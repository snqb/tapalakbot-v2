(ns tapalakbot.render
  "Deterministic card renderer for Telegram HTML.
   Multi-line blocks, bold titles, location chips, separators.
   LLM never touches this."
  (:require [clojure.string :as str]
            [clj-harness.telegram.format :as hfmt]))

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
  "Format long price with space-separated thousands.
   25000 → \"25 000\""
  [price]
  (when price
    (let [p (long price)]
      (-> (str p)
          (str/reverse)
          (str/replace #"(\d{3})(?=\d)" "$1 ")
          (str/reverse)))))

;; ════════════════════ HTML ESCAPING ════════════════════

(defn escape-html
  "Escape HTML special chars for Telegram."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

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
  [{:keys [title price currency url platform condition year mileage city tier desc]
    :or {currency "сом"}}]
  (let [emoji    (tier-emoji tier)
        price-s  (when price
                   (str "<b>" (format-price price) " " (escape-html currency) "</b>"))
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
           (str "<a href=\"" url "\">Открыть"
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
    :mashina "Mashina.kg"
    nil))

(defn- result-link
  [{:keys [url platform]}]
  (when-not (str/blank? url)
    (let [label (platform-label platform)]
      (str "<a href=\"" (escape-html url) "\">"
           (if label (str "Открыть на " label) "Открыть объявление")
           " →</a>"))))

(defn- result-price
  [{:keys [price currency]}]
  (if price
    (str (format-price price) " " (escape-html (or currency "сом")))
    "Цена не указана"))

(defn- result-detail
  [{:keys [year mileage engine gearbox city condition]}]
  (->> [(when year (str year " г."))
        (when mileage (str (format-price mileage) " км"))
        (when engine (str (escape-html engine) " л"))
        (when gearbox (escape-html (truncate-text gearbox 24)))
        (when city (str "📍 " (escape-html city)))
        (when condition (escape-html (truncate-text condition 36)))]
       (remove nil?)
       (str/join " · ")))

(defn- result-tier-prefix
  [card]
  (when (:tier card)
    (str (tier-emoji (:tier card)) " ")))

(defn- render-featured-result
  [index card]
  (let [detail (result-detail card)
        link (result-link card)]
    (str "<h3>" index ". " (result-tier-prefix card)
         (result-title card) "</h3>"
         "<p><mark>" (result-price card) "</mark>"
         (when-not (str/blank? detail) (str "<br/>" detail))
         "</p>"
         (when link (str "<p>" link "</p>")))))

(defn- render-hidden-result
  [card]
  (let [detail (result-detail card)
        link (result-link card)]
    (str "<li>" (result-tier-prefix card) "<b>" (result-title card) "</b>"
         " — <mark>" (result-price card) "</mark>"
         (when-not (str/blank? detail) (str "<br/>" detail))
         (when link (str "<br/>" link))
         "</li>")))

(defn- http-url?
  [value]
  (and (string? value)
       (or (str/starts-with? value "https://")
           (str/starts-with? value "http://"))))

(defn- render-image-slideshow
  [cards]
  (let [images (->> cards
                    (keep :image)
                    (filter http-url?)
                    distinct
                    (take inline-image-limit))]
    (when (seq images)
      (str "<tg-slideshow>"
           (apply str
                  (map #(str "<img src=\"" (escape-html %) "\"/>") images))
           "<figcaption>Фото вариантов 1–" (count images) "</figcaption>"
           "</tg-slideshow>"))))

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

(defn render-results-rich
  "Render dense, Android-safe Telegram Rich HTML.
   Up to six photos share one slideshow, six linked cards stay visible, and
   all remaining cards stay actionable inside collapsed details."
  [cards]
  (let [cards (vec cards)
        visible (take visible-result-limit cards)
        hidden (drop visible-result-limit cards)]
    (when (seq cards)
      (str "<h2>Варианты (" (count cards) ")</h2>"
           (render-image-slideshow visible)
           (str/join "<hr/>"
                     (map-indexed
                      (fn [index card]
                        (render-featured-result (inc index) card))
                      visible))
           (when (seq hidden)
             (let [hidden-count (count hidden)]
               (str "<details><summary>Ещё " hidden-count " "
                    (russian-variant-word hidden-count) "</summary>"
                    "<ul>" (apply str (map render-hidden-result hidden)) "</ul>"
                    "</details>")))
           (render-source-footer cards)))))

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
