(ns newscaster.render
  "SlideRenderer — kami-engine の今日 JVM ヘッドレスで動く 2D 経路
  （kami.mangaka.page の caption-box 流儀）を 16:9 放送向けに再構成した実 Renderer。

    render-spec → セグメントごとに 1280×720 の news-card PNG（Java2D、
    lower-thirds = 出典 caption-box）→ ffmpeg concat で mp4。

  **UI/UX は kotoba-lang の liquid-glass-ui がベース（ADR-2607021130）**:
  lower-thirds / segment chip / progress badge は `liquid-glass.tokens` の
  dark-scheme material（surface tint/border・radius・specular）を同一ソースに、
  CSS backdrop-filter 相当を Java2D で近似（縮小拡大 blur + tint fill +
  rim border + specular gradient）して描く。

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
            [liquid-glass.tokens :as lgt]
            [newscaster.animeka :as animeka]
            [newscaster.ports :as ports])
  (:import [java.awt Color Font GradientPaint Graphics2D Image RenderingHints
            GraphicsEnvironment BasicStroke]
           [java.awt.geom RoundRectangle2D$Float]
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

;; ───────────── liquid-glass material（tokens が単一ソース）─────────────

(defn- parse-rgba
  "\"rgba(16,16,20,0.58)\" → java.awt.Color（alpha 0–255）。"
  [s]
  (let [[r g b a] (map read-string (re-seq #"[\d.]+" (str s)))]
    (Color. (int r) (int g) (int b) (int (Math/round (* 255 (double (or a 1))))))))

(defn- parse-px [s] (int (or (first (map read-string (re-seq #"\d+" (str s)))) 16)))

(def ^:private glass
  "dark-scheme の liquid-glass material を Java2D 値に解決（ADR-2607021130）。
  light 既定に dark override を重ねる = liquid-glass.style の
  prefers-color-scheme: dark と同じ合成。"
  (let [t (lgt/deep-merge lgt/default-tokens (lgt/resolve-dark-tokens nil))]
    {:surface   (into {} (for [[k v] (:liquid-glass/surface t)]
                           [k {:blur   (parse-px (:blur v))
                               :tint   (parse-rgba (:tint v))
                               :border (parse-rgba (:border v))}]))
     :radius    (into {} (for [[k v] (:liquid-glass/radius t)]
                           [k (parse-px v)]))
     :highlight (read-string (get-in t [:liquid-glass/specular :highlight :opacity]))
     :rim-top   (read-string (get-in t [:liquid-glass/specular :rim :top-opacity]))}))

(defn- glass-panel!
  "liquid-glass panel を Java2D で近似描画: base（描画済み背景）の領域を縮小拡大で
  blur → tint fill → specular highlight（上面 gradient）→ border + top rim。"
  [^Graphics2D g ^BufferedImage base x y w h {:keys [surface radius]
                                              :or   {surface :thick radius :md}}]
  (let [{:keys [blur tint border]} (get-in glass [:surface surface])
        ;; :pill(999px) は CSS では自動クランプされるが Java2D の幾何は
        ;; 破綻する（矩形の短辺/2 に丸める）
        r    (min (get-in glass [:radius radius] 16)
                  (int (/ (min w h) 2)))
        clip (RoundRectangle2D$Float. x y w h (* 2 r) (* 2 r))
        old  (.getClip g)
        k    (max 2 (int (/ blur 6)))          ; 縮小率 ≈ blur 強度
        sub  (.getSubimage base
                           (max 0 (int x)) (max 0 (int y))
                           (min (int w) (- (.getWidth base) (int x)))
                           (min (int h) (- (.getHeight base) (int y))))
        ;; 同一 raster への read/write feedback を避けるため必ずコピーしてから縮小
        copy (let [b (BufferedImage. (.getWidth sub) (.getHeight sub)
                                     BufferedImage/TYPE_INT_RGB)]
               (doto (.createGraphics b) (.drawImage sub 0 0 nil) (.dispose)) b)
        down (.getScaledInstance copy (max 1 (int (/ w k))) (max 1 (int (/ h k)))
                                 Image/SCALE_SMOOTH)]
    (.setClip g clip)
    ;; backdrop-filter: blur() 近似
    (.drawImage g down (int x) (int y) (int w) (int h) nil)
    ;; tint（surface の色）
    (.setColor g tint)
    (.fill g clip)
    ;; specular highlight（上面 40% に白 gradient）
    (.setPaint g (GradientPaint.
                  0 (float y)
                  (Color. 255 255 255 (int (* 255 0.16 (:highlight glass))))
                  0 (float (+ y (* h 0.45))) (Color. 255 255 255 0)))
    (.fill g clip)
    (.setClip g old)
    ;; border + top rim
    (.setStroke g (BasicStroke. 1.0))
    (.setColor g border)
    (.draw g (RoundRectangle2D$Float. x y (dec w) (dec h) (* 2 r) (* 2 r)))
    (.setColor g (Color. 255 255 255 (int (* 255 0.5 (:rim-top glass)))))
    (when (< (+ x r) (- (+ x w) r))
      (.drawLine g (int (+ x r)) (int (inc y)) (int (- (+ x w) r)) (int (inc y))))))

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
    ;; accent bar + segment chip（liquid-glass pill、ADR-2607021130）
    (.setColor g ac)
    (.fillRect g 60 84 8 116)
    (.setFont g (Font. fam Font/BOLD 22))
    (let [label (or segment-label "")
          lw    (.stringWidth (.getFontMetrics g) label)]
      (when (seq label)
        (glass-panel! g img 84 82 (+ lw 44) 40 {:surface :regular :radius :pill})
        (.setFont g (Font. fam Font/BOLD 22))
        (.setColor g ac)
        (.drawString g ^String label 106 110)))
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
    ;; lower-third caption = liquid-glass thick panel（caption-box の glass 版）
    (when (seq (str caption))
      (glass-panel! g img 60 (- h 124) 1000 68 {:surface :thick :radius :md})
      (.setColor g ac)
      (.fillRoundRect g 60 (- h 124) 6 68 6 6)
      (.setFont g (Font. fam Font/PLAIN 21))
      (.setColor g (Color. 226 232 240))
      (let [cl (first (wrap-chars g (str caption) 940))]
        (.drawString g ^String cl 86 (- h 82))))
    ;; progress badge（glass pill、bottom-right）
    (.setFont g (Font. fam Font/PLAIN 17))
    (let [p  (str index "/" total)
          fm (.getFontMetrics g)
          pw (+ (.stringWidth fm p) 30)
          px (- w 60 pw)
          py (- h 122)]
      (glass-panel! g img px py pw 32 {:surface :clear :radius :pill})
      (.setFont g (Font. fam Font/PLAIN 17))
      (.setColor g (Color. 203 213 225))
      (.drawString g p (int (+ px 15)) (int (+ py 22))))
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
  "slide PNG 列（各 duration 秒、任意で per-seg ナレーション WAV）→ mp4。
  concat demuxer の静止画 duration は ffmpeg のバージョンで挙動が揺れるので、
  セグメント毎に -loop 1 -t で正確にエンコードしてから stream copy で連結する。
  音声は aac 48kHz stereo に正規化（無音セグメントは anullsrc）し、apad で尺まで
  埋める — 全セグメントの codec が揃うので -c copy concat が成立する。
  成功時 path、失敗 nil。"
  [^File dir frames fps out-name]
  (when-let [ffmpeg (ffmpeg-bin)]
    (let [segs (vec (map-indexed
                     (fn [i {:keys [path duration-s audio-path]}]
                       (let [seg (io/file dir (format "seg-%02d.mp4" i))
                             audio-in (if audio-path
                                        ["-i" audio-path]
                                        ["-f" "lavfi" "-i"
                                         "anullsrc=r=48000:cl=stereo"])]
                         (when (run-ffmpeg ffmpeg
                                           (concat
                                            ["-y" "-loop" "1" "-framerate" fps
                                             "-i" path]
                                            audio-in
                                            ["-t" duration-s
                                             "-af" "apad"
                                             "-c:v" "libx264" "-tune" "stillimage"
                                             "-pix_fmt" "yuv420p"
                                             "-c:a" "aac" "-ar" "48000" "-ac" "2"
                                             (.getPath seg)]))
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

(def ^:private min-segment-s 3.0)

(defn- narrated-duration
  "音声実尺が尺を決める（+0.5s 余白、下限 3s）。音声なしは編成表の尺のまま。"
  [audio duration-s]
  (if-let [d (:duration-s audio)]
    (max min-segment-s (+ (double d) 0.5))
    (or duration-s 10)))

(defn render-episode!
  "render-spec を実行して out-dir/<episode-id>/[<lang>/] に PNG 列 + ナレーション
  WAV（narrator があれば）+ episode.mp4 を書く。
  → {:video {:cid :path} :thumbnail {:cid :path} :frames [..] :narration {..}}
  （ffmpeg 不在時は :video nil = commit 側が hold する正直な失敗）。"
  [channel episode {:keys [resolution fps accent brand date lang segments] :as spec}
   out-dir & [narrator]]
  (let [[w h] (or resolution [1280 720])
        dir   (doto (if lang
                      (io/file out-dir (str (:id episode)) lang)
                      (io/file out-dir (str (:id episode))))
                (.mkdirs))
        nstory (count (filter #(= :top-stories (:segment %)) segments))
        nlang  (get-in spec [:narration :lang] lang)
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
              (fn [i {:keys [segment duration-s lines caption] :as seg}]
                (let [audio (when narrator
                              (ports/-narrate narrator channel seg nlang))
                      story-i (inc (count (filter #(= :top-stories (:segment %))
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
                  {:path (.getAbsolutePath f)
                   :duration-s (narrated-duration audio duration-s)
                   :audio-path (:path audio)
                   :audio-cid  (:cid audio)}))
              segments))
        video-path (when (seq frames) (mux! dir frames (or fps 30) "episode.mp4"))
        thumb      (first frames)]
    {:video     (when video-path
                  {:cid (sha256-cid (io/file video-path)) :path video-path
                   :type :video :lang nlang})
     :thumbnail (when thumb
                  {:cid (sha256-cid (io/file (:path thumb))) :path (:path thumb)
                   :type :thumbnail})
     :narration (when (some :audio-cid frames)
                  {:lang nlang
                   :voice (get-in spec [:narration :voice])
                   :cids (vec (keep :audio-cid frames))})
     :frames    (mapv :path frames)}))

(defrecord SlideRenderer [out-dir narrator]
  ports/Renderer
  (-render [_ channel episode render-spec]
    (render-episode! channel episode render-spec out-dir narrator)))

(defn slide-renderer
  ([] (slide-renderer "out"))
  ([out-dir] (->SlideRenderer out-dir nil))
  ([out-dir narrator] (->SlideRenderer out-dir narrator)))
