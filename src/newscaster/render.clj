(ns newscaster.render
  "SlideRenderer — kami-engine の今日 JVM ヘッドレスで動く 2D 経路
  （kami.mangaka.page の caption-box 流儀）を 16:9 放送向けに再構成した実 Renderer。

    render-spec → セグメントごとに 1280×720 の news-card PNG（Java2D、
    lower-thirds = 出典 caption-box）→ ffmpeg concat で mp4。

  外部依存は任意で fail-soft:
    IMAGEGEN_URL — kami.mangaka.render 互換の image-gen サーバ。設定時は背景画像を
                   生成して敷く（失敗したらグラデーションに fallback）。
    ANIMEKA_URL  — 設定時は newscaster.animeka で episode/cut 構造を
                   ai-gftd-animeka にも記録する（記録失敗は render を止めない）。
    ffmpeg       — PATH に無ければ frames のみ返す（:video nil → commit は hold）。

  kami-cine（gftd:kami-cine encode 契約）/ animeka の ComfyUI・TTS adapter が
  実装され次第、この Renderer を差し替えて昇格する（ADR-2607020910）。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [newscaster.animeka :as animeka]
            [newscaster.ports :as ports])
  (:import [java.awt Color Font GradientPaint Graphics2D RenderingHints
            GraphicsEnvironment]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.security MessageDigest]
           [javax.imageio ImageIO]))

;; ───────────────────────── drawing helpers ─────────────────────────

(defn- hex->color [s]
  (try (Color. (Integer/parseInt (subs s 1 3) 16)
               (Integer/parseInt (subs s 3 5) 16)
               (Integer/parseInt (subs s 5 7) 16))
       (catch Exception _ (Color. 56 189 248))))

(def ^:private jp-font-candidates
  ["Hiragino Sans" "Hiragino Kaku Gothic ProN" "Noto Sans CJK JP"
   "Noto Sans JP" "Yu Gothic" "Meiryo"])

(defn- pick-font-family []
  (let [avail (set (.getAvailableFontFamilyNames
                    (GraphicsEnvironment/getLocalGraphicsEnvironment)))]
    (or (some avail jp-font-candidates) "SansSerif")))

(defn- wrap-chars
  "CJK-safe char wrapping（kami.mangaka.page と同じ発想の文字単位折返し）。"
  [^Graphics2D g ^String text max-w]
  (let [fm (.getFontMetrics g)]
    (loop [cs (seq (str text)), cur "", out []]
      (if-let [c (first cs)]
        (let [nxt (str cur c)]
          (if (> (.stringWidth fm nxt) max-w)
            (recur (rest cs) (str c) (conj out cur))
            (recur (rest cs) nxt out)))
        (if (seq cur) (conj out cur) out)))))

(defn- imagegen-backdrop
  "IMAGEGEN_URL（kami.mangaka.render 互換）から背景を生成。失敗は nil（fallback）。"
  [prompt [w h]]
  (when-let [url (System/getenv "IMAGEGEN_URL")]
    (try
      (let [body (str "{\"prompt\":" (pr-str prompt)
                      ",\"width\":" w ",\"height\":" h "}")
            conn (doto (.openConnection (java.net.URL. url))
                   (.setRequestMethod "POST")
                   (.setRequestProperty "Content-Type" "application/json")
                   (.setConnectTimeout 5000)
                   (.setReadTimeout 60000)
                   (.setDoOutput true))]
        (with-open [os (.getOutputStream conn)]
          (.write os (.getBytes ^String body "UTF-8")))
        (with-open [is (.getInputStream conn)]
          (ImageIO/read is)))
      (catch Exception _ nil))))

(defn draw-slide
  "1 セグメント分の 16:9 news-card を描く。"
  [{:keys [w h brand date accent segment-label title lines caption index total
           backdrop-image]}]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_RGB)
        g   (.createGraphics img)
        ac  (hex->color (or accent "#38bdf8"))
        fam (pick-font-family)]
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING
                       RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g RenderingHints/KEY_TEXT_ANTIALIASING
                       RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
    ;; backdrop: imagegen (dimmed) or dark studio gradient
    (if backdrop-image
      (do (.drawImage g backdrop-image 0 0 w h nil)
          (.setColor g (Color. 5 10 25 190))
          (.fillRect g 0 0 w h))
      (do (.setPaint g (GradientPaint. 0 0 (Color. 11 18 32)
                                       0 (float h) (Color. 16 27 51)))
          (.fillRect g 0 0 w h)))
    ;; accent bar + segment label
    (.setColor g ac)
    (.fillRect g 60 90 8 110)
    (.setFont g (Font. fam Font/BOLD 22))
    (.setColor g ac)
    (.drawString g ^String (or segment-label "") 90 110)
    ;; brand + date (top-right)
    (.setFont g (Font. fam Font/BOLD 26))
    (let [fm (.getFontMetrics g)
          bx (- w 60 (.stringWidth fm (str brand)))]
      (.setColor g Color/WHITE)
      (.drawString g (str brand) bx 100)
      (.setFont g (Font. fam Font/PLAIN 18))
      (.setColor g (Color. 148 163 184))
      (let [fm2 (.getFontMetrics g)]
        (.drawString g (str date) (int (- w 60 (.stringWidth fm2 (str date)))) 128)))
    ;; title
    (.setFont g (Font. fam Font/BOLD 44))
    (.setColor g Color/WHITE)
    (let [tls (take 2 (wrap-chars g (or title "") 1120))]
      (doseq [[i l] (map-indexed vector tls)]
        (.drawString g ^String l 90 (+ 210 (* i 58)))))
    ;; body lines
    (.setFont g (Font. fam Font/PLAIN 27))
    (.setColor g (Color. 226 232 240))
    (let [wrapped (mapcat #(wrap-chars g % 1100) (or lines []))]
      (doseq [[i l] (map-indexed vector (take 6 wrapped))]
        (.drawString g ^String l 90 (+ 360 (* i 42)))))
    ;; lower-third caption box（kami.mangaka.page caption-box の放送版）
    (when (seq (str caption))
      (.setColor g (Color. 17 28 46 235))
      (.fillRoundRect g 60 (- h 120) 1000 64 14 14)
      (.setColor g ac)
      (.fillRect g 60 (- h 120) 6 64)
      (.setFont g (Font. fam Font/PLAIN 21))
      (.setColor g (Color. 226 232 240))
      (let [cl (first (wrap-chars g (str caption) 940))]
        (.drawString g ^String cl 84 (- h 80))))
    ;; progress (bottom-right)
    (.setFont g (Font. fam Font/PLAIN 18))
    (.setColor g (Color. 100 116 139))
    (let [p  (str index "/" total)
          fm (.getFontMetrics g)]
      (.drawString g p (int (- w 60 (.stringWidth fm p))) (- h 76)))
    (.dispose g)
    img))

;; ───────────────────────── ffmpeg mux ─────────────────────────

(defn- ffmpeg-bin []
  (or (System/getenv "FFMPEG_BIN")
      (some (fn [dir]
              (let [f (io/file dir "ffmpeg")]
                (when (.canExecute f) (.getPath f))))
            (str/split (or (System/getenv "PATH") "") #":"))))

(defn- run-ffmpeg [ffmpeg args]
  (let [p (-> (ProcessBuilder. ^java.util.List (vec (map str (cons ffmpeg args))))
              (.redirectErrorStream true)
              (.start))]
    (with-open [r (io/reader (.getInputStream p))]
      (doall (line-seq r)))
    (zero? (.waitFor p))))

(defn- mux!
  "slide PNG 列（各 duration 秒）→ mp4。concat demuxer の静止画 duration は
  ffmpeg のバージョンで挙動が揺れるので、セグメント毎に -loop 1 -t で正確に
  エンコードしてから stream copy で連結する。成功時 path、失敗 nil。"
  [^File dir frames fps out-name]
  (when-let [ffmpeg (ffmpeg-bin)]
    (let [segs (vec (map-indexed
                     (fn [i {:keys [path duration-s]}]
                       (let [seg (io/file dir (format "seg-%02d.mp4" i))]
                         (when (run-ffmpeg ffmpeg
                                           ["-y" "-loop" "1" "-framerate" fps
                                            "-i" path "-t" duration-s
                                            "-c:v" "libx264" "-tune" "stillimage"
                                            "-pix_fmt" "yuv420p" (.getPath seg)])
                           seg)))
                     frames))]
      (when (every? some? segs)
        (let [listf (io/file dir "segments.ffconcat")
              outf  (io/file dir out-name)]
          (spit listf (str/join "\n" (cons "ffconcat version 1.0"
                                           (map #(str "file '" (.getAbsolutePath ^File %) "'")
                                                segs))))
          (when (run-ffmpeg ffmpeg ["-y" "-f" "concat" "-safe" "0"
                                    "-i" (.getPath listf) "-c" "copy"
                                    (.getPath outf)])
            (.getPath outf)))))))

(defn- sha256-cid [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        bs (java.nio.file.Files/readAllBytes (.toPath f))]
    (str "sha256-" (apply str (map #(format "%02x" %) (.digest md bs))))))

;; ───────────────────────── Renderer port ─────────────────────────

(defn- seg-label [segment i n]
  (case segment
    :cold-open      "OPENING"
    :top-stories    (str "TOP STORIES " i "/" n)
    :one-more-thing "ONE MORE THING"
    :outro          "CREDITS"
    (str/upper-case (name segment))))

(defn render-episode!
  "render-spec を実行して out-dir/<episode-id>/ に PNG 列 + episode.mp4 を書く。
  → {:video {:cid :path} :thumbnail {:cid :path} :frames [..]}（ffmpeg 不在時は
  :video nil = commit 側が hold する正直な失敗）。"
  [channel episode {:keys [resolution fps accent brand date segments] :as spec}
   out-dir]
  (let [[w h] (or resolution [1280 720])
        dir   (doto (io/file out-dir (str (:id episode))) (.mkdirs))
        nstory (count (filter #(= :top-stories (:segment %)) segments))
        _     (when (System/getenv "ANIMEKA_URL")
                (try (animeka/mirror-episode! (System/getenv "ANIMEKA_URL")
                                              channel episode spec)
                     (catch Exception _ nil)))
        backdrop (imagegen-backdrop
                  (str "dark broadcast news studio, minimal, cinematic, "
                       (name (or (:backdrop spec) :studio-dark)))
                  [w h])
        frames
        (vec (map-indexed
              (fn [i {:keys [segment duration-s lines caption]}]
                (let [story-i (inc (count (filter #(= :top-stories (:segment %))
                                                  (take i segments))))
                      img  (draw-slide {:w w :h h :brand brand :date date
                                        :accent accent
                                        :segment-label (seg-label segment story-i nstory)
                                        :title (first lines)
                                        :lines (rest lines)
                                        :caption caption
                                        :index (inc i) :total (count segments)
                                        :backdrop-image backdrop})
                      f    (io/file dir (format "slide-%02d.png" i))]
                  (ImageIO/write img "png" f)
                  ;; concat demuxer は ffconcat の場所基準で解決するので絶対パス
                  {:path (.getAbsolutePath f) :duration-s (or duration-s 10)}))
              segments))
        video-path (when (seq frames) (mux! dir frames (or fps 30) "episode.mp4"))
        thumb      (first frames)]
    {:video     (when video-path
                  {:cid (sha256-cid (io/file video-path)) :path video-path
                   :type :video})
     :thumbnail (when thumb
                  {:cid (sha256-cid (io/file (:path thumb))) :path (:path thumb)
                   :type :thumbnail})
     :frames    (mapv :path frames)}))

(defrecord SlideRenderer [out-dir]
  ports/Renderer
  (-render [_ channel episode render-spec]
    (render-episode! channel episode render-spec out-dir)))

(defn slide-renderer
  ([] (slide-renderer "out"))
  ([out-dir] (->SlideRenderer out-dir)))
