(ns newscaster.anchorllm
  "anchor-LLM — the contained intelligence node. It composes the AI news
  channel's rundown（編成）, drafts the anchor script（原稿 = 生成される AI
  ニュース本文）, derives the render-spec and the publish metadata, and returns
  a PROPOSAL — never a committed episode and never an external post. Every
  output is censored by `newscaster.governor` before anything is recorded, and
  a publish proposal always routes to a human editor.

  Advisor is injected (mock | real LLM via langchain.model), same as
  robotaxi.ar1 / talent.hrllm / itonami.opsllm.

  Proposal shape (op-specific payload + common keys):
    {:rundown [...]|:script [...]|:render-spec {...}|:publish-meta {...}
     :summary str :rationale str :cites [article-id ..]
     :effect :proposal|:asset   ; publish/compose/draft は :proposal、render は :asset
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.model :as model]
            [newscaster.channel :as channel]
            [newscaster.governor :as gov]
            [newscaster.store :as store]))

;; ───────────────────────── deterministic mock ─────────────────────────

(defn- ranked-articles
  "priority 降順。kind は :press（source-type \"social\" 以外 = A 層記事）か
  :social（app-aozora の GFTD アクター post、ADR-2607021400）で候補を分ける
  — press 枠と fleet-pulse 枠が互いのプールを取り合わない。rights-aware?=true
  のとき（標準 mock）は governor と同じ rights-gate + channel :social-roster
  （actor-roster-gate）を鏡映して使用可能な記事だけを返す。false は権利/roster
  を知らない「素の知能」の振る舞い（careless-advisor。governor が止める側）。"
  [st rights-aware? kind ch]
  (let [social? (fn [a] (= "social" (:source-type a)))
        wants?  (if (= kind :social) social? (complement social?))
        roster  (set (keep :did (:social-roster ch)))]
    (->> (store/all-articles st)
         (filter wants?)
         (filter #(or (not rights-aware?) (gov/publish-allowed? (:rights-policy %))))
         (filter #(or (not rights-aware?) (not (social? %))
                      (contains? roster (:actor-did %))))
         (sort-by :priority)
         reverse
         vec)))

(defn- compose-rundown [st {:keys [channel] :as _req} rights-aware?]
  (let [ch      (or (store/channel-of st channel) channel/default-channel)
        slots   (channel/story-slots ch)
        press   (ranked-articles st rights-aware? :press ch)
        social  (ranked-articles st rights-aware? :social ch)
        n       (count press)
        pick    (fn [i] (when (pos? n) (nth press (min i (dec n)))))
        ;; cold-open はトップ press 記事のティザー、:story が順に消費、
        ;; :light は最後の press 記事、:social は最新の fleet-pulse post
        items (loop [ss slots, i 0, acc []]
                (if-let [{:keys [style] :as slot} (first ss)]
                  (let [a (case style
                            :headline (pick 0)
                            :light    (peek press)
                            :social   (first social)
                            (pick i))]
                    (recur (rest ss) (if (= style :story) (inc i) i)
                           (conj acc (assoc slot :article-ids (if a [(:id a)] [])
                                            :angle (:title a)))))
                  acc))
        cites (vec (distinct (mapcat :article-ids items)))]
    {:rundown    (conj items {:segment :outro :style :credits :duration-s 15
                              :article-ids []})
     :summary    (str "本日の編成: " (count cites) " 本の記事/投稿から "
                      (count items) " 枠")
     :rationale  "priority 降順で編成表のスロットに割当（press/social は provenance ごとに独立プール）"
     :cites      cites
     :effect     :proposal
     :confidence (if (>= (+ (count press) (count social)) 2) 0.85 0.4)}))

(defn- story-lines [a style]
  (case style
    :headline [(str "こんばんは。GFTD AI News、今日の AI ニュースです。")
               (str "トップは「" (:title a) "」。")]
    :story    [(str "「" (:title a) "」— 出典: " (:source-name a) "。")
               (str (:summary a))]
    :social   [(str "続いて、app-aozora より AI アクターたちの近況です。")
               (str "@" (:handle a) "（" (:source-name a) "）: 「" (:summary a) "」")]
    :light    [(str "最後にもうひとつ。「" (:title a) "」。")
               (str (:summary a))]
    :credits  ["以上、GFTD AI News でした。出典はすべて概要欄に記載しています。"
               "この番組は AI が生成しています。"]
    [(str (:title a))]))

(defn- story-lines-en
  "決定的な英語版（mock。実運用は llm-advisor が翻訳する）。英語ナレーションに
  日本語原文を混ぜると英語 G2P が破綻するため、:title-en/:summary-en があれば
  それを、無ければ出典名ベースの汎用文を使う（原題は画面と概要欄に残る）。"
  [a style]
  (let [title   (or (:title-en a)
                    (str "a new report from " (:source-name a)))
        summary (or (:summary-en a) "Details are on screen and linked below.")]
    (case style
      :headline ["Good evening — this is GFTD AI News."
                 (str "Our top story: " title ".")]
      :story    [(str title " — source: " (:source-name a) ".")
                 summary]
      :social   ["Now, an update from our AI actors over on aozora."
                 (str "@" (:handle a) " (" (:source-name a) ") posted: \""
                     (or (:summary-en a) (:summary a)) "\"")]
      :light    [(str "And one more thing: " title ".")
                 summary]
      :credits  ["That's all from GFTD AI News. All sources are listed below."
                 "This program is generated by AI."]
      [title])))

(defn- draft-script [st {:keys [episode] :as _req}]
  (let [ep      (store/episode st episode)
        ch      (or (store/channel-of st (:channel ep)) channel/default-channel)
        extra   (rest (or (:langs ch) [(:lang ch)]))
        rundown (:rundown ep)
        segs    (vec (for [{:keys [segment style duration-s article-ids]} rundown
                           :let [a (some #(store/article st %) article-ids)]]
                       (cond->
                        {:segment     segment
                         :duration-s  duration-s
                         :article-ids (vec article-ids)
                         :lines       (story-lines a style)
                         :caption     (if a
                                        (str "出典: " (:source-name a) " — " (:url a))
                                        "GFTD AI News — AI が生成した番組です")}
                         ;; 追加言語は :i18n locale map（kami.mangaka.text 流儀）。
                         ;; cites はセグメント構造側 → 全 locale で構造的に同一。
                         (some #{"en"} extra)
                         (assoc :i18n
                                {"en" {:lines (story-lines-en a style)
                                       :caption (if a
                                                  (str "Source: " (:source-name a)
                                                       " — " (:url a))
                                                  "GFTD AI News — generated by AI")}}))))
        cites   (vec (distinct (mapcat :article-ids segs)))]
    {:script     segs
     :summary    (str episode " 原稿: " (count segs) " セグメント")
     :rationale  "committed rundown の各 item を、出典を読み上げる原稿に展開"
     :cites      cites
     :effect     :proposal
     :confidence (if (seq rundown) 0.85 0.3)}))

(defn- localize-segment
  "主言語はそのまま、追加言語は :i18n の lines/caption で差し替える。"
  [seg lang primary?]
  (let [base (select-keys seg [:segment :duration-s :lines :caption :article-ids])]
    (if primary?
      base
      (merge base (select-keys (get-in seg [:i18n lang]) [:lines :caption])))))

(defn- produce-render-spec [st {:keys [episode lang] :as _req}]
  (let [ep      (store/episode st episode)
        ch      (or (store/channel-of st (:channel ep)) channel/default-channel)
        visual  (:visual ch)
        primary (channel/primary-lang ch)
        lang    (or lang primary)]
    {:render-spec {:resolution (:resolution visual)
                   :fps        (:fps visual)
                   :backdrop   (:backdrop visual)
                   :accent     (:accent visual)
                   :brand      (:title ch)
                   :date       (:date ep)
                   :lang       lang
                   :narration  {:lang  lang
                                :voice (get-in ch [:persona :voices lang :voice])}
                   :segments   (vec (for [s (:script ep)]
                                      (localize-segment s lang (= lang primary))))}
     :summary    (str episode " render-spec (" lang "): "
                      (count (:script ep)) " セグメント")
     :rationale  "channel visual + committed script から導出（per-lang localize）"
     :cites      (vec (distinct (mapcat :article-ids (:script ep))))
     :effect     :asset
     :confidence (if (seq (:script ep)) 0.8 0.3)}))

(defn- publish-meta [st {:keys [episode] :as _req}]
  (let [ep    (store/episode st episode)
        ch    (or (store/channel-of st (:channel ep)) channel/default-channel)
        arts  (keep #(store/article st %)
                    (distinct (mapcat :article-ids (:script ep))))
        yt    (:youtube ch)]
    {:publish-meta {:title       (str (:title ch) " — " (:date ep))
                    :description (str (:description ch) "\n\n出典:\n"
                                      (str/join "\n" (map #(str "- " (:title %) " / "
                                                                (:source-name %) " " (:url %))
                                                          arts))
                                      "\n\nこの動画は AI によって生成されています。")
                    :tags        (:tags yt)
                    :category-id (:category-id yt)
                    :visibility  (:visibility yt)
                    :made-for-kids false
                    :disclosure  :ai-generated}
     :summary    (str episode " を公開申請（" (count arts) " 出典）")
     :rationale  "channel の YouTube 既定メタ + episode の出典一覧から導出"
     :cites      (mapv :id arts)
     :effect     :proposal
     :confidence (if (:video ep) 0.9 0.3)}))

(defn infer [st {:keys [op] :as req} rights-aware?]
  (case op
    :rundown/compose (compose-rundown st req rights-aware?)
    :script/draft    (draft-script st req)
    :video/produce   (produce-render-spec st req)
    :episode/publish (publish-meta st req)
    {:summary "未対応" :rationale (str op) :cites [] :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────

(defprotocol Advisor
  (-advise [advisor store request]))

(defn mock-advisor
  "決定的 mock。governor が検査する不変条件（rights-gate 等）を鏡映するので、
  クリーンな store からはクリーンな proposal が出る。"
  [] (reify Advisor (-advise [_ st req] (infer st req true))))

(defn careless-advisor
  "権利を知らない素の知能ノード（priority だけで記事を選ぶ）。封じ込めのデモ/
  テスト用 — EditorialGovernor が :rights-blocked で hold する側。"
  [] (reify Advisor (-advise [_ st req] (infer st req false))))

(def ^:private system-prompt
  (str "あなたは AI ニュースチャンネルのアンカー兼編成者です。与えられた事実"
       "（ingest 済み記事・チャンネル設計・episode 状態）のみに基づき、提案を 1 つ"
       " EDN マップで返します。EDN だけを出力。\n"
       "共通キー: :summary :rationale :cites(引用した記事 id) :effect :confidence(0..1)。\n"
       "op ごとの payload: :rundown/compose→:rundown、:script/draft→:script、"
       ":video/produce→:render-spec、:episode/publish→:publish-meta"
       "（:disclosure :ai-generated を必ず含める）。\n"
       "重要: ingest 済みでない記事を引用しない。直接の外部公開は提案しない"
       "（effect は :proposal か :asset。公開は人間の承認後に行われる）。"))

(defn- facts-for [st {:keys [episode channel]}]
  {:channel  (store/channel-of st (or channel (:channel (store/episode st episode))))
   :episode  (store/episode st episode)
   :articles (store/all-articles st)})

(defn- parse-proposal [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p (update :cites #(vec (or % [])))
            (update :confidence #(if (number? %) (double %) 0.0))
            (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できません" :rationale (str content)
       :cites [] :effect :noop :confidence 0.0})))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel (Anthropic / OpenAI-compatible
  / mock-model). Output is parsed defensively → an unparseable response is a
  confidence-0 noop the governor will hold/escalate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [resp (model/-generate chat-model
                    [{:role :system :content system-prompt}
                     {:role :user :content (str "操作:" (:op req) " episode:" (:episode req)
                                                "\n事実:" (pr-str (facts-for st req)))}]
                    gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace [request proposal]
  {:t :anchor-proposal :op (:op request) :episode (:episode request)
   :summary (:summary proposal) :rationale (:rationale proposal)
   :cites (:cites proposal) :confidence (:confidence proposal)})
