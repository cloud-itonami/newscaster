(ns newscaster.animeka
  "ai-gftd-animeka XRPC driver — episode の cut 構造を animeka 側にも記録する
  （work → episode → scene → cut、セグメント = cut）。animeka の generation 層は
  現状スタブ（構造記録は完動）なので、ここでは構造のミラーだけを行い、実ピクセルは
  newscaster.render が作る。animeka の ComfyUI/TTS/ffmpeg adapter が結線され次第、
  cutRunner 経由の実レンダに昇格する（ADR-2607020910）。

  animeka の POST /xrpc/<nsid> は EDN body を受けるので JSON encoder 不要・
  レスポンスの JSON は data.json で読む。"
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(defn xrpc!
  "POST /xrpc/<nsid> with an EDN body → parsed JSON map (keyword keys)."
  [base-url nsid body]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base-url "/xrpc/" nsid)))
                (.header "Content-Type" "application/edn")
                (.timeout (Duration/ofSeconds 30))
                (.POST (HttpRequest$BodyPublishers/ofString (pr-str body)))
                (.build))
        res (.send @client req (HttpResponse$BodyHandlers/ofString))]
    (when (<= 200 (.statusCode res) 299)
      (json/read-str (.body res) :key-fn keyword))))

(defn- result-id [m]
  (or (:result_id m) (:result_uri m) (:result_convo_id m) (:result_scene_id m)
      (:id m) (:uri m)))

(defn mirror-episode!
  "channel/episode/render-spec を animeka の work/episode/scene/cut として記録。
  → {:work :episode :scene :cuts [..]}（失敗はそのまま throw — 呼び出し側で
  fail-soft にする）。"
  [base-url channel episode {:keys [fps segments]}]
  (let [work  (result-id (xrpc! base-url "ai.gftd.apps.animeka.createWork"
                                {:title (str (:title channel) " " (:date episode))}))
        ep    (result-id (xrpc! base-url "ai.gftd.apps.animeka.addEpisode"
                                {:work_id work
                                 :title_jp (str (:title channel) " — " (:date episode))
                                 :episode_num 1 :fps (or fps 30)}))
        scene (result-id (xrpc! base-url "ai.gftd.apps.animeka.addScene"
                                {:episode_id ep :location "AI news studio"}))
        cuts  (vec (for [{:keys [segment duration-s lines]} segments]
                     (let [cut (result-id
                                (xrpc! base-url "ai.gftd.apps.animeka.addCut"
                                       {:scene_id scene
                                        :duration_frames (* (or duration-s 10)
                                                            (or fps 30))
                                        :fps (or fps 30)
                                        :camera_mode "fixed"
                                        :camera_note (str (name segment) ": "
                                                          (first lines))}))]
                       (xrpc! base-url "ai.gftd.apps.animeka.cutRunner"
                              {:cut_id cut :prompt (first lines)})
                       cut)))]
    {:work work :episode ep :scene scene :cuts cuts}))
