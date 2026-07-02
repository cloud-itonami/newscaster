# newscaster Actor Design — anchor-LLM as a contained intelligence node

AI ニュースチャンネル（設計=data）→ ニュース生成（rundown/原稿）→ 動画レンダ →
YouTube 公開を扱う B 層 actor。robotaxi（AR1⊣SafetyGovernor）/ talent（HR-LLM⊣
PolicyGovernor）/ itonami（ops-LLM⊣CertGovernor）と同型に
**anchor-LLM⊣EditorialGovernor（放送考査）** を据える。ADR-2607020910。ingest
source は A 層記事（ai-gftd-news）に加え、app-aozora 上の GFTD AI アクター群の
social post（ADR-2607021400、fleet-pulse セグメント）。

## 1. 二つのフロー

```
ingest(record-op):   intake → record → END                ; 観測。常時ON、無作動
produce(produce-op): intake → advise → govern → decide → commit | hold | 人間承認
```

- **ingest**: `:article/ingest`（A 層 art-* の写像。url/source/rightsPolicy/score
  ごと、**または** app-aozora actor social post の写像 — `source-type
  "social"`、`newscaster.aozora/post->article`、ADR-2607021400）、
  `:channel/register`、`:asset/record`。LLM/governor/phase を通らない事実記録。
  press/social は **同じ article-shaped ground datom** に統一されるので op も
  store schema も分岐しない。
- **produce**: `:rundown/compose` → `:script/draft` → `:video/produce` →
  `:episode/publish`。anchor-LLM 提案 → EditorialGovernor 考査 → phase gate →
  publish は必ず人間（`interrupt-before`）。commit ノードだけが Renderer /
  Publisher port を実行する（LLM は proposal のみ）。

チャネル: `:request :context(:phase) :proposal :verdict :disposition :record
:approval :audit`

## 2. 注入される依存（swap）

- **Store**（`newscaster.store/Store`）: `MemStore` ‖ `DatomicStore`（langchain.db
  `:db-api` → 実 Datomic Local / kotoba pod）。episode は merge 意味論
  （rundown→script→video→publication が積み重なる）。
- **Advisor**（`newscaster.anchorllm/Advisor`）: `mock-advisor`（rights-aware、
  governor を鏡映）‖ `careless-advisor`（権利を知らない素の知能 — governor が
  止める側のデモ/テスト用）‖ `llm-advisor`（langchain.model）。破損応答は
  confidence0 noop → governor が hold。
- **Renderer / Publisher / NewsFeed / SocialFeed**（`newscaster.ports`）: mock は
  決定的・IO 無し。live は `newscaster.render`（Java2D news-card + ffmpeg。
  kami-engine の今日動く 2D 経路 = kami.mangaka.page caption-box の 16:9 版）と
  `newscaster.youtube`（Data API v3、承認後のみ）。SocialFeed の live は
  `newscaster.aozora/http-social-feed`（app-aozora yoro AppView XRPC
  `getAuthorFeed`、channel `:social-roster` の各アクターのみ取得、
  ADR-2607021400）。
- **Phase**（context `:phase 0..3`）: 生成の自律度のみ段階化。publish は常に人間。

## 3. EditorialGovernor（独立・放送考査）

HARD（人間でも上書き不可）:

| rule | 意味 |
|---|---|
| `:uncited-source` | ingest 済みでない記事の引用（幻覚ニュース）/ rundown 外の引用 |
| `:rights-blocked` | rightsPolicy が publish 不可（broadcast/transcript-only/unknown） |
| `:unregistered-actor` | social 投稿（`source-type "social"`）の `:actor-did` が channel `:social-roster` に未登録（app-aozora アクターのなりすまし排除、ADR-2607021400） |
| `:missing-disclosure` | publish-meta に `:disclosure :ai-generated` が無い |
| `:no-actuation` | effect が :proposal/:asset 以外（直接公開の試み） |
| `:voice-consent` | channel 未登録 voice でのナレーション（無断クローン排除、ADR-2607021030） |
| `:unsupported-lang` | channel `:langs` に無い言語の render |
| `:no-channel` `:empty-rundown` `:missing-rundown` `:empty-script` `:missing-script` `:not-rendered` | 工程整合性 |

SOFT: confidence < 0.6 → escalate。`:episode/publish` は外部公開 = 常に
high-stakes → 人間承認（clean でも）。

rightsPolicy 語彙は A 層 `news.policy` と同一（public-domain / gov-open / cc-by /
fair-use-quote / original 可、transcript-only / broadcast / unknown 不可）。

## 4. SSoT + 放送台帳

ground datoms（article/channel/episode/asset）が canonical。append-only の
**放送台帳**が「いつ・どの記事を根拠に・誰が承認して・何を公開したか」を不変に
残す＝出典トレーサビリティ/データ主権の核。`:t` 語彙: `:recorded :anchor-proposal
:composed :scripted :rendered :published :editorial-hold :signoff-rejected
:render-failed :publish-failed :approval-requested :editorial-signoff`。

## 5. 上流/下流の接続（層の設計）

```
ai-gftd-news (A層)     …一次収集。art-* datom graph・score・rightsPolicy
      │ NewsFeed port（写像 ingest）
app-aozora (yoro AppView)  …GFTD AI アクター群の social post
      │ SocialFeed port（post→article 写像 ingest、:social-roster 限定）
      │ ADR-2607021400
ai-gftd-newscaster (B層・本 repo)
      │ Narrator port（ADR-2607021030）
      ├─ scripts/tts_server.py … open-weight TTS gateway（POST /tts → WAV）
      │    BACKEND=tada   … HumeAI/tada-3b-ml（Hume 純正 open weights、ja 含む）
      │    BACKEND=kokoro … Kokoro-82M（Apache、ローカル/CI）
      │ Renderer port
      ├─ newscaster.render  … Java2D news-card + 音声実尺で尺同期 + ffmpeg
      ├─ ai-gftd-animeka    … cut 構造ミラー（ANIMEKA_URL）。adapter 結線後に実レンダ昇格
      └─ kami-engine        … kami.mangaka.{render,text,page}（2D）/ 将来 kami-cine encode
      │ Publisher port（人間承認後のみ）
      └─ YouTube Data API v3（containsSyntheticMedia 開示）
```

多言語（ADR-2607021030）: channel `:langs` + per-lang voice registry。script は
主言語 + `:i18n {lang {:lines :caption}}`（kami.mangaka.text の locale map 流儀）。
cites はセグメント構造側にあるため**全 locale で構造的に同一** = 翻訳で出典が
すり替わらない。`:video/produce :lang` → per-lang mp4（episode `:videos {lang v}`）。

## 6. 段階導入（Phase 0→3）

| phase | rundown/script | video | publish |
|---|---|---|---|
| 0 ingest-only | hold (:phase-disabled) | hold | hold |
| 1 assisted | 人間承認 | 人間承認 | 人間承認 |
| 2 assisted-edit | auto | 人間承認 | 人間承認 |
| 3 supervised | auto | auto | **常に人間承認** |
