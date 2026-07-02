(ns newscaster.preview
  "ローカル確認プレビュー（out/preview.html）— UI/UX は kotoba-lang の
  liquid-glass-ui をベースにする（ADR-2607021130）。ad-hoc HTML でなく、
  liquid-glass.components（panel/badge/tab-bar）の pure hiccup を
  shitsuke.hiccup/->html で SSR する（no-build、glass material は CSS のみ）。

  内容は store の構造化データから直接組む: channel 設計 / rundown+cites /
  原稿（ja·en）/ per-lang videos / 放送台帳。"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [liquid-glass.components :as lg]
            [liquid-glass.style :as ls]
            [newscaster.store :as store]
            [shitsuke.hiccup :as h]))

(def ^:private page-css
  "html{min-height:100%}
body{margin:0;min-height:100vh;font-family:'Hiragino Sans','Noto Sans JP',-apple-system,sans-serif;
     color:#e2e8f0;background:radial-gradient(1200px 600px at 15% -5%,#1d3a5f 0%,transparent 55%),
     radial-gradient(900px 500px at 95% 10%,#173a56 0%,transparent 50%),
     linear-gradient(160deg,#0b1220 0%,#101b33 55%,#0d1526 100%);background-attachment:fixed;}
.shell{max-width:1100px;margin:0 auto;padding:44px 24px 96px;}
.header h1{font-size:24px;margin:0 0 8px;color:#fff;text-shadow:0 2px 12px rgba(0,0,0,.4);}
.header p{margin:0 0 24px;color:#94a3b8;font-size:13px;line-height:1.6;max-width:760px;}
.accent{color:#38bdf8;}
section{margin:28px 0;}
section>h2{font-size:12px;text-transform:uppercase;letter-spacing:.1em;color:#7dd3fc;margin:0 0 12px;font-weight:700;}
.liquid-glass__panel{color:#e2e8f0;}
.liquid-glass__panel h3{margin:0 0 8px;font-size:14px;color:#fff;}
.cols{display:grid;grid-template-columns:1fr 1fr;gap:16px;}
video{width:100%;border-radius:14px;display:block;}
.slides{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;}
.slides img{width:100%;border-radius:10px;display:block;}
.script-seg{margin:0 0 14px;}
.script-seg .lines{margin:6px 0 4px;font-size:13.5px;line-height:1.7;}
.script-seg .cap{font-size:11.5px;color:#94a3b8;}
.ledger{font-size:12px;line-height:2.1;}
.liquid-glass__badge{font-size:11px;margin-right:8px;color:#e2e8f0;}
.badge-hold{color:#fca5a5;}
.badge-commit{color:#86efac;}
.meta-row{display:flex;gap:10px;flex-wrap:wrap;margin:0 0 14px;}
.liquid-glass__tab{color:#cbd5e1;}
.liquid-glass__tab--active{color:#0b1220;}")

(defn- fmt-format [{:keys [segment duration-s items]}]
  (str (name segment) " " duration-s "s" (when (pos? items) (str " ×" items))))

(defn- channel-panel [ch]
  (lg/panel
   [[:h3 (:title ch)]
    [:p {:style "margin:0 0 10px;font-size:13px;color:#cbd5e1;"} (:description ch)]
    [:div {:class "meta-row"}
     (lg/badge (str "編成 " (reduce + 0 (map :duration-s (:format ch))) "s+"))
     (lg/badge (str "langs " (str/join "·" (:langs ch))))
     (lg/badge (str "anchor " (get-in ch [:persona :anchor])))
     (lg/badge "AI 生成開示")]
    [:p {:style "margin:0;font-size:12px;color:#94a3b8;"}
     (str/join " → " (map fmt-format (:format ch)))]]
   {:surface :thick :elevation :raised}))

(defn- video-panel [label episode-id lang video]
  (lg/panel
   [[:h3 label]
    (if (:path video)
      [:video {:src (str episode-id "/" lang "/episode.mp4") :controls true
               :preload "metadata"}]
      [:p "（未レンダ）"])
    [:p {:style "margin:8px 0 0;font-size:11px;color:#94a3b8;word-break:break-all;"}
     (:cid video)]]
   {:surface :regular :elevation :overlay}))

(defn- script-panel [ep]
  (lg/panel
   (into [[:h3 "原稿（生成された AI ニュース）"]]
         (for [{:keys [segment lines caption i18n]} (:script ep)]
           [:div {:class "script-seg"}
            (lg/badge (name segment))
            [:div {:class "lines"}
             (for [l lines] [:div l])
             (when-let [en (get-in i18n ["en" :lines])]
               [:div {:style "margin-top:4px;color:#7dd3fc;font-size:12.5px;"}
                (for [l en] [:div l])])]
            [:div {:class "cap"} caption]]))
   {:surface :regular :elevation :raised}))

(defn- rundown-panel [ep articles]
  (lg/panel
   (into [[:h3 "rundown（編成 + 出典）"]]
         (for [{:keys [segment article-ids]} (:rundown ep)]
           [:p {:style "margin:4px 0;font-size:13px;"}
            (lg/badge (name segment))
            (str/join "、" (map #(:title (get articles %) %) article-ids))]))
   {:surface :clear :elevation :flat}))

(defn- ledger-panel [facts]
  (lg/panel
   (into [[:h3 "放送台帳（append-only）"]]
         [[:div {:class "ledger"}
           (for [f facts]
             [:div
              (lg/badge (name (or (:disposition f) :record))
                        {:class (case (:disposition f)
                                  :hold "badge-hold" :commit "badge-commit" nil)})
              (store/ledger-line f)])]])
   {:surface :regular :elevation :raised}))

(defn page
  "store から preview ページ hiccup を組む。videos は {lang video}。"
  [st episode-id]
  (let [ep  (store/episode st episode-id)
        ch  (store/channel-of st (:channel ep))
        arts (into {} (map (juxt :id identity)) (store/all-articles st))]
    [:html {:lang "ja"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (str (:title ch) " — " episode-id " preview")]
      (ls/inline-style-hiccup (str (ls/root-css) "\n" (ls/component-css)))
      [:style page-css]]
     [:body
      [:div {:class "shell"}
       [:div {:class "header"}
        [:h1 [:span {:class "accent"} "▍"] (str (:title ch) " — " episode-id)]
        [:p (str "ai-gftd-newscaster / UI: liquid-glass-ui。"
                 "尺はナレーション実時間で同期、公開は人間の editorial sign-off。")]]
       [:section [:h2 "channel（設計 = data）"] (channel-panel ch)]
       [:section [:h2 "episode videos"]
        [:div {:class "cols"}
         (for [[lang v] (sort-by key (:videos ep))]
           (video-panel (if (= lang "ja") "日本語版" (str/upper-case lang))
                        episode-id lang v))]]
       [:section [:h2 "news-card frames（ja）"]
        (lg/panel
         [[:div {:class "slides"}
           (for [i (range 6)]
             [:img {:src (format "%s/ja/slide-%02d.png" episode-id i)
                    :alt (str "slide " i)}])]]
         {:surface :clear :elevation :flat})]
       [:section [:h2 "rundown"] (rundown-panel ep arts)]
       [:section [:h2 "script"] (script-panel ep)]
       [:section [:h2 "ledger"] (ledger-panel (store/ledger st))]]]]))

(defn write!
  "SSR して out-dir/preview.html に書く。→ path"
  [st episode-id out-dir]
  (let [f (io/file out-dir "preview.html")]
    (io/make-parents f)
    (spit f (str "<!doctype html>\n" (h/->html (page st episode-id))))
    (.getPath f)))
