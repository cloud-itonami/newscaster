(ns newscaster.sim
  "Demo: design the AI news channel, generate today's AI news, render it as a
  video, and route the publish through a human editorial sign-off.

    channel   既定チャンネル「GFTD AI News」の編成表を表示
    ingest    A 層記事の観測（ground datom）
    careless  権利を知らない advisor の rundown → EditorialGovernor が HOLD
              （:rights-blocked — 封じ込めの実演）
    rundown   anchor-LLM(mock) の編成 → commit
    script    原稿生成 = 生成された AI ニュース本文 → commit
    video     render-spec → SlideRenderer（Java2D + ffmpeg）で実 mp4 を out/ に生成
    publish   常に interrupt → 人間の editorial sign-off → mock publisher で公開
    ledger    放送台帳（append-only）
    parity    DatomicStore でも同一契約

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [newscaster.anchorllm :as anchorllm]
            [newscaster.channel :as channel]
            [newscaster.operation :as op]
            [newscaster.store :as store]
            #?@(:clj [[newscaster.preview :as preview]
                      [newscaster.render :as render]
                      [newscaster.tts :as tts]])))

(defn- line [& xs] (println (apply str xs)))

(defn- drive [actor tid req phase approve?]
  (let [res (g/run* actor {:request req :context {:phase phase}} {:thread-id tid})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  editorial sign-off — 編集者の考査 (reason: "
                (-> res :state :audit last :reason) ")")
          (let [r2 (g/run* actor {:approval {:status (if approve? :approved :rejected)
                                             :by "editor:jun"}}
                           {:thread-id tid :resume? true})]
            (line "   ▶  " (if approve? "承認" "却下") " → "
                  (get-in r2 [:state :disposition]))
            r2))
      (do (line "   → " (get-in res [:state :disposition])
                (when-let [pr (-> res :state :audit last :phase-reason)] (str " (" pr ")"))
                (when-let [b (-> res :state :audit last :basis)]
                  (when (= :hold (get-in res [:state :disposition])) (str " " (pr-str b)))))
          res))))

(defn -main [& _]
  (let [st    (store/seed-db)
        ep    "ep-20260702"
        ch-id "ch-gftd-ai-news"
        #?@(:clj [narrator (tts/http-narrator)])
        actor (op/build st #?(:clj  {:renderer (render/slide-renderer
                                                "out" narrator)}
                              :cljs {}))]
    #?(:clj (line (if narrator
                    "TTS_URL 検出 — open-weight ナレーションつきで生成します"
                    "TTS_URL 未設定 — 無音で生成します（scripts/tts_server.py 参照）")))

    (line "── チャンネル設計（data as channel）──")
    (let [ch (store/channel-of st ch-id)]
      (line "  " (:title ch) " — " (:description ch))
      (line "  編成 (" (channel/planned-duration-s ch) "s): "
            (pr-str (mapv (juxt :segment :duration-s :items) (:format ch)))))

    (line "\n── ingest（A 層記事の観測 → ground datoms）──")
    (drive actor "i1" {:op :article/ingest :article "art-jp-ai-strategy"
                       :value {:id "art-jp-ai-strategy"
                               :url "https://www.cao.go.jp/ai-strategy-2026"
                               :title "政府、AI 戦略 2026 改訂版を公表"
                               :title-en "Japan publishes the revised AI Strategy 2026"
                               :summary "国産基盤モデルの評価基盤整備と公共調達指針を追加。"
                               :summary-en "Adds an evaluation platform for domestic foundation models and public-procurement guidance."
                               :source-id "src-cao" :source-name "内閣府"
                               :source-type "official" :rights-policy "gov-open"
                               :lang "ja" :published-at "2026-07-02"
                               :priority 83 :credibility 92}} 3 true)
    (line "  ingested articles: " (mapv :id (store/all-articles st)))

    (line "\n── rundown/compose: 権利を知らない advisor → EditorialGovernor が HOLD ──")
    (let [careless (op/build st {:advisor (anchorllm/careless-advisor)})]
      (drive careless "r-bad" {:op :rundown/compose :episode ep :channel ch-id
                               :date "2026-07-02"} 3 true))

    (line "\n── rundown/compose: anchor-LLM(mock) の編成 ──")
    (drive actor "r1" {:op :rundown/compose :episode ep :channel ch-id
                       :date "2026-07-02"} 3 true)
    (doseq [{:keys [segment article-ids]} (:rundown (store/episode st ep))]
      (line "   " segment " ← " (pr-str article-ids)))

    (line "\n── script/draft: 原稿生成（= 生成された AI ニュース）──")
    (drive actor "s1" {:op :script/draft :episode ep} 3 true)
    (doseq [{:keys [segment lines caption]} (:script (store/episode st ep))]
      (line "  [" (name segment) "]")
      (doseq [l lines] (line "    " l))
      (line "    ── " caption))

    (line "\n── video/produce (ja): SlideRenderer（news-card + ナレーション + ffmpeg）──")
    (drive actor "v1" {:op :video/produce :episode ep} 3 true)
    (let [{:keys [video thumbnail]} (store/episode st ep)]
      (line "  video: " (or (:path video) "(ffmpeg 不在 — frames のみ)"))
      (line "  thumbnail: " (:path thumbnail))
      (line "  cid: " (:cid video)))

    (line "\n── episode/publish: 外部公開は常に人間の editorial sign-off ──")
    (drive actor "p1" {:op :episode/publish :episode ep} 3 true)
    (line "  publication: " (pr-str (:publication (store/episode st ep))))

    (line "\n── video/produce (en): 多言語 — 同じ script の :i18n から英語版 ──")
    (drive actor "v-en" {:op :video/produce :episode ep :lang "en"} 3 true)
    (let [e (store/episode st ep)]
      (line "  videos: " (pr-str (into {} (map (fn [[k v]] [k (:path v)])
                                               (:videos e))))))

    (line "\n── 段階導入: rundown/compose を phase 0 (ingest-only) で ──")
    (drive actor "p0" {:op :rundown/compose :episode "ep-shadow" :channel ch-id
                       :date "2026-07-02"} 0 true)

    (line "\n── 放送台帳（append-only; 出典・承認トレーサビリティ）──")
    (doseq [f (store/ledger st)] (line "  " (store/ledger-line f)))

    (line "\n── バックエンド差し替え: DatomicStore でも同一契約 ──")
    (let [ds (store/datomic-seed-db) da (op/build ds)]
      (drive da "d1" {:op :rundown/compose :episode "ep-d" :channel ch-id
                      :date "2026-07-02"} 3 true)
      (line "  DatomicStore rundown segments: "
            (mapv :segment (:rundown (store/episode ds "ep-d")))))

    ;; ローカル確認プレビュー（liquid-glass-ui SSR、ADR-2607021130）
    #?(:clj (line "\npreview: " (preview/write! st ep "out")))
    (line "\ndone.")))
