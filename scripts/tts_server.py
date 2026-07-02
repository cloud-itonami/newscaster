#!/usr/bin/env python3
"""Open-weight TTS gateway for ai-gftd-newscaster (ADR-2607021030).

Contract:  POST /tts  {"text": str, "lang": "ja"|"en"|.., "voice": str}
           -> 200 audio/wav
           GET  /health -> {"ok": true, "backend": ...}

Backends (BACKEND env, default "kokoro"):
  kokoro — hexgrad/Kokoro-82M (Apache-2.0). 9 langs incl. ja. Fast on CPU.
           pip install kokoro soundfile "misaki[ja]"
  tada   — HumeAI/tada-3b-ml (Hume's own open weights; code MIT, weights
           Llama 3.2 Community License). Multilingual incl. ja = "hume
           quality" without the Hume API. Needs ~9GB (bf16) — GPU pod
           recommended. Voice = reference wav+txt in $TADA_VOICE_DIR/<voice>.{wav,txt}
           pip install git+https://github.com/HumeAI/tada torchaudio

Run:  BACKEND=kokoro PORT=8123 python3 scripts/tts_server.py
"""
import io
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BACKEND = os.environ.get("BACKEND", "kokoro")
PORT = int(os.environ.get("PORT", "8123"))

# ───────────────────────── kokoro backend ─────────────────────────

KOKORO_LANG = {"en": "a", "ja": "j", "zh": "z", "es": "e", "fr": "f",
               "hi": "h", "it": "i", "pt": "p"}
KOKORO_DEFAULT_VOICE = {"ja": "jf_alpha", "en": "af_heart"}

_kokoro_pipelines = {}


def kokoro_tts(text, lang, voice):
    import numpy as np
    import soundfile as sf
    from kokoro import KPipeline

    code = KOKORO_LANG.get(lang, "a")
    if code not in _kokoro_pipelines:
        _kokoro_pipelines[code] = KPipeline(lang_code=code,
                                            repo_id="hexgrad/Kokoro-82M")
    pipeline = _kokoro_pipelines[code]
    voice = voice or KOKORO_DEFAULT_VOICE.get(lang, "af_heart")
    chunks = [audio for (_gs, _ps, audio) in pipeline(text, voice=voice)]
    wav = np.concatenate(chunks) if chunks else np.zeros(2400, dtype="float32")
    buf = io.BytesIO()
    sf.write(buf, wav, 24000, format="WAV", subtype="PCM_16")
    return buf.getvalue()


# ───────────────────────── tada backend ─────────────────────────

_tada = {}


def tada_tts(text, lang, voice):
    """HumeAI/tada — LLM-based TTS. Voice prompt = reference audio+transcript
    ($TADA_VOICE_DIR/<voice>.wav + .txt). See github.com/HumeAI/tada."""
    import torch
    import torchaudio

    if "model" not in _tada:
        from tada.modules.encoder import Encoder
        from tada.modules.tada import TadaForCausalLM
        _tada["encoder"] = Encoder.from_pretrained("HumeAI/tada-codec")
        _tada["model"] = TadaForCausalLM.from_pretrained(
            "HumeAI/tada-3b-ml",
            torch_dtype=torch.bfloat16 if torch.cuda.is_available() else torch.float32)
    voice_dir = os.environ.get("TADA_VOICE_DIR", "voices")
    prompt = None
    ref_wav = os.path.join(voice_dir, f"{voice}.wav")
    ref_txt = os.path.join(voice_dir, f"{voice}.txt")
    if voice and os.path.exists(ref_wav) and os.path.exists(ref_txt):
        with open(ref_txt) as f:
            transcript = f.read().strip()
        prompt = _tada["encoder"].encode_prompt(ref_wav, transcript)
    output = _tada["model"].generate(prompt=prompt, text=text)
    buf = io.BytesIO()
    torchaudio.save(buf, output.audio.cpu(), output.sample_rate, format="wav")
    return buf.getvalue()


BACKENDS = {"kokoro": kokoro_tts, "tada": tada_tts}


# ───────────────────────── http server ─────────────────────────

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("[tts] %s\n" % (fmt % args))

    def do_GET(self):
        if self.path == "/health":
            body = json.dumps({"ok": True, "backend": BACKEND}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_error(404)

    def do_POST(self):
        if self.path != "/tts":
            self.send_error(404)
            return
        try:
            n = int(self.headers.get("Content-Length", "0"))
            req = json.loads(self.rfile.read(n))
            wav = BACKENDS[BACKEND](req["text"], req.get("lang", "en"),
                                    req.get("voice"))
            self.send_response(200)
            self.send_header("Content-Type", "audio/wav")
            self.send_header("Content-Length", str(len(wav)))
            self.end_headers()
            self.wfile.write(wav)
        except Exception as e:  # noqa: BLE001 — report to caller, keep serving
            msg = json.dumps({"error": str(e)}).encode()
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)


if __name__ == "__main__":
    print(f"[tts] backend={BACKEND} port={PORT}", file=sys.stderr)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
