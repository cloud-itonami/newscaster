(ns newscaster.render-test
  "SlideRenderer smoke test — Java2D headless で news-card PNG が実際に出ること
  （ffmpeg は環境依存なので、あれば mp4 まで、なければ frames のみを検証）。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [newscaster.render :as render])
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
