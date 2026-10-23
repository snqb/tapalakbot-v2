(ns tapalakbot.result-delivery
  "Owned result controls and lossless native Rich Message delivery."
   (:require [clj-harness.telegram :as tg]
             [clojure.string :as str]
             [clojure.tools.logging :as log]
             [tapalakbot.bot-state :as state]
             [tapalakbot.render :as render]
             [tapalakbot.result-order :as result-order]
             [tapalakbot.result-store :as result-store]
             [tapalakbot.search :as search]))

(defn- refinement-budget [direction cards]
  (let [prices (->> cards (keep search/card-price-kgs) (filter pos?) sort vec)]
    (when (seq prices)
      (let [median (double (nth prices (quot (count prices) 2)))]
        (long (if (= direction :cheaper)
                (Math/floor (* 0.9 median))
                (Math/ceil (* 1.1 median))))))))

(defn- sort-control-label [mode active?]
  (str (when active? "✓ ") (result-order/mode-label mode)))

 (defn results-keyboard
   "Create owned pagination, sorting and budget controls for a complete pool."
   [query cursor-id has-more owner-id context]
   (let [context (assoc context :query query)
         active-mode (or (:sort-mode context) result-order/default-mode)
         sort-row (when cursor-id
                    (mapv (fn [mode]
                            {"text" (sort-control-label mode (= mode active-mode))
                             "callback_data" (str "sort:" (name mode) ":" cursor-id)})
                          [:price-asc :price-desc :relevance]))
         rows (cond-> []
                (and has-more cursor-id)
                (conj [{"text" (str "🔄 Ещё " result-store/page-size " вариантов")
                        "callback_data" (str "page:" cursor-id)}])
                sort-row
                (conj sort-row)
                (some? (refinement-budget :cheaper (:cards context)))
                (conj (mapv (fn [[direction label]]
                              {"text" label
                               "callback_data" (str (name direction) ":"
                                                    (state/cache-refinement! owner-id context))})
                            [[:cheaper "⬇️ Дешевле"] [:dearer "⬆️ Дороже"]])))]
     (when (seq rows) {"inline_keyboard" rows})))

(defn refinement-message
  "Consume only an owned callback and return an authoritative replacement budget."
  [owner-id direction callback-token]
  (when-let [{:keys [query cards]} (state/take-refinement! owner-id callback-token)]
    (when-let [budget (refinement-budget direction cards)]
      (merge {:text (str query "\nПокажи варианты "
                         (if (= direction :cheaper) "дешевле: до " "дороже: от ")
                         budget " сом.")}
             (if (= direction :cheaper)
               {:price-min nil :price-max budget}
               {:price-min budget :price-max nil})))))

(defn- attempt [kind f]
  (try
    (let [result (f)]
      (when (and result (not= false (get result "ok"))) result))
    (catch Exception e
      (log/warn :result-delivery-failed :kind kind :error (.getMessage e))
      nil)))

(defn- legacy-cards [cards]
  (str/join
   "\n\n"
   (map-indexed
    (fn [index card]
      (str "<b>" (or (:result-index card) (inc index)) ".</b> "
           (render/render-card card)
           (when-let [warnings (seq (get-in card [:decision :warnings]))]
             (str "\n" (render/escape-html (str/join " · " warnings))))))
    cards)))

 (defn send-reply!
   "Send analysis and source-owned cards with controls attached to the card document.
    Failed media retries as text-only Rich HTML, then all cards as legacy HTML."
   [chat-id {:keys [intro cards selection collection sort-mode pool-count]}
    {:keys [keyboard thread-id]}]
   (let [card-opts {:selection selection
                    :collection collection
                    :sort-mode sort-mode
                    :pool-count pool-count}]
     (when-not (str/blank? intro)
       (or (attempt :markdown
                    #(tg/send-md chat-id intro
                                 :reply_markup (when-not (seq cards) keyboard)
                                 :thread-id thread-id))
           (attempt :plain
                    #(tg/send-message chat-id intro :parse-mode nil
                                      :reply_markup (when-not (seq cards) keyboard)
                                      :thread-id thread-id))))
     (when (or (seq cards) selection)
       (let [rich-send (fn [images?]
                         (tg/send-rich-message
                          chat-id :html (render/render-results-rich
                                         cards (assoc card-opts :include-images? images?))
                          :preview false
                          :reply-markup (when (seq cards) keyboard)
                          :thread-id thread-id))]
         (or (attempt :rich-media #(rich-send true))
             (attempt :rich-text #(rich-send false))
             (doseq [chunk (tg/split-message
                           (if (seq cards)
                             (legacy-cards cards)
                             "Подходящих вариантов не подтверждено."))]
               (when-not (attempt :legacy
                                 #(tg/send-message chat-id chunk :parse-mode "HTML"
                                                   :preview false :reply_markup keyboard
                                                   :thread-id thread-id))
                 (throw (ex-info "Telegram result delivery unavailable" {})))))))))
