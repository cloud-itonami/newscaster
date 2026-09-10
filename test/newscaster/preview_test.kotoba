(ns newscaster.preview-test
  "preview は liquid-glass-ui components の SSR（ADR-2607021130）。"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [newscaster.preview :as preview]
            [newscaster.store :as store])
  (:import [java.nio.file Files]))

(defn- tmp-dir []
  (str (Files/createTempDirectory
        "newscaster-preview"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- seeded []
  (doto (store/seed-db)
    (store/record-datom!
     {:kind :episode :id "ep-t"
      :value {:id "ep-t" :channel "ch-gftd-ai-news" :date "2026-07-02"
              :rundown [{:segment :cold-open :article-ids ["art-openai-o5"]}]
              :script [{:segment :cold-open :article-ids ["art-openai-o5"]
                        :lines ["こんばんは。"] :caption "出典: OpenAI Blog"
                        :i18n {"en" {:lines ["Good evening."]}}}]
              :videos {"ja" {:path "x/ja/episode.mp4" :cid "sha256-j"}
                       "en" {:path "x/en/episode.mp4" :cid "sha256-e"}}}})
    (store/append-ledger!
     {:t :composed :op :rundown/compose :episode "ep-t"
      :disposition :commit :basis ["art-openai-o5"]})))

(deftest preview-is-liquid-glass-ssr
  (testing "SSR HTML が liquid-glass material + 構造化コンテンツを含む"
    (let [st   (seeded)
          path (preview/write! st "ep-t" (tmp-dir))
          html (slurp path)]
      (is (.exists (io/file path)))
      (is (re-find #"liquid-glass__panel" html) "glass panel components")
      (is (re-find #"--liquid-glass-surface-thick-tint" html) "material tokens CSS")
      (is (re-find #"ep-t/ja/episode.mp4" html) "per-lang video")
      (is (re-find #"ep-t/en/episode.mp4" html))
      (is (re-find #"こんばんは。" html) "script content")
      (is (re-find #"Good evening\." html) ":i18n content")
      (is (re-find #"放送台帳" html) "ledger section"))))
