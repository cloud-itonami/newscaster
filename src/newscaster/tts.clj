(ns newscaster.tts
  "HttpNarrator — open-weight TTS gateway driver（ADR-2607021030）。

  契約: `POST $TTS_URL/tts {\"text\",\"lang\",\"voice\"}` → WAV bytes。
  サーバは `scripts/tts_server.py`（BACKEND=kokoro|tada）:
    kokoro — 82M / Apache-2.0 / ja 含む多言語。CPU で実時間超（ローカル確認・CI 用）
    tada   — HumeAI/tada-3b-ml（Hume 純正 open weights、ja 含む多言語）= hume quality

  murakumo の IMAGEGEN_URL / GFTD_LLM_URL と同じ gateway 流儀なので、backend は
  契約の後ろで自由に差し替えられる。音声は WAV の RIFF ヘッダから実尺を読み、
  sha256 cid つきで返す（放送台帳の provenance）。"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [kotoba.lang.text :as str]
            [newscaster.ports :as ports])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(defn- sha256 [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (str "sha256-" (apply str (map #(format "%02x" %) (.digest md bs))))))

(defn wav-duration-s
  "RIFF/WAVE ヘッダを歩いて data チャンク実尺（秒）を返す。不明は nil。"
  [^bytes bs]
  (when (and (> (alength bs) 44)
             (= "RIFF" (String. bs 0 4)) (= "WAVE" (String. bs 8 4)))
    (let [bb (doto (ByteBuffer/wrap bs) (.order ByteOrder/LITTLE_ENDIAN))]
      (loop [pos 12, byte-rate nil]
        (when (<= (+ pos 8) (alength bs))
          (let [cid  (String. bs pos 4)
                size (.getInt bb (+ pos 4))]
            (cond
              (= cid "fmt ")
              (recur (+ pos 8 size (mod size 2)) (.getInt bb (+ pos 16)))
              (and (= cid "data") byte-rate (pos? byte-rate))
              (double (/ size byte-rate))
              :else
              (recur (+ pos 8 size (mod size 2)) byte-rate))))))))

(defn synthesize!
  "TTS gateway に text を投げ、WAV を out-dir に書く。
  → {:path :duration-s :cid :voice} | nil（失敗）。"
  [url out-dir {:keys [text lang voice]}]
  (try
    (let [req (-> (HttpRequest/newBuilder)
                  (.uri (URI/create (str url "/tts")))
                  (.header "Content-Type" "application/json")
                  (.timeout (Duration/ofMinutes 10))
                  (.POST (HttpRequest$BodyPublishers/ofString
                          (json/write-str {:text text :lang lang :voice voice})))
                  (.build))
          res (.send @client req (HttpResponse$BodyHandlers/ofByteArray))]
      (when (<= 200 (.statusCode res) 299)
        (let [bs  (.body res)
              cid (sha256 bs)
              f   (io/file out-dir (str lang "-" (subs cid 7 15) ".wav"))]
          (io/make-parents f)
          (with-open [os (io/output-stream f)] (.write os ^bytes bs))
          {:path (.getAbsolutePath f)
           :duration-s (wav-duration-s bs)
           :cid cid
           :voice voice})))
    (catch Exception _ nil)))

(defrecord HttpNarrator [url out-dir]
  ports/Narrator
  (-narrate [_ channel segment lang]
    (let [voice (get-in channel [:persona :voices lang :voice])
          text  (str/join " " (:lines segment))]
      (when (seq text)
        (synthesize! url out-dir {:text text :lang lang :voice voice})))))

(defn http-narrator
  "TTS_URL（または明示 url）から Narrator を作る。url 不在は nil
  （呼び出し側は無音のまま動かす）。"
  ([] (http-narrator {:out-dir "out/audio"}))
  ([{:keys [url out-dir]}]
   (when-let [u (or url (System/getenv "TTS_URL"))]
     (->HttpNarrator u (or out-dir "out/audio")))))
