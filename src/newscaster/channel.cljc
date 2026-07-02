(ns newscaster.channel
  "AI ニュースチャンネルの設計 = data。チャンネルは編成表（segment format）・
  ペルソナ・ビジュアル・YouTube 既定メタ（AI 生成開示を含む）・
  social-roster（app-aozora 上の引用可能アクター allowlist、ADR-2607021400）を
  持つ 1 entity で、actor はこの data を読んで rundown/script/render-spec/
  publish-meta を組み立てる。

  既定チャンネル「GFTD AI News」: 日次の AI ニュースダイジェスト、ja、約 3.5 分。
    cold-open(15s) → top-stories×3(45s each) → fleet-pulse(20s)
      → one-more-thing(30s) → outro(15s)

  fleet-pulse は press（ai-gftd-news A 層）ではなく app-aozora 上の GFTD AI
  アクター群の social post から編成する枠（style :social）。引用可能なのは
  `:social-roster` に登録済みの did のみ（governor :unregistered-actor、
  なりすまし・未検証アカウントの混入を型で排除）。

  visual の lower-thirds は kami-engine の kami.mangaka.text/page の caption-box
  語彙（:kind :caption-box）を 16:9 放送向けに再解釈したもの。")

(def default-channel
  {:id "ch-gftd-ai-news"
   :handle "gftd-ai-news"
   :title "GFTD AI News"
   :description "AI の一次情報だけを、出典つきで毎日 3.5 分に編成する AI ニュースチャンネル"
   :lang "ja"
   :langs ["ja" "en"]                                ; 主言語が先頭（多言語 render）
   :cadence :daily
   :target-duration-s 210
   :persona {:anchor "Yuzu"
             :tone "落ち着いた・正確・必ず出典を読む"
             :disclosure :ai-generated
             ;; 登録済み voice のみ Narrator に渡せる（governor :voice-consent、
             ;; ADR-2607021030）。value は TTS backend の voice id / reference 名。
             :voices {"ja" {:voice "jf_alpha"}
                      "en" {:voice "af_heart"}}}
   :format [{:segment :cold-open      :duration-s 15 :items 1 :style :headline}
            {:segment :top-stories    :duration-s 45 :items 3 :style :story}
            {:segment :fleet-pulse    :duration-s 20 :items 1 :style :social}
            {:segment :one-more-thing :duration-s 30 :items 1 :style :light}
            {:segment :outro          :duration-s 15 :items 0 :style :credits}]
   :visual {:backdrop :studio-dark
            :resolution [1280 720]
            :fps 30
            :accent "#38bdf8"
            :lower-thirds {:kind :caption-box :attribution? true}}
   :youtube {:category-id "28"                       ; Science & Technology
             :tags ["AI" "ニュース" "GFTD AI News"]
             :visibility :unlisted
             :made-for-kids false
             :disclosure :ai-generated}
   ;; app-aozora（yoro AppView）上で fleet-pulse に引用してよいアクターの
   ;; allowlist。did は自組織アクターの did:web（ADR-2607021400）— 実際に
   ;; app-aozora へ post するアクターが登録され次第、実 did に差し替える
   ;; デモ/種データ（現状 SocialFeed は mock 既定、live は follow-up）。
   :social-roster [{:did "did:web:aozora.gftd.ai:actor:robotaxi" :handle "robotaxi.aozora.gftd.ai"}
                   {:did "did:web:aozora.gftd.ai:actor:itonami" :handle "itonami.aozora.gftd.ai"}]})

(defn planned-duration-s
  "編成表の合計尺（秒）。top-stories は items ぶん展開して数える。"
  [{:keys [format]}]
  (reduce + 0 (map (fn [{:keys [duration-s items]}]
                     (if (= 0 items) duration-s (* duration-s (max 1 items))))
                   format)))

(defn story-slots
  "記事を割り当てるスロット列（outro のような items=0 は除く）を
  [{:segment :style :duration-s} ...] に展開する。"
  [{:keys [format]}]
  (vec (mapcat (fn [{:keys [segment style duration-s items]}]
                 (repeat items {:segment segment :style style :duration-s duration-s}))
               format)))

(defn primary-lang [{:keys [lang langs]}]
  (or (first langs) lang "ja"))

(defn validate
  "チャンネル設計の妥当性。violations の vector（空 = OK）。"
  [{:keys [id title lang langs format youtube persona social-roster] :as _ch}]
  (cond-> []
    (not (string? id))    (conj {:rule :missing-id})
    (not (string? title)) (conj {:rule :missing-title})
    (not (string? lang))  (conj {:rule :missing-lang})
    (empty? format)       (conj {:rule :empty-format})
    (not= :ai-generated (:disclosure youtube)) (conj {:rule :missing-disclosure})
    ;; ナレーションする言語には登録 voice が要る（governor :voice-consent の前提）
    (some #(nil? (get-in persona [:voices %])) (or langs [lang]))
    (conj {:rule :missing-voice})
    ;; :social 枠があるのに roster が空 = fleet-pulse が常に空撮（governor
    ;; :unregistered-actor の前提が満たせない）
    (and (some #(= :social (:style %)) format) (empty? social-roster))
    (conj {:rule :empty-social-roster})))
