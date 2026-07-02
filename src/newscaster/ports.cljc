(ns newscaster.ports
  "注入境界（swap）— actor コアが依存する外界は全て protocol port:

    NewsFeed  — A 層（ai-gftd-news）からの記事取得。mock ‖ kotoba XRPC。
    Renderer  — render-spec → 動画 asset。mock ‖ newscaster.render
                （Java2D 16:9 news-card + ffmpeg。ANIMEKA_URL で animeka へ構造記録）。
    Publisher — 承認済み episode の外部公開。mock ‖ newscaster.youtube
                （YouTube Data API v3）。**publish op の人間承認後にのみ呼ばれる。**

  mock はすべて決定的・IO 無しで、contract test と demo を offline で回す。")

(defprotocol NewsFeed
  (-fetch-articles [feed opts] "→ [article ..]（A 層 article の写像）"))

(defprotocol Renderer
  (-render [renderer channel episode render-spec]
    "→ {:video {:cid ..} :thumbnail {:cid ..} :frames [..]} | nil（失敗）"))

(defprotocol Publisher
  (-publish [publisher channel episode publish-meta]
    "→ {:video-id .. :url ..} | nil（失敗）"))

;; ───────────────────────── deterministic mocks ─────────────────────────

(defn mock-feed
  "固定の記事列を返す NewsFeed。"
  [articles]
  (reify NewsFeed (-fetch-articles [_ _opts] (vec articles))))

(defn mock-renderer []
  (reify Renderer
    (-render [_ _ch episode _spec]
      {:video     {:cid (str "cid-video-" (:id episode)) :type :video}
       :thumbnail {:cid (str "cid-thumb-" (:id episode)) :type :thumbnail}
       :frames    []})))

(defn failing-renderer
  "常に失敗する Renderer（render 失敗 → hold のテスト用）。"
  []
  (reify Renderer (-render [_ _ _ _] nil)))

(defn mock-publisher []
  (reify Publisher
    (-publish [_ _ch episode _meta]
      {:video-id (str "yt-" (:id episode))
       :url      (str "https://youtube.example/watch?v=yt-" (:id episode))})))
