(ns newscaster.render-test
  "SlideRenderer smoke test — Java2D headless で news-card PNG が実際に出ること
  （ffmpeg は環境依存なので、あれば mp4 まで、なければ frames のみを検証）。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [newscaster.channel :as channel]
            [newscaster.ports :as ports]
            [newscaster.render :as render]
            [newscaster.tts :as tts])
  (:import [java.nio.file Files]))

(defn- tmp-dir []
  (str (Files/createTempDirectory "newscaster-render" (make-array
                                                       java.nio.file.attribute.FileAttribute 0))))

(deftest slide-renderer-produces-frames
  (testing "render-spec → PNG frames（+ ffmpeg があれば mp4）"
    (let [out (tmp-dir)
          res (render/render-episode!
               {:title "GFTD AI News"}
               {:id "ep-test" :channel "ch-gftd-ai-news"}
               {:resolution [1280 720] :fps 30 :accent "#38bdf8"
                :brand "GFTD AI News" :date "2026-07-02"
                :segments [{:segment :cold-open :duration-s 1
                            :lines ["こんばんは。" "トップは「テスト」。"]
                            :caption "出典: テスト — https://example.com"}
                           {:segment :outro :duration-s 1
                            :lines ["以上、GFTD AI News でした。"]
                            :caption "AI が生成した番組です"}]}
               out)]
      (is (= 2 (count (:frames res))))
      (is (every? #(.exists (io/file %)) (:frames res)))
      (is (some? (:thumbnail res)))
      (when (:video res)
        (is (.exists (io/file (:path (:video res)))))
        (is (re-find #"^sha256-" (:cid (:video res))))))))

(deftest narrated-duration-drives-segment-length
  (testing "音声実尺 + 0.5s（下限 3s）が編成表の固定尺を上書きする"
    (let [nd #'render/narrated-duration]
      (is (= 5.5 (nd {:duration-s 5.0} 45)) "音声 5s → 5.5s（45s 枠でも）")
      (is (= 3.0 (nd {:duration-s 1.0} 45)) "極短音声は下限 3s")
      (is (= 45  (nd nil 45)) "音声なしは編成表の尺のまま"))))

(deftest fixed-narrator-flows-into-render
  (testing "Narrator の尺が render の frame duration に届く（無音・実ファイル無し）"
    (let [out (tmp-dir)
          res (render/render-episode!
               channel/default-channel
               {:id "ep-nar" :channel "ch-gftd-ai-news"}
               {:resolution [640 360] :fps 30 :accent "#38bdf8"
                :brand "GFTD AI News" :date "2026-07-02" :lang "ja"
                :narration {:lang "ja" :voice "jf_alpha"}
                :segments [{:segment :cold-open :duration-s 45
                            :lines ["テスト行"] :caption "出典: t"}]}
               out (ports/fixed-narrator 4.0))]
      (is (= 1 (count (:frames res))))
      (is (re-find #"/ja/" (first (:frames res))) "per-lang サブディレクトリ"))))

(deftest wav-duration-from-riff-header
  (testing "RIFF ヘッダから実尺（44.1kHz 16bit mono の 1 秒）"
    (let [sr 44100 n sr bytes-per (* 2 1)
          data-size (* n bytes-per)
          bb (java.nio.ByteBuffer/allocate (+ 44 data-size))]
      (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
      (.put bb (.getBytes "RIFF")) (.putInt bb (+ 36 data-size))
      (.put bb (.getBytes "WAVE"))
      (.put bb (.getBytes "fmt ")) (.putInt bb 16)
      (.putShort bb 1) (.putShort bb 1) (.putInt bb sr)
      (.putInt bb (* sr bytes-per)) (.putShort bb bytes-per) (.putShort bb 16)
      (.put bb (.getBytes "data")) (.putInt bb data-size)
      (is (= 1.0 (tts/wav-duration-s (.array bb)))))))
