(ns newscaster.store
  "SSoT for the newscaster actor, behind a `Store` protocol so the backend is a
  swap (MemStore default ‖ DatomicStore via langchain.db, itself swappable to
  real Datomic Local / kotoba-server).

  Domain = B 層の放送編成。A 層（ai-gftd-news）の article を provenance ごと写像し、
  channel（編成表）/ episode（rundown → script → video → publication）/ asset を持つ。

    article  — A 層記事の写像（art-<sha256(url)> id・url・source・rightsPolicy・score）
    channel  — チャンネル設計（newscaster.channel）
    episode  — 1 回の放送（:status :planned→:rundown→:scripted→:rendered→:published）
    asset    — render 産物（video/thumbnail/frame、cid/path）

  append-only の **ledger は放送台帳** — いつ・どの記事を根拠に・誰が承認して・何を
  公開したかの不変の系譜（出典トレーサビリティ/データ主権の核）。"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [kotoba.lang.text :as str]
            [langchain.db :as d]
            [newscaster.channel :as channel]))

(defprotocol Store
  (article [s id])
  (all-articles [s])
  (channel-of [s id])
  (all-channels [s])
  (episode [s id])
  (episodes-of [s channel-id])
  (assets-of [s episode-id])
  (ledger [s])
  (record-datom! [s record] "append a ground fact to the SSoT")
  (append-ledger! [s fact]  "append one immutable broadcast-ledger fact")
  (seed! [s data]           "bulk-seed entity collections (idempotent upsert)"))

;; ───────────────────────── demo data ─────────────────────────

(defn demo-data
  "AI ニュースの種記事。art-scoop-tv は最高 priority だが rightsPolicy=broadcast
  （権利的に使用不可）— 権利を知らない advisor が引用すると EditorialGovernor が
  hold する、という封じ込めのデモ/テスト用。"
  []
  {:channels {"ch-gftd-ai-news" channel/default-channel}
   :articles
   {"art-openai-o5"
    {:id "art-openai-o5" :url "https://openai.com/blog/o5"
     :title "OpenAI、推論特化の新モデル「o5」を発表"
     :title-en "OpenAI announces o5, a reasoning-focused model"
     :summary "長時間タスクでの自己検証を強化した推論モデル。API は段階提供。"
     :summary-en "A reasoning model with stronger self-verification on long tasks. API access rolls out in stages."
     :source-id "src-openai" :source-name "OpenAI Blog" :source-type "press"
     :rights-policy "fair-use-quote" :lang "ja" :published-at "2026-07-01"
     :priority 92 :credibility 80}
    "art-eu-ai-act"
    {:id "art-eu-ai-act" :url "https://commission.europa.eu/ai-act-gpai"
     :title "EU AI Act、汎用モデル条項の執行が本格開始"
     :title-en "EU AI Act enforcement begins for general-purpose model provisions"
     :summary "GPAI 提供者への透明性義務と系統的リスク評価の適用が始まった。"
     :summary-en "Transparency duties and systemic-risk assessments now apply to GPAI providers."
     :source-id "src-ec" :source-name "European Commission" :source-type "regulator"
     :rights-policy "gov-open" :lang "ja" :published-at "2026-07-01"
     :priority 88 :credibility 90}
    "art-arxiv-ssm"
    {:id "art-arxiv-ssm" :url "https://arxiv.org/abs/2606.99999"
     :title "状態空間モデルが超長文脈推論で新 SOTA"
     :title-en "State-space models set a new SOTA on ultra-long-context reasoning"
     :summary "1000 万トークン文脈での検索・推論ベンチで Transformer 系を上回る報告。"
     :summary-en "Reported to beat Transformer baselines on 10-million-token retrieval and reasoning benchmarks."
     :source-id "src-arxiv" :source-name "arXiv" :source-type "dataset"
     :rights-policy "cc-by" :lang "ja" :published-at "2026-06-30"
     :priority 76 :credibility 75}
    "art-metr-eval"
    {:id "art-metr-eval" :url "https://metr.org/autonomy-eval-2026"
     :title "METR、フロンティアモデル自律性評価の新基準を公開"
     :title-en "METR publishes a new standard for frontier-model autonomy evals"
     :summary "長期タスク遂行能力を測る公開評価スイート。各社モデルの結果も掲載。"
     :summary-en "An open evaluation suite for long-horizon task capability, with results across frontier models."
     :source-id "src-metr" :source-name "METR" :source-type "official"
     :rights-policy "public-domain" :lang "ja" :published-at "2026-06-29"
     :priority 71 :credibility 85}
    "art-scoop-tv"
    {:id "art-scoop-tv" :url "https://tv.example/ai-ma-scoop"
     :title "【TV 素材】大手 AI 企業の大型買収観測"
     :title-en "[TV footage] Rumored mega-acquisition of a major AI company"
     :summary "テレビ報道の録画素材。権利上そのまま再利用できない。"
     :summary-en "Recorded broadcast footage; cannot be reused as-is for rights reasons."
     :source-id "src-tv" :source-name "TV network" :source-type "liveAudio"
     :rights-policy "broadcast" :lang "ja" :published-at "2026-07-02"
     :priority 95 :credibility 55}}
   :episodes {}
   :assets {}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (article [_ id] (get-in @a [:articles id]))
  (all-articles [_] (sort-by :id (vals (:articles @a))))
  (channel-of [_ id] (get-in @a [:channels id]))
  (all-channels [_] (sort-by :id (vals (:channels @a))))
  (episode [_ id] (get-in @a [:episodes id]))
  (episodes-of [_ channel-id]
    (sort-by :id (filter #(= channel-id (:channel %)) (vals (:episodes @a)))))
  (assets-of [_ episode-id] (get-in @a [:assets episode-id] []))
  (ledger [_] (:ledger @a))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :article (swap! a update-in [:articles id] merge value)
      :channel (swap! a update-in [:channels id] merge value)
      :episode (swap! a update-in [:episodes id] merge value)
      :asset   (swap! a update-in [:assets id] (fnil conj []) value)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (seed! [s data]
    (swap! a merge (select-keys data [:channels :articles :episodes :assets])) s))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────────

(def ^:private schema
  {:article/id      {:db/unique :db.unique/identity}
   :channel/id      {:db/unique :db.unique/identity}
   :episode/id      {:db/unique :db.unique/identity}
   :ledger/seq      {:db/unique :db.unique/identity}
   :asset/episode   {:db/valueType :db.type/ref}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

;; The store talks to its backend ONLY through the langchain.db `:db-api` map
;; {:q :transact! :db :pull :entid}, so the same record runs on the in-process
;; EAVT backend or a kotoba-server pod by construction.

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- pull* [{:keys [api conn]} pattern eid] ((:pull api) ((:db api) conn) pattern eid))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defn- edn-entity [this id-attr edn-attr id]
  (when-let [m (pull* this [id-attr edn-attr] [id-attr id])]
    (when (get m id-attr) (dec* (get m edn-attr)))))

(defrecord DatomicStore [api conn]
  Store
  (article [this id] (edn-entity this :article/id :article/edn id))
  (all-articles [this]
    (->> (q* this '[:find [?id ...] :where [?e :article/id ?id]])
         (map #(article this %)) (sort-by :id)))
  (channel-of [this id] (edn-entity this :channel/id :channel/edn id))
  (all-channels [this]
    (->> (q* this '[:find [?id ...] :where [?e :channel/id ?id]])
         (map #(channel-of this %)) (sort-by :id)))
  (episode [this id] (edn-entity this :episode/id :episode/edn id))
  (episodes-of [this channel-id]
    (->> (q* this '[:find [?id ...] :where [?e :episode/id ?id]])
         (map #(episode this %)) (filter #(= channel-id (:channel %))) (sort-by :id)))
  (assets-of [this episode-id]
    (->> (q* this '[:find [?v ...] :in $ ?eid :where
                    [?e :episode/id ?eid] [?r :asset/episode ?e] [?r :asset/edn ?v]]
             episode-id)
         (mapv dec*)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (record-datom! [s {:keys [kind id value]}]
    (case kind
      :article (tx* s [{:article/id id :article/edn (enc (merge (article s id) value))}])
      :channel (tx* s [{:channel/id id :channel/edn (enc (merge (channel-of s id) value))}])
      :episode (tx* s [{:episode/id id :episode/edn (enc (merge (episode s id) value))}])
      :asset   (tx* s [{:asset/episode [:episode/id id] :asset/edn (enc value)}])
      nil)
    s)
  (append-ledger! [s fact]
    (tx* s [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}]) fact)
  (seed! [s data]
    (doseq [[id v] (:articles data)] (record-datom! s {:kind :article :id id :value v}))
    (doseq [[id v] (:channels data)] (record-datom! s {:kind :channel :id id :value v}))
    (doseq [[id v] (:episodes data)] (record-datom! s {:kind :episode :id id :value v}))
    s))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline). For a kotoba-server pod, swap the :db-api (langchain.kotoba-db)."
  ([] (datomic-store nil))
  ([data] (let [s (->DatomicStore d/api (d/create-conn schema))]
            (when data (seed! s data)) s)))

(defn datomic-seed-db [] (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line [{:keys [t op episode disposition basis]}]
  (str/join " · " [(name (or disposition :record)) (str "t=" (some-> t name))
                   (str "op=" op) (str "episode=" episode)
                   (str "basis=" (pr-str basis))]))
