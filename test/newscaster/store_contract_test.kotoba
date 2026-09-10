(ns newscaster.store-contract-test
  "MemStore ≡ DatomicStore — the swap is a contract, not a hope."
  (:require [clojure.test :refer [deftest is testing]]
            [newscaster.store :as store]))

(defn- exercise [s]
  (store/record-datom! s {:kind :article :id "art-n"
                          :value {:id "art-n" :title "新記事" :url "u"
                                  :source-name "src" :rights-policy "cc-by"
                                  :priority 50}})
  (store/record-datom! s {:kind :episode :id "ep-1"
                          :value {:id "ep-1" :channel "ch-gftd-ai-news"
                                  :date "2026-07-02"
                                  :rundown [{:segment :cold-open
                                             :article-ids ["art-n"]}]
                                  :status :rundown}})
  (store/record-datom! s {:kind :episode :id "ep-1"
                          :value {:script [{:segment :cold-open :lines ["l"]
                                            :article-ids ["art-n"]}]
                                  :status :scripted}})
  (store/record-datom! s {:kind :asset :id "ep-1"
                          :value {:cid "cid-1" :type :video}})
  (store/append-ledger! s {:t :composed :op :rundown/compose :episode "ep-1"
                           :disposition :commit :basis ["art-n"]})
  {:article   (store/article s "art-n")
   :n-arts    (count (store/all-articles s))
   :episode   (dissoc (store/episode s "ep-1") nil)
   :by-chan   (mapv :id (store/episodes-of s "ch-gftd-ai-news"))
   :assets    (store/assets-of s "ep-1")
   :ledger    (store/ledger s)})

(deftest mem-and-datomic-agree
  (testing "same operations, same observable state"
    (let [m (exercise (store/seed-db))
          d (exercise (store/datomic-seed-db))]
      (is (= (:article m) (:article d)))
      (is (= (:n-arts m) (:n-arts d)))
      (is (= (:episode m) (:episode d)) "episode merge semantics agree")
      (is (= (:by-chan m) (:by-chan d)))
      (is (= (:assets m) (:assets d)))
      (is (= (:ledger m) (:ledger d))))))

(deftest episode-merge-preserves-earlier-keys
  (let [s (store/datomic-seed-db)]
    (store/record-datom! s {:kind :episode :id "ep-m"
                            :value {:id "ep-m" :channel "c" :rundown [:r]}})
    (store/record-datom! s {:kind :episode :id "ep-m" :value {:script [:s]}})
    (let [e (store/episode s "ep-m")]
      (is (= [:r] (:rundown e)) "rundown survives the script merge")
      (is (= [:s] (:script e))))))
