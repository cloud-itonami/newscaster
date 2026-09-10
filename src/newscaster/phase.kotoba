(ns newscaster.phase
  "Phase 0→3 staged rollout, gating only the PRODUCE ops（編成/原稿/レンダ/公開）.
  Recording ground datoms（A 層記事の観測・チャンネル登録・asset 記録）is always
  on. The phase only decides how much autonomy the production has, and can only
  add caution.

    0 ingest-only   — 観測のみ。編成/生成はまだ出さない（shadow）。
    1 assisted      — produce 可、ただし全て人間承認。
    2 assisted-edit — rundown/script は auto-commit 可。video/publish は人間。
    3 supervised    — video まで auto-commit 可（clean+confident 時）。
                      **episode/publish は外部公開 = 常に人間承認**（never auto）。")

(def record-ops #{:article/ingest :channel/register :asset/record})
(def produce-ops #{:rundown/compose :script/draft :video/produce :episode/publish})

(def phases
  {0 {:label "ingest-only"   :produce #{}         :auto #{}}
   1 {:label "assisted"      :produce produce-ops :auto #{}}
   2 {:label "assisted-edit" :produce produce-ops :auto #{:rundown/compose :script/draft}}
   3 {:label "supervised"    :produce produce-ops :auto #{:rundown/compose :script/draft
                                                          :video/produce}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (newscaster.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase NUMBER
  (`(get phases phase (get phases default-phase))`). This is directly
  reachable by any ordinary caller that simply omits :phase -- not just
  malformed/malicious input -- so it must be the MOST CONSERVATIVE
  phase, never the most permissive: 'can only add caution' (this
  namespace's own docstring) has to hold for a MISSING phase too, not
  only an explicitly-set low one. This was 3 (supervised, the single
  most permissive tier -- video/produce can auto-commit) until a live
  check confirmed a caller who forgets :phase silently got maximum
  autonomy instead of the safe default. Root-caused to the shared
  talent.phase template this family is ported from (also fixed there,
  gftd-talent-actor); 1 (assisted) matches the sibling ports' own choice
  (tsumugu.phase / shiropico.phase default-phase 1) -- produce is
  allowed but every one still needs human approval."
  1)

(defn record-op? [op] (contains? record-ops op))

(defn gate
  "Adjust a produce op's governor disposition for the rollout phase.
  Returns {:disposition kw :reason kw|nil}. :episode/publish is never in :auto,
  so it always escalates（公開は常に人間の editorial sign-off）."
  [phase {:keys [op]} disposition]
  (let [{:keys [produce auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold disposition)         {:disposition :hold :reason nil}
      (not (contains? produce op))  {:disposition :hold :reason :phase-disabled}
      (and (= :commit disposition)
           (not (contains? auto op))) {:disposition :escalate :reason :phase-approval}
      :else                         {:disposition disposition :reason nil})))

(defn verdict->disposition [v]
  (cond (:hard? v) :hold (:escalate? v) :escalate :else :commit))
