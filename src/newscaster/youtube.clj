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

(defn upload!
  "承認済み episode の video を YouTube にアップロード。→ {:video-id :url} | nil。"
  [token video-path publish-meta]
  (let [video (io/file video-path)]
    (when (.exists video)
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
            res  (.send client req (HttpResponse$BodyHandlers/ofString))]
        (when (<= 200 (.statusCode res) 299)
          (let [id (get (json/read-str (.body res)) "id")]
            {:video-id id :url (str "https://www.youtube.com/watch?v=" id)}))))))

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
