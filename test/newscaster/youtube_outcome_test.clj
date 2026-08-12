(ns newscaster.youtube-outcome-test
  "upload! が応答を捨てないこと — 純関数と、ネットワークに出ない経路だけ。

  修正前の upload! は「ファイルが無い」「YouTube が拒否した」「YouTube が受理した
  が応答を読めなかった」の 3 つを nil に潰していた。呼び出し側はそれを区別できず、
  台帳には一律 :publish-failed（= 何も公開されていない）と書かれる。YouTube が
  受理していた場合、それは記録の欠落ではなく **積極的に間違った記録** である。

  この名前空間は修正後にしか存在できない（classify-status は新しい関数）。
  修正前後の比較は publish_ordering_test の側が担う — あちらは既存 API だけを
  使うので、source だけ revert しても compile が通り、振る舞いの差が見える。

  fixture はすべて明らかに合成で、実ネットワークにも実 YouTube にも出ない。"
  (:require [clojure.test :refer [deftest is testing]]
            [newscaster.youtube :as yt]))

(deftest only-4xx-may-be-called-a-determinate-rejection
  (testing "4xx = API がリクエストを受け付けなかった → 動画は存在しない"
    (is (= :rejected (yt/classify-status 400)))
    (is (= :rejected (yt/classify-status 401)))
    (is (= :rejected (yt/classify-status 403)))
    (is (= :rejected (yt/classify-status 413)))
    (is (= :rejected (yt/classify-status 429))))

  (testing "408 はバイトが届いていた可能性が残るので断定しない"
    (is (= :indeterminate (yt/classify-status 408))))

  (testing "5xx は YouTube が動画を作ってから応答に失敗した可能性を否定できない"
    (is (= :indeterminate (yt/classify-status 500)))
    (is (= :indeterminate (yt/classify-status 502)))
    (is (= :indeterminate (yt/classify-status 503)))))

(deftest missing-video-is-the-one-case-we-may-call-not-attempted
  (testing "ファイルが無ければワイヤに一切出ていない — ここだけ断定できる"
    (let [res (yt/upload! "synthetic-token-not-a-real-credential"
                          "/nonexistent/synthetic-fixture.mp4"
                          {:title "synthetic" :description "synthetic"
                           :visibility :unlisted})]
      (is (= :not-attempted (:outcome res)))
      (is (= :video-missing (:reason res)))
      (is (nil? (:video-id res)))
      (is (some? res) "nil は返さない — 呼び出し側が区別できる形で返す"))))
