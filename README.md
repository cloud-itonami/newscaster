# ai-gftd-newscaster

An **AI news broadcast actor** for gftdcojp — it designs an AI news *channel*
(編成表 as data), generates the daily AI news (rundown → anchor script), renders
it as a YouTube-ready video, and routes every publication through a human
editorial sign-off. It is the **B 層**（編成・生成・配信）counterpart of
[`ai-gftd-news`](../ai-gftd-news)（A 層 = 一次情報収集のみ）.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj) StateGraph
runtime — the same sealed-intelligence + independent-governor pattern as
robotaxi-actor (AR1 ⊣ SafetyGovernor), gftd-talent-actor (HR-LLM ⊣
PolicyGovernor) and ai-gftd-itonami (ops-LLM ⊣ CertGovernor). Here it is
**anchor-LLM ⊣ EditorialGovernor**（放送考査）. Design: ADR-2607020910.

## The core contract

```
A 層記事 (ai-gftd-news: art-<sha256(url)>, provenance, rightsPolicy, score)
        │  ingest = durable ground datoms (observe; always on)
        ▼
  ┌───────────┐  rundown/script/     ┌───────────────────┐
  │ anchor-LLM│  render-spec/publish │ EditorialGovernor │ (independent system)
  │  (sealed) │ ───────────────────▶ │     放送考査       │
  └───────────┘   + cited articles   └────────┬──────────┘
                            commit ◀──────────┼─────────▶ hold (uncited source /
                                │             │            rights-blocked /
                        Renderer port     escalate         missing disclosure —
                        (video assets)        │            un-overridable)
                                              ▼
                                   人間の editorial sign-off
                                   （episode/publish は常に人間）
                                              │ approved
                                              ▼
                                      Publisher port (YouTube)
```

**The actor never airs a proposal the EditorialGovernor would reject, and never
posts to the outside world without a human editorial sign-off.** Hard editorial
invariants — source-traceability（cites ⊆ ingested）, rights-gate（rightsPolicy
publish 可）, disclosure（`:disclosure :ai-generated`）, no-actuation — force
**hold** and cannot be approved past; a clean publication still routes to a
human editor.

## The channel is data

`newscaster.channel/default-channel` — **GFTD AI News**: daily, ja, ~3 min.

| segment | 尺 | items | style |
|---|---|---|---|
| cold-open | 15s | 1 | headline tease |
| top-stories | 45s ×3 | 3 | story + 出典 lower-third |
| one-more-thing | 30s | 1 | light |
| outro | 15s | — | credits + AI 開示 |

Persona（anchor "Yuzu"）, visual（1280×720\@30fps, studio-dark, caption-box
lower-thirds = kami-engine の `kami.mangaka.page` 語彙の放送版）and the YouTube
defaults（category 28, unlisted, **containsSyntheticMedia 開示**）all live on the
channel entity — the actor derives rundown / script / render-spec / publish-meta
from it.

## Injected ports (swap)

| port | mock（既定・決定的） | live |
|---|---|---|
| Store | `MemStore` | `DatomicStore`（langchain.db `:db-api` → 実 Datomic / kotoba pod） |
| Advisor | `mock-advisor`（rights-aware）/ `careless-advisor`（封じ込めデモ用） | `llm-advisor`（langchain.model） |
| NewsFeed | `mock-feed` | A 層 kotoba XRPC（follow-up） |
| Renderer | `mock-renderer` | `newscaster.render/slide-renderer` — Java2D 16:9 news-card + ffmpeg → mp4。`IMAGEGEN_URL` で背景生成、`ANIMEKA_URL` で ai-gftd-animeka に cut 構造をミラー |
| Publisher | `mock-publisher` | `newscaster.youtube/publisher` — YouTube Data API v3（`YT_ACCESS_TOKEN`、承認後のみ） |

## Run

```bash
clojure -M:dev:run     # design channel → generate today's AI news → render mp4 (out/) → human sign-off → publish (mock)
clojure -M:dev:test    # editorial contract + store parity + advisor + channel + render smoke
clojure -M:lint        # clj-kondo (errors fail)
```

The demo ingests an article, shows a **careless advisor being held on
`:rights-blocked`**（封じ込めの実演）, composes the rundown, generates the anchor
script（これが生成された AI ニュース）, renders `out/ep-20260702/episode.mp4`
（ffmpeg が PATH に在るとき; 無ければ PNG frames）, interrupts for the editorial
sign-off, publishes via the mock publisher, and prints the append-only 放送台帳.

## Layout

```
src/newscaster/channel.cljc    チャンネル設計 = data（編成表/ペルソナ/visual/YouTube 既定）
src/newscaster/store.cljc      Store protocol + MemStore ‖ DatomicStore + 放送台帳
src/newscaster/anchorllm.cljc  anchor-LLM (sealed): mock ‖ careless ‖ llm-advisor
src/newscaster/governor.cljc   EditorialGovernor（放送考査; HARD/SOFT invariants）
src/newscaster/phase.cljc      Phase 0→3（publish は決して auto にならない）
src/newscaster/ports.cljc      NewsFeed / Renderer / Publisher protocols + mocks
src/newscaster/operation.cljc  BroadcastActor StateGraph (ingest ‖ produce)
src/newscaster/render.clj      SlideRenderer: Java2D news-card + ffmpeg（+IMAGEGEN/ANIMEKA）
src/newscaster/animeka.clj     ai-gftd-animeka XRPC ミラー（EDN body）
src/newscaster/youtube.clj     YouTube Data API v3 publisher（承認後のみ）
src/newscaster/sim.cljc        demo driver
```

## Follow-ups

- A 層 NewsFeed の kotoba XRPC 実装（`news.gftd.ai` の `qListArticles` 経由）。
- animeka の ComfyUI / TTS / ffmpeg adapter が結線され次第、SlideRenderer →
  `cutRunner` 実レンダへ昇格。3D スタジオは `kami.backend.host`（現状スタブ）
  or kami-cine `encode` 契約（pod 側）。
- TTS ナレーション（現状は無音の字幕動画）と多言語（`kami.mangaka.text` の
  locale map）。
