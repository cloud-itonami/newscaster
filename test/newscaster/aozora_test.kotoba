(ns newscaster.aozora-test
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is testing]]
            [newscaster.aozora :as aozora]
            [newscaster.governor :as gov]))

(def ^:private sample-post
  {:uri "at://did:web:aozora.gftd.ai:actor:robotaxi/app.bsky.feed.post/3xk9"
   :cid "bafyabc123"
   :author {:did "did:web:aozora.gftd.ai:actor:robotaxi"
            :handle "robotaxi.aozora.gftd.ai"
            :display-name "robotaxi"}
   :text "本日のサービスエリアで自動運転実証、走行距離1200km突破。MRC 発動 0 件。"
   :created-at "2026-07-02T09:00:00Z"
   :lang "ja"
   :like-count 10 :repost-count 3 :reply-count 2})

(deftest post->article-maps-provenance
  (testing "social post は article-shaped ground datom に写像され、rights-policy は常に actor-original"
    (let [a (aozora/post->article sample-post)]
      (is (= "social" (:source-type a)))
      (is (= "actor-original" (:rights-policy a)))
      (is (gov/publish-allowed? (:rights-policy a)))
      (is (= "did:web:aozora.gftd.ai:actor:robotaxi" (:actor-did a)))
      (is (= "robotaxi.aozora.gftd.ai" (:handle a)))
      (is (= (:uri sample-post) (:url a)))
      (is (= (:text sample-post) (:summary a)))
      (is (= "post-robotaxi-3xk9" (:id a)) "id = post-<did suffix>-<at-uri rkey>"))))

(deftest post->article-truncates-title
  (let [long-text (apply str (repeat 80 "あ"))
        a (aozora/post->article (assoc sample-post :text long-text))]
    (is (<= (count (:title a)) 41))
    (is (str/starts-with? (:title a) "あ"))
    (is (= long-text (:summary a)) "summary は全文を保持")))

(deftest score-post-is-deterministic-and-bounded
  (is (= {:priority 73 :credibility 80}
         (aozora/score-post {:like-count 10 :repost-count 3 :reply-count 2}))
      "55 底 + like(10) + repost×2(6) + reply(2) = 73")
  (is (= 100 (:priority (aozora/score-post {:like-count 9999})))
      "priority は 100 に飽和する"))
