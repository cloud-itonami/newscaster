(ns newscaster.operation
  "BroadcastActor — one newscaster operation = one supervised actor run, a
  langgraph-clj StateGraph. Two flows share one auditable graph:

    ingest (record-op):  intake → record → END
        A 層記事の写像 / チャンネル登録 / asset 記録 become durable ground
        datoms. Always on, never an LLM call, never an actuation.

    produce (produce-op): intake → advise → govern → decide → commit|hold|approval
        the anchor-LLM (sealed) proposes rundown/script/render-spec/publish-meta;
        the EditorialGovernor enforces editorial invariants（source-traceability /
        rights-gate / disclosure / no-actuation）; the phase gate adds caution;
        an :episode/publish ALWAYS routes to a human editor
        (interrupt-before :request-approval).

  Single invariant (the newscaster analog of robotaxi's safety contract):
    the actor never airs a proposal the EditorialGovernor would reject, and
    never posts to the outside world without a human editorial sign-off."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [newscaster.anchorllm :as anchorllm]
            [newscaster.governor :as gov]
            [newscaster.phase :as phase]
            [newscaster.ports :as ports]
            [newscaster.store :as store]))

(defn- request->record
  "Map an ingest request to a store ground-datom record."
  [{:keys [op article channel episode value]}]
  (case op
    :article/ingest   {:kind :article :id article :value value}
    :channel/register {:kind :channel :id channel :value value}
    :asset/record     {:kind :asset   :id episode :value value}))

(defn- subject [{:keys [article channel episode]}] (or episode article channel))

(defn- commit-record
  "Build the :record payload committed for a clean produce proposal."
  [{:keys [op episode channel date]} proposal]
  (case op
    :rundown/compose {:kind :episode :id episode
                      :value {:id episode :channel channel :date date
                              :rundown (:rundown proposal) :status :rundown}}
    :script/draft    {:kind :episode :id episode
                      :value {:script (:script proposal) :status :scripted}}
    :video/produce   {:kind :render :id episode
                      :value {:render-spec (:render-spec proposal)}}
    :episode/publish {:kind :publication :id episode
                      :value {:publish-meta (:publish-meta proposal)}}))

(defn build
  "Compiles a BroadcastActor bound to `store` (any newscaster.store/Store).
  opts: :advisor (default mock), :renderer (default mock), :publisher
  (default mock), :checkpointer (default in-mem)."
  [store & [{:keys [advisor renderer publisher checkpointer]
             :or   {advisor      (anchorllm/mock-advisor)
                    renderer     (ports/mock-renderer)
                    publisher    (ports/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; :phase + (future) authn
         :proposal    {:default nil}
         :verdict     {:default nil}
         :disposition {:default nil}
         :record      {:default nil}
         :approval    {:default nil}
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; ── ingest path: record a ground datom (observe), no LLM/governor ──
      (g/add-node :record
        (fn [{:keys [request]}]
          (let [rec (request->record request)
                f   {:t :recorded :op (:op request) :episode (subject request)
                     :disposition :record :basis (:kind rec)}]
            (store/record-datom! store rec)
            (store/append-ledger! store f)
            {:disposition :record :audit [f]})))

      ;; ── produce path ──
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (anchorllm/-advise advisor store request)]
            {:proposal p :audit [(anchorllm/trace request p)]})))

      (g/add-node :govern
        (fn [{:keys [request proposal]}]
          {:verdict (gov/check request proposal store)}))

      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (let [base (phase/verdict->disposition verdict)
                ph   (:phase context phase/default-phase)
                {:keys [disposition reason]} (phase/gate ph request base)]
            (case disposition
              :hold
              {:disposition :hold
               :audit [(cond-> (gov/hold-fact request verdict)
                         reason (assoc :phase-reason reason :phase ph))]}
              :escalate
              {:disposition :escalate
               :audit [{:t :approval-requested :op (:op request)
                        :episode (subject request)
                        :reason (or reason (if (:high-stakes? verdict)
                                             :editorial-signoff :low-confidence))
                        :summary (:summary proposal)
                        :phase ph :confidence (:confidence verdict)}]}
              :commit
              {:disposition :commit
               :record (commit-record request proposal)}))))

      (g/add-node :request-approval
        (fn [{:keys [request proposal approval verdict]}]
          (if (= :approved (:status approval))
            {:disposition :commit
             :record (assoc-in (commit-record request proposal)
                               [:value :approved-by] (:by approval))
             :audit [{:t :editorial-signoff :op (:op request)
                      :episode (subject request) :by (:by approval)
                      :summary (:summary proposal)}]}
            {:disposition :hold
             :audit [(merge (gov/hold-fact request
                                           (assoc verdict :violations
                                                  [{:rule :editor-rejected}]))
                            {:t :signoff-rejected})]})))

      ;; commit — episode datom, or execute the Renderer/Publisher port.
      (g/add-node :commit
        (fn [{:keys [request record]}]
          (let [eid (:id record)]
            (case (:kind record)
              :episode
              (do (store/record-datom! store record)
                  (let [f {:t (if (:rundown (:value record)) :composed :scripted)
                           :op (:op request) :episode eid :disposition :commit
                           :basis (vec (distinct (mapcat :article-ids
                                                         (or (:rundown (:value record))
                                                             (:script (:value record))))))}]
                    (store/append-ledger! store f)
                    {:audit [f]}))

              :render
              (let [ep  (store/episode store eid)
                    ch  (store/channel-of store (:channel ep))
                    res (ports/-render renderer ch ep
                                       (get-in record [:value :render-spec]))]
                (if (:video res)
                  (do (store/record-datom! store {:kind :asset :id eid
                                                  :value (:video res)})
                      (when (:thumbnail res)
                        (store/record-datom! store {:kind :asset :id eid
                                                    :value (:thumbnail res)}))
                      (store/record-datom! store {:kind :episode :id eid
                                                  :value {:video (:video res)
                                                          :thumbnail (:thumbnail res)
                                                          :status :rendered}})
                      (let [f {:t :rendered :op (:op request) :episode eid
                               :disposition :commit
                               :basis (:cid (:video res))}]
                        (store/append-ledger! store f)
                        {:audit [f]}))
                  (let [f {:t :render-failed :op (:op request) :episode eid
                           :disposition :hold :basis [:renderer-error]}]
                    (store/append-ledger! store f)
                    {:disposition :hold :audit [f]})))

              :publication
              (let [ep   (store/episode store eid)
                    ch   (store/channel-of store (:channel ep))
                    meta (get-in record [:value :publish-meta])
                    res  (ports/-publish publisher ch ep meta)]
                (if res
                  (do (store/record-datom! store
                        {:kind :episode :id eid
                         :value {:publication (assoc res :approved-by
                                                     (get-in record [:value :approved-by]))
                                 :status :published}})
                      (let [f {:t :published :op (:op request) :episode eid
                               :disposition :commit :basis (:url res)}]
                        (store/append-ledger! store f)
                        {:audit [f]}))
                  (let [f {:t :publish-failed :op (:op request) :episode eid
                           :disposition :hold :basis [:publisher-error]}]
                    (store/append-ledger! store f)
                    {:disposition :hold :audit [f]})))))))

      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(#{:editorial-hold :signoff-rejected} (:t %))
                                      audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      ;; intake routes ingest vs produce.
      (g/add-conditional-edges :intake
        (fn [{:keys [request]}]
          (if (phase/record-op? (:op request)) :record :advise)))
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit, :escalate :request-approval, :hold)))
      (g/add-conditional-edges :request-approval
        (fn [{:keys [disposition]}] (if (= :commit disposition) :commit :hold)))

      (g/set-finish-point :record)
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph
       {:checkpointer checkpointer :interrupt-before #{:request-approval}})))
