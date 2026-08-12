(ns newscaster.youtube
  "YouTubePublisher — YouTube Data API v3 multipart upload。**publish op の人間
  承認後にのみ** BroadcastActor の :commit ノードから呼ばれる（EditorialGovernor
  の no-actuation 不変条件）。

  認証は環境変数 YT_ACCESS_TOKEN（OAuth2 bearer、scope:
  https://www.googleapis.com/auth/youtube.upload）。秘密情報はコミットしない。
  disclosure :ai-generated は status.containsSyntheticMedia=true として申告する。"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [newscaster.ports :as ports])
  (:import [java.io ByteArrayOutputStream File]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private upload-url
  "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=multipart&part=snippet,status")

(defn- metadata-json [{:keys [title description tags category-id visibility
                              made-for-kids disclosure]}]
  (json/write-str
   {:snippet {:title       title
              :description description
              :tags        tags
              :categoryId  category-id}
    :status  {:privacyStatus          (name (or visibility :unlisted))
              :selfDeclaredMadeForKids (boolean made-for-kids)
              :containsSyntheticMedia  (= :ai-generated disclosure)}}))

(defn- multipart-body ^bytes [^String meta-json ^File video]
  (let [boundary "newscaster-boundary"
        out (ByteArrayOutputStream.)
        w   (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (w (str "--" boundary "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"))
    (w meta-json)
    (w (str "\r\n--" boundary "\r\nContent-Type: video/mp4\r\n\r\n"))
    (io/copy video out)
    (w (str "\r\n--" boundary "--\r\n"))
    (.toByteArray out)))

(defn classify-status
  "HTTP ステータス → :rejected（確定的に何も作られていない）| :indeterminate
  （作られたかもしれない）。

  4xx は API がリクエストを受け付けなかった＝動画は存在しない、と断定してよい
  唯一の帯。ただし 408（server がボディを読み切れなかった）は例外で、
  バイトが届いていた可能性が残るので indeterminate に倒す。5xx とそれ以外は
  すべて indeterminate — YouTube が動画を作ってから応答に失敗した可能性を
  否定できない。"
  [status]
  (if (and (<= 400 status 499) (not= 408 status)) :rejected :indeterminate))

(defn upload!
  "承認済み episode の video を YouTube にアップロード。

  → 成功: {:video-id .. :url ..}
  → それ以外: {:outcome :not-attempted | :rejected | :indeterminate ..}

  **nil は返さない。** 以前は「ファイルが無い」「YouTube が拒否した」「YouTube が
  受理したが応答を読めなかった」の 3 つを nil に潰していたので、呼び出し側は
  区別できないまま :publish-failed（＝何も公開されていない）という、根拠の無い
  事実を台帳に書いていた。応答を捨てるのをやめれば、記録は自然に正しくなる。

  indeterminate は「失敗」ではなく「わからない」であって、確定するまで人間が
  チャンネルを見るしかない — その区別こそが記録の値打ちなので潰さない。"
  [token video-path publish-meta]
  (let [video (io/file video-path)]
    (if-not (.exists video)
      ;; ワイヤに一切出ていない。ここだけは「何も公開されていない」と断定できる。
      {:outcome :not-attempted :reason :video-missing}
      (let [body (multipart-body (metadata-json publish-meta) video)
            req  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create upload-url))
                     (.header "Authorization" (str "Bearer " token))
                     (.header "Content-Type"
                              "multipart/related; boundary=newscaster-boundary")
                     (.timeout (Duration/ofMinutes 10))
                     (.POST (HttpRequest$BodyPublishers/ofByteArray body))
                     (.build))
            client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds 10)) (.build))
            res    (try (.send client req (HttpResponse$BodyHandlers/ofString))
                        (catch Exception e
                          ;; バイトを送り終えた後の切断と、繋がる前の失敗は
                          ;; ここでは区別できない。断定しない。
                          {::transport (.getMessage e)}))]
        (cond
          (::transport res)
          {:outcome :indeterminate :reason :transport
           :detail (subs (str (::transport res)) 0 (min 300 (count (str (::transport res)))))}

          (<= 200 (.statusCode res) 299)
          (let [id (get (json/read-str (.body res)) "id")]
            (if (string? id)
              {:video-id id :url (str "https://www.youtube.com/watch?v=" id)}
              ;; 2xx なのに id が無い = YouTube は受理したが動画に名前が付かない。
              ;; これがまさに「名指しできない残骸」で、失敗ではない。
              {:outcome :indeterminate :reason :no-id :status (.statusCode res)}))

          :else
          {:outcome (classify-status (.statusCode res))
           :status  (.statusCode res)})))))

(defrecord YouTubePublisher [token]
  ports/Publisher
  (-publish [_ _channel episode publish-meta]
    (upload! token (get-in episode [:video :path]) publish-meta)))

(defn publisher
  "YT_ACCESS_TOKEN から Publisher を作る。token 不在は nil（呼び出し側は mock の
  まま動かす）。"
  []
  (when-let [t (System/getenv "YT_ACCESS_TOKEN")]
    (->YouTubePublisher t)))
