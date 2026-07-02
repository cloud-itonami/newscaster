(ns newscaster.governor-contract-test
  "The editorial contract as executable tests — newscaster's analog of
  robotaxi's safety_contract_test. Invariant: the actor never airs a proposal
  the EditorialGovernor would reject, never posts to the outside world without
  a human editorial sign-off, and always records observations."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [newscaster.anchorllm :as anchorllm]
            [newscaster.operation :as op]
            [newscaster.ports :as ports]
            [newscaster.store :as store]))

(defn- fresh
  ([] (fresh {}))
  ([opts] (let [s (store/seed-db)] [s (op/build s opts)])))

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context {:phase phase}} {:thread-id tid}))

(defn- to-published
  "rundown → script → video を phase 3 で commit して episode を公開可能状態にする。"
  [actor st ep]
  (run actor (str ep "-r") {:op :rundown/compose :episode ep
                            :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
  (run actor (str ep "-s") {:op :script/draft :episode ep} 3)
  (run actor (str ep "-v") {:op :video/produce :episode ep} 3)
  (store/episode st ep))

(deftest ingest-always-records
  (testing "observe path records a ground datom regardless of phase"
    (let [[s actor] (fresh)
          res (run actor "i" {:op :article/ingest :article "art-x"
                              :value {:id "art-x" :title "t" :url "u"
                                      :source-name "s" :rights-policy "cc-by"
                                      :priority 10}} 0)]
      (is (= :record (get-in res [:state :disposition])))
      (is (= "t" (:title (store/article s "art-x")))))))

(deftest rights-blocked-source-is-held
  (testing "権利を知らない advisor が broadcast 素材を引用 → hold（上書き不可）"
    (let [[s actor] (fresh {:advisor (anchorllm/careless-advisor)})
          res (run actor "rb" {:op :rundown/compose :episode "ep-t"
                               :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
          basis (-> (store/ledger s) last :basis)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:rights-blocked} basis))
      (is (nil? (:rundown (store/episode s "ep-t"))) "nothing recorded on hold"))))

(deftest hallucinated-source-is-held
  (testing "ingest されていない記事の引用（幻覚ニュース）→ hold :uncited-source"
    (let [ghost (reify anchorllm/Advisor
                  (-advise [_ _ _]
                    {:rundown [{:segment :cold-open :duration-s 15
                                :article-ids ["art-ghost"]}]
                     :summary "x" :rationale "x" :cites ["art-ghost"]
                     :effect :proposal :confidence 0.9}))
          [s actor] (fresh {:advisor ghost})
          res (run actor "gh" {:op :rundown/compose :episode "ep-t"
                               :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:uncited-source} (-> (store/ledger s) last :basis))))))

(deftest script-without-rundown-is-held
  (let [[s actor] (fresh)
        res (run actor "sr" {:op :script/draft :episode "ep-none"} 3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:missing-rundown} (-> (store/ledger s) last :basis)))))

(deftest publish-requires-editorial-signoff
  (testing "a clean episode never auto-publishes — it interrupts for a human"
    (let [[s actor] (fresh)
          ep "ep-pub"]
      (to-published actor s ep)
      (is (= :rendered (:status (store/episode s ep))))
      (let [r1 (run actor "pub" {:op :episode/publish :episode ep} 3)]
        (is (= :interrupted (:status r1)) "publish is high-stakes → always human")
        (let [r2 (g/run* actor {:approval {:status :approved :by "editor-7"}}
                         {:thread-id "pub" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (let [e (store/episode s ep)]
            (is (= :published (:status e)))
            (is (= "editor-7" (get-in e [:publication :approved-by])))
            (is (string? (get-in e [:publication :url])))))))))

(deftest publish-without-disclosure-is-held
  (testing "AI 生成開示の無い publish proposal は hold（人間にも届かない）"
    (let [[s actor] (fresh)
          ep "ep-nd"
          _  (to-published actor s ep)
          bad (reify anchorllm/Advisor
                (-advise [_ _ _]
                  {:publish-meta {:title "t" :description "d" :visibility :public}
                   :summary "x" :rationale "x" :cites []
                   :effect :proposal :confidence 0.95}))
          a2 (op/build s {:advisor bad})
          res (run a2 "nd" {:op :episode/publish :episode ep} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:missing-disclosure} (-> (store/ledger s) last :basis)))
      (is (not= :published (:status (store/episode s ep)))))))

(deftest publish-before-render-is-held
  (let [[s actor] (fresh)
        ep "ep-nr"
        _ (run actor "nr-r" {:op :rundown/compose :episode ep
                             :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
        _ (run actor "nr-s" {:op :script/draft :episode ep} 3)
        res (run actor "nr" {:op :episode/publish :episode ep} 3)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:not-rendered} (-> (store/ledger s) last :basis)))))

(deftest no-actuation-invariant
  (testing "a proposal that tries to post directly is held"
    (let [bad (reify anchorllm/Advisor
                (-advise [_ _ _]
                  {:rundown [] :summary "x" :rationale "x" :cites []
                   :effect :external-post :confidence 0.9}))
          [s actor] (fresh {:advisor bad})
          res (run actor "na" {:op :rundown/compose :episode "ep-t"
                               :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-actuation} (-> (store/ledger s) last :basis))))))

(deftest unregistered-voice-is-held
  (testing "channel に登録の無い voice でのナレーション（無断クローン）→ hold"
    (let [[s _] (fresh)
          ep "ep-vc"
          base (op/build s)
          _ (run base "vc-r" {:op :rundown/compose :episode ep
                              :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
          _ (run base "vc-s" {:op :script/draft :episode ep} 3)
          bad (reify anchorllm/Advisor
                (-advise [_ _ _]
                  {:render-spec {:lang "ja"
                                 :narration {:lang "ja" :voice "cloned-someone"}
                                 :segments []}
                   :summary "x" :rationale "x" :cites []
                   :effect :asset :confidence 0.9}))
          a2 (op/build s {:advisor bad})
          res (run a2 "vc" {:op :video/produce :episode ep} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:voice-consent} (-> (store/ledger s) last :basis)))
      (is (nil? (:video (store/episode s ep)))))))

(deftest unsupported-lang-is-held
  (testing "channel :langs に無い言語の render → hold"
    (let [[s actor] (fresh)
          ep "ep-fr"
          _ (run actor "fr-r" {:op :rundown/compose :episode ep
                               :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
          _ (run actor "fr-s" {:op :script/draft :episode ep} 3)
          res (run actor "fr" {:op :video/produce :episode ep :lang "fr"} 3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:unsupported-lang} (-> (store/ledger s) last :basis))))))

(deftest multilingual-videos-accumulate
  (testing ":video/produce :lang \"en\" は :videos に per-lang で積まれる"
    (let [[s actor] (fresh)
          ep "ep-ml"]
      (to-published actor s ep)                      ; ja render 済み
      (run actor "ml-en" {:op :video/produce :episode ep :lang "en"} 3)
      (let [e (store/episode s ep)]
        (is (= #{"ja" "en"} (set (keys (:videos e)))))
        (is (= :rendered (:status e)))))))

(deftest render-failure-holds
  (testing "Renderer が失敗したら asset は記録されず hold"
    (let [[s actor] (fresh {:renderer (ports/failing-renderer)})
          ep "ep-rf"]
      (run actor "rf-r" {:op :rundown/compose :episode ep
                         :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
      (run actor "rf-s" {:op :script/draft :episode ep} 3)
      (let [res (run actor "rf-v" {:op :video/produce :episode ep} 3)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (nil? (:video (store/episode s ep))))
        (is (empty? (store/assets-of s ep)))))))

(deftest reject-signoff-holds
  (testing "an editor rejection records a hold, not a publication"
    (let [[s actor] (fresh)
          ep "ep-rej"]
      (to-published actor s ep)
      (run actor "rej" {:op :episode/publish :episode ep} 3)
      (let [r2 (g/run* actor {:approval {:status :rejected :by "editor-7"}}
                       {:thread-id "rej" :resume? true})]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (not= :published (:status (store/episode s ep))))))))

(deftest phase0-disables-production
  (let [[s actor] (fresh)
        res (run actor "p0" {:op :rundown/compose :episode "ep-t"
                             :channel "ch-gftd-ai-news" :date "2026-07-02"} 0)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) last :phase-reason)))))

(deftest phase3-autocommits-video-but-never-publish
  (testing "phase 3: video は auto-commit、publish は常に人間"
    (let [[s actor] (fresh)
          ep "ep-auto"]
      (to-published actor s ep)
      (is (= :rendered (:status (store/episode s ep))) "video auto-committed")
      (is (= :interrupted
             (:status (run actor "ap" {:op :episode/publish :episode ep} 3)))))))
