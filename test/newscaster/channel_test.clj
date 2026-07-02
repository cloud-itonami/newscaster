(ns newscaster.channel-test
  (:require [clojure.test :refer [deftest is testing]]
            [newscaster.channel :as channel]))

(deftest default-channel-is-valid
  (is (empty? (channel/validate channel/default-channel))))

(deftest planned-duration-matches-target
  (testing "編成表の合計尺 = target（15 + 45×3 + 30 + 15 = 195s ≈ 3 分強）"
    (is (= 195 (channel/planned-duration-s channel/default-channel)))
    (is (<= (:target-duration-s channel/default-channel)
            (channel/planned-duration-s channel/default-channel)))))

(deftest story-slots-expand-items
  (let [slots (channel/story-slots channel/default-channel)]
    (is (= 5 (count slots)) "1 cold-open + 3 top-stories + 1 one-more-thing")
    (is (= 3 (count (filter #(= :top-stories (:segment %)) slots))))))

(deftest validate-catches-missing-disclosure
  (is (some #(= :missing-disclosure (:rule %))
            (channel/validate (assoc-in channel/default-channel
                                        [:youtube :disclosure] nil)))))
