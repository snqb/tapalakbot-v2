(ns tapalakbot.render
  "Deterministic card renderer for Telegram HTML.
   Multi-line blocks, bold titles, location chips, separators.
   LLM never touches this."
  (:require [clojure.string :as str]
            [clj-harness.telegram.format :as hfmt]
            [tapalakbot.result-order :as result-order]))

;; ════════════════════ TIER ASSIGNMENT ════════════════════

(defn assign-tier
  "Assign :great/:good/:premium based on price vs market avg ratio.
   ratio < 0.7 → :great (fire deal)
   ratio > 1.3 → :premium
   else → :good"
  [price market-avg]
  (when (and price market-avg (pos? market-avg))
    (let [ratio (/ (double price) (double market-avg))]
      (cond
        (< ratio 0.7) :great
        (> ratio 1.3) :premium
        :else :good))))

(defn tier-emoji
  "Map tier keyword to emoji."
  [tier]
  (case tier
    :great   "🔥"
    :good    "💰"
    :premium "💎"
    "•"))

;; ════════════════════ PRICE FORMATTING ════════════════════

(defn format-price
  "Format source amounts without rounding away fractional units."
  [price]
  (when price
    (let [[whole fraction] (str/split (.toPlainString (bigdec price)) #"\." 2)
          grouped (-> whole str/reverse
                      (str/replace #"(\d{3})(?=\d)" "$1 ")
                      str/reverse)]
      (str grouped (when fraction (str "." fraction))))))

;; ════════════════════ HTML ESCAPING ════════════════════

(defn escape-html
  "Escape HTML special chars for Telegram."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn format-money
  "Render a source amount and currency; unknown currency never becomes KGS."
  [price currency]
  (if (some? price)
    (str (format-price price) " "
         (escape-html (if (str/blank? currency) "валюта не указана" currency)))
    "Цена не указана"))

(defn strip-markdown
  "Convert agent Markdown to Telegram-safe HTML through the shared harness
   formatter. Unsupported tables and ==highlights== are normalized before send."
  [text]
  (when text
    (-> text
        hfmt/md->html
        str/trim)))


;; ════════════════════ SINGLE CARD RENDERING ════════════════════

(defn render-card
  "Render a single card to Telegram HTML. Multi-line block format:
   🔥 <b>Title</b>
   💰 25 000 сом · 📍 Бишкек
   📋 Отличное состояние
   <i>Description snippet…</i>
   <a href=\"url\">Открыть на lalafo →</a>"
  [{:keys [title price currency url platform condition year mileage city tier desc]}]
  (let [emoji    (tier-emoji tier)
        price-s  (when price
                   (str "<b>" (format-money price currency) "</b>"))
        ;; Detail chips: location, year, mileage, condition
        chips    (cond-> []
                   city     (conj (str "📍 " (escape-html city)))
                   year     (conj (str year " г."))
                   mileage  (conj (str (format-price mileage) " км"))
                   condition (conj (str "📋 " (escape-html condition))))
        chip-str (when (seq chips) (str/join " · " chips))
        ;; Desc snippet (first 80 chars)
        desc-snip (when (and desc (pos? (count desc)))
                    (let [d (subs desc 0 (min 80 (count desc)))]
                      (str "<i>" (escape-html d)
                           (when (> (count desc) 80) "…") "</i>")))]
    (str emoji " <b>" (escape-html title) "</b>\n"
         (when price-s (str price-s "\n"))
         (when chip-str (str chip-str "\n"))
         (when desc-snip (str desc-snip "\n"))
         (when (and url (not (str/blank? url)))
           (str "<a href=\"" (escape-html url) "\">Открыть"
                (when platform (str " на " (escape-html (name platform))))
                " →</a>")))))

;; ════════════════════ GROUPED CARD RENDERING ════════════════════

(def tier-order [:great :good :premium])

(def tier-headers
  {:great   "🔥 Лучшая цена"
   :good    "💰 Хорошая цена"
   :premium "💎 Премиум"})

(defn render-cards
  "Group cards by tier and render with headers and separators.
   Cards without a tier go into :good by default."
  [cards]
  (let [grouped (->> cards
                     (map #(assoc % :tier (or (:tier %) :good)))
                     (group-by :tier))]
    (str/join "\n\n"
              (for [tier tier-order
                    :let [group (get grouped tier)]
                    :when (seq group)]
                (str "<b>" (tier-headers tier) "</b>\n\n"
                     (str/join "\n\n━━━━━━━━━━━━━━━\n\n"
                               (map render-card group)))))))

(def ^:private visible-result-limit 6)
(def ^:private inline-image-limit 6)

(defn- truncate-text
  [value limit]
  (let [s (str (or value ""))]
    (if (<= (count s) limit)
      s
      (str (subs s 0 (max 0 (dec limit))) "…"))))

(defn- result-title
  [{:keys [title]}]
  (escape-html (truncate-text title 72)))

(defn- platform-label
  [platform]
  (case platform
    :lalafo "Lalafo.kg"
    nil))

(defn- http-url?
  [value]
  (and (string? value)
       (or (str/starts-with? value "https://")
           (str/starts-with? value "http://"))))

(defn- result-link
  [{:keys [url platform]}]
  (when (and (http-url? url) (not (str/blank? url)))
    (let [label (platform-label platform)]
      (str "<a href=\"" (escape-html url) "\">"
           (if label (str "Открыть на " label) "Открыть объявление")
           " →</a>"))))

(defn- result-price
  [{:keys [price currency]}]
  (format-money price currency))

(defn- source-param-entries
  [params]
  (cond
    (map? params) (sort-by (comp str key) params)
    (sequential? params)
    (keep (fn [param]
            (cond
              (map? param) (let [label (or (:name param) (get param "name"))
                                value (or (:value param) (get param "value"))]
                            (when (and label (some? value)) [label value]))
              (and (sequential? param) (= 2 (count param))) param))
          params)
    :else []))

(defn- source-attribute
  [[key value]]
  (when (and (some? key) (some? value)
             (not (str/blank? (str value))))
    (str (escape-html key) ": " (escape-html value))))

(defn- condition-token
  [condition]
  (when (some? condition)
    (let [token (-> condition str str/trim str/lower-case)]
      (if (str/starts-with? token ":")
        (subs token 1)
        token))))

(defn- decision-condition-label
  [condition]
  (let [token (condition-token condition)]
    (cond
      (contains? #{"new" "новое" "новый" "новая"} token) "Новое"
      (contains? #{"used" "б/у" "бу" "использованное" "использованный"} token) "Б/у"
      (contains? #{"damaged" "поврежденное" "повреждённое" "неисправное"} token) "Поврежденное"
      (contains? #{"refurbished" "восстановленное"} token) "Восстановленное"
      (contains? #{"unknown" "неизвестно" "не указано"} token) "Состояние не указано"
      (and token (not (str/blank? token))) (str condition))))

(defn- condition-indication
  [{:keys [condition decision]}]
  (let [label (or (decision-condition-label condition)
                  (decision-condition-label (:condition decision))
                  "Состояние не указано")]
    (str "📋 " (escape-html label))))

(defn- result-detail
  [{:keys [year mileage engine gearbox city params]}]
  (let [attributes (concat
                    [(when year (str year " г."))
                     (when mileage (str (format-price mileage) " км"))
                     (when engine (str (escape-html engine) " л"))
                     (when gearbox (escape-html (truncate-text gearbox 24)))
                     (when city (str "📍 " (escape-html city)))]
                    (map source-attribute (source-param-entries params)))]
    (str/join " · " (remove nil? attributes))))

(defn- risk-warning
  [risk]
  (case (condition-token risk)
    "missing" "Материальная деталь запроса отсутствует в описании."
    "suspicious" "В источнике есть конкретный сигнал риска; проверьте объявление до покупки."
    "price_conflict" "Текущая цена или валюта в описании противоречит данным объявления."
    "incompatible" "Источник содержит явное противоречие запросу."
    nil))

(defn- unknown-condition-warning?
  [warning]
  (= "состояние в источнике не указано."
     (str/lower-case (str/trim (str warning)))))

(defn- decision-warnings
  [{:keys [decision]}]
  (let [warnings (concat (:warnings decision)
                         (when-let [warning (risk-warning (:risk decision))]
                           [warning]))]
    (->> warnings
         (remove #(str/blank? (str %)))
         (remove unknown-condition-warning?)
         (map str)
         distinct)))

(defn- decision-block
  [card]
  (let [warnings (map escape-html (decision-warnings card))]
    (when (seq warnings)
      (str "<p><b>⚠️ Важно:</b> " (str/join " · " warnings) "</p>"))))

(defn- decision-fit
  [card]
  (if (= :direct (get-in card [:decision :fit])) :direct :possible))

(defn- fit-heading
  [fit]
  (if (= fit :direct)
    "<p><b>Ближе к запросу</b></p>"
    "<p><b>Возможные варианты — проверьте соответствие</b></p>"))

(defn- render-featured-result
  [index card]
  (let [detail (result-detail card)
        condition (condition-indication card)
        link (result-link card)]
    (str "<h3>" (or (:result-index card) index) ". "
         (result-title card) "</h3>"
         "<p><mark>" (result-price card) "</mark>"
         (when (or (seq detail) condition)
           (str "<br/>" (str/join " · " (remove nil? [detail condition]))))
         "</p>"
         (decision-block card)
         (when link (str "<p>" link "</p>")))))

(defn- render-visible-results
  [cards]
  (second
   (reduce (fn [[previous html] [index card]]
             (let [fit (decision-fit card)]
               [fit (str html
                         (when (seq html) "<hr/>")
                         (when (not= fit previous) (fit-heading fit))
                         (render-featured-result (inc index) card))]))
           [nil ""]
           (map-indexed vector cards))))

(defn- render-hidden-result
  [index card]
  (let [detail (result-detail card)
        condition (condition-indication card)
        link (result-link card)]
    (str "<li><b>" (or (:result-index card) index) ". "
         (result-title card) "</b>"
         " — <mark>" (result-price card) "</mark>"
         (when (or (seq detail) condition)
           (str "<br/>" (str/join " · " (remove nil? [detail condition]))))
         (when-let [warnings (decision-block card)]
           warnings)
         (when link (str "<br/>" link))
         "</li>")))

(defn- render-hidden-results
  [cards offset]
  (loop [remaining cards
         index offset
         html ""]
    (if-let [group (seq (take-while #(= (decision-fit (first remaining))
                                        (decision-fit %))
                                    remaining))]
      (let [group-html (str "<p><b>"
                            (if (= :direct (decision-fit (first group)))
                              "Ближе к запросу"
                              "Возможные варианты — проверьте соответствие")
                            "</b></p><ul>"
                            (apply str
                                   (map-indexed
                                    (fn [group-index card]
                                      (render-hidden-result (+ index group-index 1) card))
                                    group))
                            "</ul>")]
        (recur (drop (count group) remaining)
               (+ index (count group))
               (str html group-html)))
      html)))


(defn- render-image-slideshow
  [cards include-images?]
  (when include-images?
    (let [images (->> cards
                      (map-indexed (fn [index card]
                                     {:index (or (:result-index card) (inc index))
                                      :url (or (:image card) (first (:images card)))}))
                      (filter #(http-url? (:url %)))
                      (take inline-image-limit))]
      (when (seq images)
        (str "<tg-slideshow>"
             (apply str
                    (map #(str "<img src=\"" (escape-html (:url %)) "\"/>") images))
             "<figcaption>Фото объявлений: "
             (str/join ", " (map :index images)) "</figcaption>"
             "</tg-slideshow>")))))

(defn- russian-variant-word
  [n]
  (let [mod-100 (mod n 100)
        mod-10 (mod n 10)]
    (cond
      (<= 11 mod-100 14) "вариантов"
      (= mod-10 1) "вариант"
      (<= 2 mod-10 4) "варианта"
      :else "вариантов")))

(defn- render-source-footer
  [cards]
  (let [sources (->> cards (keep (comp platform-label :platform)) distinct vec)]
    (when (seq sources)
      (str "<footer>" (if (= 1 (count sources)) "Источник: " "Источники: ")
           (str/join " · " sources) "</footer>"))))

(defn selection-summary-text
  "Describe only source counts and completed selection decisions."
  [{:keys [selection collection]}]
  (let [parts (cond-> []
                (number? (:raw collection))
                (conj (str "Собрано " (:raw collection)))
                (number? (:input-count selection))
                (conj (str "проверено " (:input-count selection)))
                (number? (:accepted-count selection))
                (conj (str (:accepted-count selection) " ближе к запросу"))
                (number? (:rejected-count selection))
                (conj (str (:rejected-count selection) " отклонено"))
                (pos? (or (:uncertain-count selection) 0))
                (conj (str (:uncertain-count selection) " требуют проверки"))
                (:truncated collection)
                (conj "сбор ограничен")
                (pos? (or (:failed-queries collection) 0))
                (conj "часть запросов к площадке не выполнена"))]
    (when (seq parts) (str (str/join " · " parts) "."))))

(defn- visible-order-caption
  [visible-count pool-count sort-mode]
  (let [total (if (number? pool-count) pool-count visible-count)
        mode (or sort-mode :price-asc)
        label (result-order/mode-label mode)]
    (str "Показано " visible-count " из " total
         " · Подходящие — сначала"
         " · " label)))

(defn- selection-details
  [opts]
  (when (or (:selection opts) (:collection opts))
    (str "<details><summary>Как отбирали</summary><p>"
         (escape-html
          (or (selection-summary-text opts)
              "Проверка завершена; подходящих вариантов не подтверждено."))
         "</p></details>")))

(defn render-results-rich
  "Render deterministic Telegram Rich HTML recommendation cards.
   The optional opts map carries :selection/:collection facts,
   :pool-count/:sort-mode display metadata and :include-images? for a
   text-only retry. Cards are already ordered by the caller."
  ([cards] (render-results-rich cards {}))
  ([cards opts]
   (let [cards (vec cards)
         visible (vec (take visible-result-limit cards))
         hidden (vec (drop visible-result-limit cards))
         include-images? (not= false (:include-images? opts))]
     (when (or (seq cards) (:selection opts) (:collection opts))
       (str (if (seq cards)
              "<h2>Варианты</h2>"
              "<h2>Подходящих вариантов не подтверждено</h2>")
            (when (seq cards)
              (str "<p><i>"
                   (escape-html
                    (visible-order-caption (count cards)
                                           (:pool-count opts)
                                           (:sort-mode opts)))
                   "</i></p>"))
            (render-image-slideshow cards include-images?)
            (render-visible-results visible)
            (when (seq hidden)
              (let [hidden-count (count hidden)]
                (str "<details><summary>Ещё " hidden-count " "
                     (russian-variant-word hidden-count) "</summary>"
                     (render-hidden-results hidden visible-result-limit)
                     "</details>")))
            (selection-details opts)
            (render-source-footer cards))))))

;; ════════════════════ FULL REPLY RENDERING ════════════════════

(defn render-reply
  "Render full Telegram HTML reply.
   Input: {:mode :intro :cards :cta :assumptions :market-note :comparison :verdict}
   Modes: :error, :no-results, :clarify, :shortlist, :research, :followup, :compare."
  [{:keys [mode intro cards cta assumptions market-note comparison verdict]}]
  (case mode
    :error      (str "❌ " (or intro "Произошла ошибка. Попробуйте ещё раз."))
    :no-results (str "🔍 " (or intro "Ничего не найдено по вашему запросу.")
                     (when (seq assumptions)
                       (str "\n\nПредположения: " (if (vector? assumptions) (str/join " · " assumptions) assumptions))))
    :clarify    (str "❗ " (or intro "Уточните, пожалуйста, ваш запрос."))
    ;; Research mode — intro + market note + cards
    :research
    (str (when (and intro (not (str/blank? intro)))
           (str (strip-markdown intro) "\n\n"))
         (when (and market-note (not (str/blank? market-note)))
           (str "<i>" (escape-html market-note) "</i>\n\n"))
         (when (seq cards)
           (render-cards cards))
         (when (seq assumptions)
           (let [a (if (vector? assumptions) (str/join " · " assumptions) (str assumptions))]
             (str "\n\n<i>" a "</i>")))
         (when (and cta (not (str/blank? cta)))
           (str "\n\n💬 " cta)))
    ;; Followup mode — conversational answer, no cards
    :followup
    (str (when (and intro (not (str/blank? intro)))
           (strip-markdown intro))
         (when (and cta (not (str/blank? cta)))
           (str "\n\n💬 " cta)))
    ;; Compare mode — intro + comparison points + verdict
    :compare
    (str (when (and intro (not (str/blank? intro)))
           (str (strip-markdown intro) "\n\n"))
         (when (seq comparison)
           (str (str/join "\n" (map #(str "• " (escape-html %)) comparison)) "\n\n"))
         (when (and verdict (not (str/blank? verdict)))
           (str "<b>Итог:</b> " (escape-html verdict) "\n"))
         (when (and cta (not (str/blank? cta)))
           (str "\n💬 " cta)))
    ;; Default: full card render (shortlist, refine, etc.)
    (str (when (and intro (not (str/blank? intro)))
           (str (strip-markdown intro) "\n\n"))
         (when (seq cards)
           (render-cards cards))
         (when (seq assumptions)
           (let [a (if (vector? assumptions) (str/join " · " assumptions) (str assumptions))]
             (str "\n\n<i>" a "</i>")))
         (when (and cta (not (str/blank? cta)))
           (str "\n\n💬 " cta)))))

(defn render-welcome
  "Render welcome/greeting message."
  [name]
  (str "👋 Салам, " (escape-html (or name "друг")) "!\n\n"
       "Я <b>TapalakBot</b> — умный помощник по покупкам на Lalafo.kg 🇰🇬\n\n"
       "Просто напиши что ищешь! 🔍"))
