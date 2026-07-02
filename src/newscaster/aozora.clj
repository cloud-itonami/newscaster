(ns newscaster.aozora
  "app-aozora bridge (ADR-2607021400) — maps posts from GFTD's own AI actors
  on the app-aozora social network (yoro AppView, `com.etzhayyim.yoro.feed.*`,
  see orgs/gftdcojp/app-aozora/00-contracts) into the same article-shaped
  ground datom the A 層（ai-gftd-news）ingests, so rundown/script/governor
  logic (newscaster.governor/anchorllm) is fully shared across press and
  social provenance — no schema migration, no new op.

  Trust model: only posts from actors on the channel's `:social-roster`
  allowlist are ever fetched here, AND the EditorialGovernor independently
  re-checks every cited article's `:actor-did` against that same roster
  (`:unregistered-actor`) — so a compromised/spoofed aozora account, or a
  bug in this mapping layer, can't be laundered into the newscast (the
  containment pattern this workspace's actors always use: the fetch/mapping
  layer is not trusted to enforce the invariant by itself).

  `rights-policy` is always \"actor-original\" — a GFTD actor's own social
  post is first-party content the org already controls, so there is no
  copyright question the way there is for A 層 press articles; the trust
  question is authenticity (roster), not licensing."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [newscaster.ports :as ports])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

;; ───────────────────────── pure mapping (post → article) ─────────────────────────

(defn- at-uri->rkey [uri] (last (str/split (or uri "") #"/")))
(defn- did-suffix [did] (last (str/split (or did "") #":")))

(defn score-post
  "engagement からの決定的スコア。priority は 55 を底に like/repost/reply で
  加点（上限 100）。credibility は first-party 固定値。"
  [{:keys [like-count repost-count reply-count]}]
  {:priority (min 100 (+ 55 (or like-count 0) (* 2 (or repost-count 0))
                        (or reply-count 0)))
   :credibility 80})

(defn post->article
  "post {:uri :cid :author{:did :handle :display-name} :text :created-at
  :lang :like-count :repost-count :reply-count} → article-shaped ground
  datom（newscaster.store の :article と同じ shape。:source-type \"social\"）。"
  [{:keys [uri cid author text created-at lang] :as post}]
  (let [{:keys [did handle display-name]} author
        title (if (> (count (or text "")) 40) (str (subs text 0 40) "…") text)
        {:keys [priority credibility]} (score-post post)]
    {:id (str "post-" (did-suffix did) "-" (at-uri->rkey uri))
     :url uri
     :cid cid
     :title title
     :summary text
     :source-id did
     :source-name (or display-name handle)
     :source-type "social"
     :actor-did did
     :post-uri uri
     :handle handle
     :rights-policy "actor-original"
     :lang (or lang "ja")
     :published-at created-at
     :priority priority
     :credibility credibility}))

;; ───────────────────────── live yoro AppView XRPC client ─────────────────────────

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(defn- get! [base-url nsid params]
  (let [qs  (str/join "&" (for [[k v] params :when v]
                            (str (name k) "=" (URLEncoder/encode (str v) "UTF-8"))))
        uri (str base-url "/xrpc/" nsid (when (seq qs) (str "?" qs)))
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create uri))
                (.timeout (Duration/ofSeconds 15))
                (.GET)
                (.build))
        res (.send @client req (HttpResponse$BodyHandlers/ofString))]
    (when (<= 200 (.statusCode res) 299)
      (json/read-str (.body res) :key-fn keyword))))

(defn- post-view->article
  "yoro postView（uri/cid/author/record/indexedAt + counts）→ article。"
  [{:keys [uri cid author record indexedAt likeCount repostCount replyCount]}]
  (post->article
   {:uri uri :cid cid
    :author {:did (:did author) :handle (:handle author)
             :display-name (:displayName author)}
    :text (:text record) :created-at (or (:createdAt record) indexedAt)
    :lang (first (:langs record))
    :like-count likeCount :repost-count repostCount :reply-count replyCount}))

(defn fetch-author-feed
  "com.etzhayyim.yoro.feed.getAuthorFeed → [article ..]（新しい順）。"
  [base-url did & [{:keys [limit] :or {limit 20}}]]
  (->> (get! base-url "com.etzhayyim.yoro.feed.getAuthorFeed" {:actor did :limit limit})
       :feed (keep :post) (map post-view->article) vec))

(defn http-social-feed
  "SocialFeed port — channel の :social-roster に登録されたアクター各々の
  getAuthorFeed を集約する（-fetch-posts opts の :roster が
  [{:did ..} ..]）。base-url は明示 or $AOZORA_APPVIEW_URL。url 不在は nil
  （呼び出し側は mock のまま動かす — newscaster.tts/http-narrator と同じ
  fail-soft の流儀）。"
  ([] (http-social-feed nil))
  ([base-url]
   (when-let [u (or base-url (System/getenv "AOZORA_APPVIEW_URL"))]
     (reify ports/SocialFeed
       (-fetch-posts [_ {:keys [roster limit]}]
         (->> (mapcat #(fetch-author-feed u (:did %) {:limit (or limit 10)}) roster)
              (sort-by :published-at) reverse vec))))))
