(ns newscaster.governor
  "EditorialGovernor（放送考査）— the independent editorial layer that earns the
  anchor-LLM the right to *propose*. The LLM has no notion of rights policy,
  source traceability or the disclosure charter, so this MUST be a separate
  system (rules over the ingested ground datoms) able to *reject* a proposal
  and fall back to HOLD — the newscaster analog of robotaxi's MRC / itonami's
  CertGovernor.

  HARD invariants（人間でも上書き不可）:
    1. source-traceability — rundown/script が引用する article は全て store に
       ingest 済み（幻覚ニュースの構造的排除。cites ⊆ ingested）。
    2. rights-gate         — 引用 article の rightsPolicy が publish 可で無ければ
       hold（A 層 news.policy と同じ語彙 + social 投稿用の \"actor-original\"）。
    3. actor-roster-gate   — :source-type \"social\" の article は channel
       :social-roster に登録済みの :actor-did のみ引用可（app-aozora
       アクターのなりすまし/未検証アカウント混入を排除、ADR-2607021400）。
    4. disclosure          — publish proposal は :disclosure :ai-generated
       （合成メディア開示）を含む。
    5. no-actuation        — proposal の effect は :proposal|:asset のみ。外部公開
       （YouTube upload）は publish op の人間承認後に Publisher port だけが行う。
  SOFT:
    6. Confidence floor → escalate.
    7. :episode/publish は外部公開 = high-stakes → ALWAYS human approval."
  (:require [newscaster.store :as store]))

(def confidence-floor 0.6)

;; A 層 news.policy と同じ rightsPolicy 語彙（publish 可否のみ）+ app-aozora
;; social 投稿用の "actor-original"（自組織アクターの一次投稿 = 著作権上の懸念
;; なし。真正性は rights ではなく :social-roster 側で担保、下記 3）。
(def rights-policy-table
  {"public-domain"   true
   "gov-open"        true
   "cc-by"           true
   "fair-use-quote"  true
   "original"        true
   "actor-original"  true
   "transcript-only" false
   "broadcast"       false})

(defn publish-allowed? [rights-policy]
  (get rights-policy-table (or rights-policy "unknown") false))

;; ───────────────────────── invariant checks ─────────────────────────

(def ^:private allowed-effect
  {:rundown/compose :proposal
   :script/draft    :proposal
   :video/produce   :asset
   :episode/publish :proposal})

(defn- actuation-violations [op proposal]
  (when (not= (allowed-effect op) (:effect proposal))
    [{:rule :no-actuation
      :detail (str "actor は直接公開/作動しない（effect=" (:effect proposal)
                   "、許可=" (allowed-effect op) "）")}]))

(defn- citation-violations
  "cites ⊆ ingested articles（幻覚ニュース排除）+ rights-gate + social 投稿は
  channel :social-roster に登録済みの :actor-did のみ引用可
  （:unregistered-actor、なりすまし排除、ADR-2607021400）。"
  [st ch article-ids]
  (let [arts    (keep #(store/article st %) article-ids)
        missing (remove #(store/article st %) article-ids)
        blocked (->> arts (remove #(publish-allowed? (:rights-policy %))) (map :id))
        roster  (set (keep :did (:social-roster ch)))
        unreg   (->> arts
                     (filter #(= "social" (:source-type %)))
                     (remove #(contains? roster (:actor-did %)))
                     (map :id))]
    (cond-> []
      (seq missing)
      (conj {:rule :uncited-source
             :detail (str "未 ingest の記事を引用: " (vec missing))})
      (seq blocked)
      (conj {:rule :rights-blocked
             :detail (str "権利上使用不可の記事を引用: " (vec blocked))})
      (seq unreg)
      (conj {:rule :unregistered-actor
             :detail (str "channel 未登録アクターの social post を引用: " (vec unreg))}))))

(defn- rundown-violations [st {:keys [channel]} proposal]
  (let [ch    (store/channel-of st channel)
        items (:rundown proposal)
        cited (vec (mapcat :article-ids items))]
    (into
     (cond-> []
       (nil? ch)
       (conj {:rule :no-channel :detail (str "未登録チャンネル " channel)})
       (empty? (filter (comp seq :article-ids) items))
       (conj {:rule :empty-rundown :detail "記事を引用する item が 1 つも無い"}))
     (citation-violations st ch cited))))

(defn- script-violations [st {:keys [episode]} proposal]
  (let [ep      (store/episode st episode)
        ch      (store/channel-of st (:channel ep))
        rundown (:rundown ep)
        allowed (set (mapcat :article-ids rundown))
        segs    (:script proposal)
        cited   (vec (mapcat :article-ids segs))
        stray   (remove allowed cited)]
    (into
     (cond-> []
       (empty? rundown)
       (conj {:rule :missing-rundown :detail "committed rundown が無い"})
       (empty? (filter (comp seq :lines) segs))
       (conj {:rule :empty-script :detail "本文の無い script"})
       (and (seq rundown) (seq stray))
       (conj {:rule :uncited-source
              :detail (str "rundown 外の記事を引用: " (vec stray))}))
     (citation-violations st ch cited))))

(defn- video-violations [st {:keys [episode]} proposal]
  (let [ep     (store/episode st episode)
        ch     (store/channel-of st (:channel ep))
        spec   (:render-spec proposal)
        lang   (or (:lang spec) (get-in spec [:narration :lang]))
        voice  (get-in spec [:narration :voice])
        langs  (set (or (:langs ch) [(:lang ch)]))
        voices (set (keep :voice (vals (get-in ch [:persona :voices]))))]
    (cond-> []
      (empty? (:script ep))
      (conj {:rule :missing-script :detail "committed script が無い"})
      ;; ADR-2607021030: 未対応言語の render を型で止める
      (and ch lang (not (contains? langs lang)))
      (conj {:rule :unsupported-lang
             :detail (str "channel 未対応の言語: " lang " (対応: " (vec langs) ")")})
      ;; ADR-2607021030: narration voice は channel 登録済みのみ（無断クローン排除）
      (and ch voice (not (contains? voices voice)))
      (conj {:rule :voice-consent
             :detail (str "未登録 voice でのナレーション: " voice)}))))

(defn- publish-violations [st {:keys [episode]} proposal]
  (let [ep (store/episode st episode)]
    (cond-> []
      (nil? (:video ep))
      (conj {:rule :not-rendered :detail "render 済み video asset が無い"})
      (not= :ai-generated (get-in proposal [:publish-meta :disclosure]))
      (conj {:rule :missing-disclosure
             :detail "AI 生成開示（:disclosure :ai-generated）が無い"}))))

(defn check
  "Censors an anchor-LLM proposal for a produce op. Returns
   {:ok? :violations :confidence :hard? :escalate? :high-stakes?}.

   Hard violations (traceability/rights/disclosure/actuation) force HOLD and
   cannot be overridden. :episode/publish is always high-stakes → human
   approval even when clean."
  [request proposal st]
  (let [op   (:op request)
        hard (into (vec (actuation-violations op proposal))
                   (case op
                     :rundown/compose (rundown-violations st request proposal)
                     :script/draft    (script-violations st request proposal)
                     :video/produce   (video-violations st request proposal)
                     :episode/publish (publish-violations st request proposal)
                     []))
        conf    (:confidence proposal 0.0)
        low?    (< conf confidence-floor)
        stakes? (= :episode/publish op)
        hard?   (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request verdict]
  {:t :editorial-hold :op (:op request) :episode (:episode request)
   :disposition :hold :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict) :confidence (:confidence verdict)})
