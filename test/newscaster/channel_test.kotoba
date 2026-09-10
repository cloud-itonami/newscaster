(ns newscaster.channel-test
  (:require [clojure.test :refer [deftest is testing]]
            [newscaster.channel :as channel]))

(deftest default-channel-is-valid
  (is (empty? (channel/validate channel/default-channel))))

(deftest planned-duration-matches-target
  (testing "編成表の合計尺 = target（15 + 45×3 + 20 + 30 + 15 = 215s ≈ 3.5 分強、
  20s は fleet-pulse = app-aozora actor social post 枠、ADR-2607021400）"
    (is (= 215 (channel/planned-duration-s channel/default-channel)))
    (is (<= (:target-duration-s channel/default-channel)
            (channel/planned-duration-s channel/default-channel)))))

(deftest story-slots-expand-items
  (let [slots (channel/story-slots channel/default-channel)]
    (is (= 6 (count slots))
        "1 cold-open + 3 top-stories + 1 fleet-pulse + 1 one-more-thing")
    (is (= 3 (count (filter #(= :top-stories (:segment %)) slots))))
    (is (= 1 (count (filter #(= :fleet-pulse (:segment %)) slots))))))

(deftest validate-catches-missing-disclosure
  (is (some #(= :missing-disclosure (:rule %))
            (channel/validate (assoc-in channel/default-channel
                                        [:youtube :disclosure] nil)))))

(deftest validate-catches-empty-social-roster
  (testing ":social 枠があるのに social-roster が空 = fleet-pulse が常に空撮"
    (is (some #(= :empty-social-roster (:rule %))
              (channel/validate (assoc channel/default-channel :social-roster []))))))
