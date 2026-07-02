(ns newscaster.anchorllm-test
  (:require [clojure.test :refer [deftest is testing]]
            [newscaster.anchorllm :as anchorllm]
            [newscaster.governor :as gov]
            [newscaster.store :as store]))

(defn- advise [advisor st req] (anchorllm/-advise advisor st req))

(deftest mock-rundown-is-rights-aware
  (testing "標準 mock は governor の rights-gate を鏡映（broadcast 素材を引用しない）"
    (let [st (store/seed-db)
          p  (advise (anchorllm/mock-advisor) st
                     {:op :rundown/compose :episode "ep" :channel "ch-gftd-ai-news"})]
      (is (= :proposal (:effect p)))
      (is (seq (:cites p)))
      (is (not-any? #{"art-scoop-tv"} (:cites p)))
      (is (every? #(gov/publish-allowed?
                    (:rights-policy (store/article st %))) (:cites p))))))

(deftest careless-rundown-cites-blocked
  (testing "careless advisor は priority だけで選ぶ（governor が止める側）"
    (let [st (store/seed-db)
          p  (advise (anchorllm/careless-advisor) st
                     {:op :rundown/compose :episode "ep" :channel "ch-gftd-ai-news"})]
      (is (some #{"art-scoop-tv"} (:cites p))))))

(deftest script-cites-subset-of-rundown
  (let [st (store/seed-db)
        _  (store/record-datom! st {:kind :episode :id "ep"
                                    :value {:id "ep" :channel "ch-gftd-ai-news"
                                            :date "2026-07-02"
                                            :rundown [{:segment :cold-open
                                                       :style :headline :duration-s 15
                                                       :article-ids ["art-openai-o5"]}
                                                      {:segment :outro :style :credits
                                                       :duration-s 15 :article-ids []}]}})
        p  (advise (anchorllm/mock-advisor) st {:op :script/draft :episode "ep"})]
    (is (= #{"art-openai-o5"} (set (:cites p))))
    (is (every? (comp seq :lines) (:script p)) "every segment has lines")
    (is (some #(re-find #"AI が生成" (apply str (:lines %))) (:script p))
        "on-air disclosure is part of the script")))

(deftest publish-meta-includes-disclosure-and-sources
  (let [st (store/seed-db)
        _  (store/record-datom! st {:kind :episode :id "ep"
                                    :value {:id "ep" :channel "ch-gftd-ai-news"
                                            :date "2026-07-02"
                                            :script [{:segment :top-stories
                                                      :lines ["x"]
                                                      :article-ids ["art-eu-ai-act"]}]
                                            :video {:cid "cid-v"}}})
        p  (advise (anchorllm/mock-advisor) st {:op :episode/publish :episode "ep"})]
    (is (= :ai-generated (get-in p [:publish-meta :disclosure])))
    (is (re-find #"European Commission" (get-in p [:publish-meta :description])))
    (is (false? (get-in p [:publish-meta :made-for-kids])))))

(deftest parse-proposal-is-defensive
  (testing "garbage LLM output degrades to a confidence-0 noop"
    (let [p (#'anchorllm/parse-proposal "***not edn***")]
      (is (= :noop (:effect p)))
      (is (= 0.0 (:confidence p))))))
