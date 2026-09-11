# ai-gftd-newscaster

An **AI news broadcast actor** for gftdcojp — it designs an AI news *channel*
(編成表 as data), generates the daily AI news (rundown → anchor script), renders
it as a YouTube-ready video, and routes every publication through a human
editorial sign-off. It is the **B 層**（編成・生成・配信）counterpart of
[`ai-gftd-news`](../ai-gftd-news)（A 層 = 一次情報収集のみ）, and it also
ingests **social posts from GFTD's own AI actors on
[`app-aozora`](../app-aozora)**（yoro AppView）as a second, independent
provenance for the `fleet-pulse` segment（ADR-2607021400）.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) StateGraph
runtime — the same sealed-intelligence + independent-governor pattern as
robotaxi-actor (AR1 ⊣ SafetyGovernor), gftd-talent-actor (HR-LLM ⊣
PolicyGovernor) and ai-gftd-itonami (ops-LLM ⊣ CertGovernor). Here it is
**anchor-LLM ⊣ EditorialGovernor**（放送考査）. Design: ADR-2607020910
（+ ADR-2607021400 for the app-aozora social-post source）.

## The core contract

```
A 層記事 (ai-gftd-news: art-<sha256(url)>, provenance, rightsPolicy, score)
        ╲
         ╲ ingest = durable ground datoms (observe; always on)
app-aozora actor social post (yoro getAuthorFeed, :social-roster only)
         ╱  同じ article-shaped datom へ写像（source-type "social"）
        ╱
  ┌───────────┐  rundown/script/     ┌───────────────────┐
  │ anchor-LLM│  render-spec/publish │ EditorialGovernor │ (independent system)
  │  (sealed) │ ───────────────────▶ │     放送考査       │
  └───────────┘   + cited articles   └────────┬──────────┘
                            commit ◀──────────┼─────────▶ hold (uncited source /
                                │             │            rights-blocked /
                        Renderer port     escalate        unregistered actor /
                        (video assets)        │            missing disclosure —
                                              ▼            un-overridable)
                                   人間の editorial sign-off
                                   （episode/publish は常に人間）
                                              │ approved
                                              ▼
                                      Publisher port (YouTube)
```

**The actor never airs a proposal the EditorialGovernor would reject, and never
posts to the outside world without a human editorial sign-off.** Hard editorial
invariants — source-traceability（cites ⊆ ingested）, rights-gate（rightsPolicy
publish 可）, **actor-roster-gate**（social 投稿は channel `:social-roster`
登録済みアクターのみ引用可 — なりすまし排除）, disclosure（`:disclosure
:ai-generated`）, no-actuation — force **hold** and cannot be approved past; a
clean publication still routes to a human editor.

app-aozora の social post は press 記事と**同じ article-shaped ground datom**
に写像される（`newscaster.aozora/post->article`）ので、governor/anchorllm の
rundown・script・render-spec・publish-meta ロジックは provenance（press ‖
social）を区別せず共有する。信頼できるのは rights-policy（press）ではなく
**channel :social-roster に登録された actor-did**（social）で、この検査は
fetch/mapping 層だけでなく EditorialGovernor 側でも独立に行う（封じ込めの原則:
取得層は信用しない）。

## The channel is data

`newscaster.channel/default-channel` — **GFTD AI News**: daily, ja, ~3.5 min.

| segment | 尺 | items | style |
|---|---|---|---|
| cold-open | 15s | 1 | headline tease |
| top-stories | 45s ×3 | 3 | story + 出典 lower-third |
| fleet-pulse | 20s | 1 | app-aozora actor social post（ADR-2607021400） |
| one-more-thing | 30s | 1 | light |
| outro | 15s | — | credits + AI 開示 |

Persona（anchor "Yuzu"）, visual（1280×720\@30fps, studio-dark, caption-box
lower-thirds = kami-engine の `kami.mangaka.page` 語彙の放送版）, the YouTube
defaults（category 28, unlisted, **containsSyntheticMedia 開示**）, and
**`:social-roster`**（`fleet-pulse` に引用してよい app-aozora アクターの
`{:did :handle}` allowlist）all live on the channel entity — the actor derives
rundown / script / render-spec / publish-meta from it.

## Injected ports (swap)

| port | mock（既定・決定的） | live |
|---|---|---|
| Store | `MemStore` | `DatomicStore`（langchain.db `:db-api` → 実 Datomic / kotoba pod） |
| Advisor | `mock-advisor`（rights-aware + roster-aware）/ `careless-advisor`（封じ込めデモ用） | `llm-advisor`（langchain.model） |
| NewsFeed | `mock-feed` | A 層 kotoba XRPC（follow-up） |
| SocialFeed | `mock-social-feed` | `newscaster.aozora/http-social-feed` — app-aozora yoro AppView XRPC（`com.etzhayyim.yoro.feed.getAuthorFeed`、`AOZORA_APPVIEW_URL`）。roster の各アクターの post を article-shaped datom に写像。ADR-2607021400 |
| Narrator | `mock-narrator`（無音） | `newscaster.tts/http-narrator` — open-weight TTS gateway（`TTS_URL`）。backend: **TADA**（`HumeAI/tada-3b-ml` = Hume 純正 open weights、ja 含む多言語 = hume quality）‖ **Kokoro**（82M Apache、ローカル/CI 用）。ADR-2607021030 |
| Renderer | `mock-renderer` | `newscaster.render/slide-renderer` — Java2D 16:9 news-card + Narrator 音声（尺は音声実尺で同期）+ ffmpeg → mp4。`IMAGEGEN_URL` で背景生成、`ANIMEKA_URL` で ai-gftd-animeka に cut 構造をミラー |
| Publisher | `mock-publisher` | `newscaster.youtube/publisher` — YouTube Data API v3（`YT_ACCESS_TOKEN`、承認後のみ） |

## Run

```bash
kbb -M:dev:run     # design channel → generate today's AI news → render mp4 (out/) → human sign-off → publish (mock)
kbb -M:dev:test    # editorial contract + store parity + advisor + channel + render smoke
kbb -M:lint        # clj-kondo (errors fail)

# ナレーションつき（open-weight TTS gateway。ADR-2607021030）
# ⚠ Python 3.12 系で（3.14 は spacy が未 build）。ja は unidic、en は spacy モデルが要る
uv venv --python python3.12 .venv-tts
uv pip install --python .venv-tts/bin/python kokoro soundfile "misaki[ja]" \
  "en-core-web-sm @ https://github.com/explosion/spacy-models/releases/download/en_core_web_sm-3.8.0/en_core_web_sm-3.8.0-py3-none-any.whl"
.venv-tts/bin/python -m unidic download
BACKEND=kokoro PORT=8123 .venv-tts/bin/python scripts/tts_server.py &   # 軽量 backend
TTS_URL=http://127.0.0.1:8123 kbb -M:dev:run                        # ja + en 音声つき mp4
# hume quality: BACKEND=tada（HumeAI/tada-3b-ml、bf16 ~9GB — GPU pod 推奨）
```

The demo ingests an A 層 article and two app-aozora actor social posts, shows a
**channel-unregistered actor's post being held on `:unregistered-actor`** and a
**careless advisor being held on `:rights-blocked`**（封じ込めの実演、いずれも
上書き不可）, composes the rundown（press top-stories + fleet-pulse from the
registered actor's post）, generates the anchor script（これが生成された AI
ニュース）, renders `out/ep-20260702/episode.mp4`（ffmpeg が PATH に在るとき;
無ければ PNG frames）, interrupts for the editorial sign-off, publishes via the
mock publisher, and prints the append-only 放送台帳.

## Layout

```
src/newscaster/channel.cljc    チャンネル設計 = data（編成表/ペルソナ/:langs/voice registry/:social-roster）
src/newscaster/store.cljc      Store protocol + MemStore ‖ DatomicStore + 放送台帳
src/newscaster/anchorllm.cljc  anchor-LLM (sealed): mock ‖ careless ‖ llm-advisor（:i18n 原稿、press/social 独立プール）
src/newscaster/governor.cljc   EditorialGovernor（+ :voice-consent / :unsupported-lang / :unregistered-actor）
src/newscaster/phase.cljc      Phase 0→3（publish は決して auto にならない）
src/newscaster/ports.cljc      NewsFeed / SocialFeed / Narrator / Renderer / Publisher protocols + mocks
src/newscaster/operation.cljc  BroadcastActor StateGraph (ingest ‖ produce、:videos per-lang)
src/newscaster/aozora.clj      app-aozora bridge: post→article 写像 + yoro AppView XRPC client（ADR-2607021400）
src/newscaster/render.clj      SlideRenderer: news-card + ナレーション尺同期 + ffmpeg
src/newscaster/tts.clj         HttpNarrator: open-weight TTS gateway driver（WAV 実尺/cid）
src/newscaster/animeka.clj     ai-gftd-animeka XRPC ミラー（EDN body）
src/newscaster/youtube.clj     YouTube Data API v3 publisher（承認後のみ）
src/newscaster/sim.cljc        demo driver（ja + en の 2 言語 render）
scripts/tts_server.py          TTS gateway サーバ（BACKEND=kokoro|tada）
```

## Follow-ups

- A 層 NewsFeed の kotoba XRPC 実装（`news.gftd.ai` の `qListArticles` 経由）。
- `:social-roster` の実 did への差し替え — 現状は `newscaster.channel/
  default-channel` に illustrative な demo did が入っているだけで、実際に
  app-aozora へ post するアクター（robotaxi-actor / itonami 等）が登録され
  次第、west/RAD identity の did:web と揃える。`SocialFeed` live 実装
  （`newscaster.aozora/http-social-feed`、`AOZORA_APPVIEW_URL`）はコード
  としては動くが、上記ロースター更新までは mock 運用が既定。
- app-aozora の post-level moderation label（`com.atproto.label.queryLabels`）
  を governor に配線する follow-up — 現状は roster gate（真正性）のみで、
  ラベル済み投稿の hold（品質/安全性）は未実装。
- animeka の ComfyUI / ffmpeg adapter が結線され次第、SlideRenderer →
  `cutRunner` 実レンダへ昇格。3D スタジオは `kami.backend.host`（現状スタブ）
  or kami-cine `encode` 契約（pod 側）。
- TADA の persona reference 音源整備（演技指示相当は reference audio 依存）と
  GPU pod での BACKEND=tada 常用化。YouTube per-locale publication /
  multi-audio track（API allowlist 待ち）。
