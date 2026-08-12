(ns newscaster.publish-ordering-test
  "公開（取り消せない外部効果）の順序と、その記録が本当のことを言っているか。

  ADR-2608124600 が名指しした形: 効果を先に出して記録を後にすると、その間に
  落ちた実行が **誰にも名指しできない動画** を残し、次の実行がもう 1 本作る。
  newscaster の場合そこに 2 つ目の欠陥が重なっていた — upload! が非 2xx で nil
  を返すので、YouTube が受理していても台帳には :publish-failed（= 何も公開されて
  いない）と書かれる。**記録が欠けているのではなく、間違っている。**

  ここでの負荷試験は retry。upload と record の間で落ちた後、次の実行が 2 本目の
  動画を作らないこと。fixture はすべて合成で、実ネットワークには一切出ない。"
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [newscaster.operation :as op]
            [newscaster.ports :as ports]
            [newscaster.store :as store]))

;; ── テスト専用の計器 ──
;; 意図的に src ではなくここに置く。src に置くと「source だけ revert して
;; テストは残す」という修正前後の比較ができなくなる（計器ごと消えて
;; コンパイルエラーになり、振る舞いの差ではなく不在を見ることになる）。

(defn- counting-publisher
  "呼ばれた回数を数える。二重公開は状態を見ても分からない — 回数でしか見えない。"
  [counter inner]
  (reify ports/Publisher
    (-publish [_ ch episode meta]
      (swap! counter inc)
      (ports/-publish inner ch episode meta))))

(defn- rejecting-publisher
  "確定的に拒否する（4xx 相当 — 動画は作られていない）。"
  []
  (reify ports/Publisher
    (-publish [_ _ch _episode _meta] {:outcome :rejected :status 403})))

(defn- indeterminate-publisher
  "公開されたか分からない（5xx・切断・id 無しの 2xx 相当）。
  「失敗」ではないので :publish-failed と記録してはならない。"
  [reason]
  (reify ports/Publisher
    (-publish [_ _ch _episode _meta] {:outcome :indeterminate :reason reason})))

(defn- fresh
  ([] (fresh {}))
  ([opts] (let [s (store/seed-db)] [s (op/build s opts)])))

(defn- run [actor tid req phase]
  (g/run* actor {:request req :context {:phase phase}} {:thread-id tid}))

(defn- to-rendered
  "publish 可能なところまで episode を進める。"
  [actor ep]
  (run actor (str ep "-r") {:op :rundown/compose :episode ep
                            :channel "ch-gftd-ai-news" :date "2026-07-02"} 3)
  (run actor (str ep "-s") {:op :script/draft :episode ep} 3)
  (run actor (str ep "-v") {:op :video/produce :episode ep} 3))

(defn- publish!
  "publish は常に人間承認で割り込む。承認して再開するところまでを 1 手に。"
  [actor ep tid]
  (run actor tid {:op :episode/publish :episode ep} 3)
  (g/run* actor {:approval {:status :approved :by "editor-7"}}
          {:thread-id tid :resume? true}))

(defn- ledger-kinds [s] (mapv :t (store/ledger s)))

;; ─────────────────── 正の対照（修正の前後どちらでも通る）───────────────────

(deftest control-clean-publish-still-records-published
  (testing "正の対照: 素直に成功する公開は、これまでどおり :published を残す"
    (let [[s actor] (fresh)
          ep "ep-ok"]
      (to-rendered actor ep)
      (let [r (publish! actor ep "ok")]
        (is (= :commit (get-in r [:state :disposition])))
        (let [e (store/episode s ep)]
          (is (= :published (:status e)))
          (is (= "yt-ep-ok" (get-in e [:publication :video-id])))
          (is (= "editor-7" (get-in e [:publication :approved-by]))))
        (is (some #{:published} (ledger-kinds s)))))))

(deftest control-publish-still-requires-human-signoff
  (testing "正の対照: 公開は依然として必ず人間の承認で割り込む（no-actuation）"
    (let [[s actor] (fresh)
          ep "ep-signoff"
          calls (atom 0)]
      (to-rendered actor ep)
      ;; publisher を数えておく — 承認前に外へ出ていないことを状態でなく回数で見る
      (let [[s2 _] [s nil]
            actor2 (op/build s2 {:publisher (counting-publisher
                                             calls (ports/mock-publisher))})
            r1 (run actor2 "so" {:op :episode/publish :episode ep} 3)]
        (is (= :interrupted (:status r1)))
        (is (zero? @calls) "承認前に publisher が呼ばれてはならない")))))

;; ─────────── 修正前に落ち、修正後に通る（本題）───────────

(deftest indeterminate-upload-is-not-recorded-as-failed
  (testing "YouTube が受理したかどうか分からない結果を :publish-failed と書かない"
    (let [s (store/seed-db)
          actor (op/build s {:publisher (indeterminate-publisher :transport)})
          ep "ep-ind"]
      (to-rendered actor ep)
      (publish! actor ep "ind")
      (let [kinds (ledger-kinds s)]
        (is (some #{:publish-indeterminate} kinds)
            "「わからない」は :publish-indeterminate として記録される")
        (is (not-any? #{:publish-failed} kinds)
            "「何も公開されていない」と断定する事実を書いてはならない"))
      (is (not= :published (:status (store/episode s ep)))))))

(deftest nil-response-is-not-recorded-as-failed
  (testing "旧 upload! が非 2xx で返していたのはまさに nil — それを「失敗」と書かない"
    ;; これが production の欠陥そのものの形。YouTube が 500 を返した（が動画は
    ;; 作られたかもしれない）とき、旧 upload! は nil を返し、台帳には
    ;; :publish-failed = 「何も公開されていない」と書かれていた。
    (let [s (store/seed-db)
          nil-publisher (reify ports/Publisher
                          (-publish [_ _ch _episode _meta] nil))
          actor (op/build s {:publisher nil-publisher})
          ep "ep-nil"]
      (to-rendered actor ep)
      (publish! actor ep "nil")
      (let [kinds (ledger-kinds s)]
        (is (not-any? #{:publish-failed} kinds)
            "nil は「わからない」であって「失敗した」ではない")
        (is (some #{:publish-indeterminate} kinds)))
      (is (not= :published (:status (store/episode s ep)))))))

(deftest determinate-rejection-is-still-recorded-as-failed
  (testing "4xx（確定的に何も作られていない）は今までどおり :publish-failed"
    (let [s (store/seed-db)
          actor (op/build s {:publisher (rejecting-publisher)})
          ep "ep-rej"]
      (to-rendered actor ep)
      (publish! actor ep "rej")
      (is (some #{:publish-failed} (ledger-kinds s)))
      (is (not= :published (:status (store/episode s ep)))))))

(deftest retry-after-crash-between-upload-and-record-does-not-republish
  (testing "**負荷試験** upload と record の間で落ちた後、retry は 2 本目を作らない"
    (let [s     (store/seed-db)
          calls (atom 0)
          actor (op/build s {:publisher (counting-publisher
                                         calls (ports/mock-publisher))})
          ep    "ep-crash"]
      (to-rendered actor ep)

      ;; 1 回目 — 効果は出たが、記録の直前にプロセスが落ちた状況を再現する。
      ;; 「意図の印は durable に置かれた／結果は書かれていない」という、
      ;; まさにその窓の状態を作る。
      (publish! actor ep "crash-1")
      (is (= 1 @calls))
      (store/record-datom! s {:kind :episode :id ep
                              :value {:publication nil :status :rendered}})
      (store/record-datom! s {:kind :episode :id ep
                              :value {:publish-attempt {:op :episode/publish
                                                        :by "editor-7"}}})
      (is (nil? (:publication (store/episode s ep))) "落ちた直後は結果が無い")

      ;; 2 回目 — 素直な retry。ここで publisher が再び呼ばれたら動画が 2 本になる。
      (publish! actor ep "crash-2")
      (is (= 1 @calls)
          "retry は 2 本目の動画を作ってはならない（publisher の呼び出しは 1 回のまま）")
      (is (some #{:publish-attempt-unresolved} (ledger-kinds s))
          "未確定の前回試行として保留され、人間が確定させる"))))

(deftest retry-after-successful-publish-does-not-republish
  (testing "公開済みの episode を再度 publish しても 2 本目を作らない"
    (let [s     (store/seed-db)
          calls (atom 0)
          actor (op/build s {:publisher (counting-publisher
                                         calls (ports/mock-publisher))})
          ep    "ep-twice"]
      (to-rendered actor ep)
      (publish! actor ep "twice-1")
      (is (= 1 @calls))
      (is (= :published (:status (store/episode s ep))))

      (publish! actor ep "twice-2")
      (is (= 1 @calls) "すでに公開済み — publisher は二度と呼ばれない")
      (is (some #{:publish-noop} (ledger-kinds s))))))

(deftest intent-is-recorded-before-the-effect
  (testing "効果より先に意図が durable に置かれている（順序そのもの）"
    (let [s   (store/seed-db)
          seen (atom nil)
          ep  "ep-order"
          ;; publisher が呼ばれた瞬間の store を覗く。効果の時点で印が
          ;; 既に在るなら、途中で落ちても残骸を名指しできる。
          probe (reify ports/Publisher
                  (-publish [_ _ch episode _meta]
                    (reset! seen (:publish-attempt (store/episode s (:id episode))))
                    {:video-id "yt-ep-order"
                     :url "https://youtube.example/watch?v=yt-ep-order"}))
          actor (op/build s {:publisher probe})]
      (to-rendered actor ep)
      (publish! actor ep "order")
      (is (some? @seen) "publisher が呼ばれた時点で試行の印が durable に在る")
      (is (= "editor-7" (:by @seen)))
      (is (nil? (:publish-attempt (store/episode s ep)))
          "成功したら印は畳まれる"))))
