(ns tapalakbot.render
  "Deterministic card renderer for Telegram HTML.
   Replaces LLM-generated HTML with structured Clojure rendering."
  (:require [clojure.string :as str]))

;; ══════════════════════ TIER ASSIGNMENT ══════════════════════

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

;; ══════════════════════ PRICE FORMATTING ══════════════════════

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

;; ══════════════════════ SINGLE CARD RENDERING ══════════════════════

(defn- escape-html
  "Escape HTML special chars for Telegram."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn render-card
  "Render a single card to Telegram HTML.
   Input: {:title :price :currency :url :platform :condition :year :mileage :city :tier}
   Returns: single HTML-formatted line."
  [{:keys [title price currency url platform condition year mileage city tier]
    :or {currency "сом"}}]
  (let [emoji   (tier-emoji tier)
        price-s (format-price price)
        parts   (cond-> []
                  true      (conj (str emoji " " (escape-html title)))
                  price-s   (conj (str "<b>" price-s " " (escape-html currency) "</b>"))
                  condition (conj (str (escape-html condition)))
                  year      (conj (str year))
                  mileage   (conj (str (format-price mileage) " км"))
                  city      (conj (str "📍 " (escape-html city)))
                  platform  (conj (str "(" (escape-html platform) ")")))]
    (str (str/join " — " parts)
         (when (and url (not (str/blank? url)))
           (str "\n    <a href=\"" url "\">открыть</a>")))))

;; ══════════════════════ GROUPED CARD RENDERING ══════════════════════

(def tier-order [:great :good :premium])

(def tier-headers
  {:great   "🔥 Выгодная цена"
   :good    "💰 Хорошая цена"
   :premium "💎 Премиум"})

(defn render-cards
  "Group cards by tier and render each group with header.
   Cards without a tier go into :good by default."
  [cards]
  (let [grouped (->> cards
                     (map #(assoc % :tier (or (:tier %) :good)))
                     (group-by :tier))]
    (str/join "\n\n"
              (for [tier tier-order
                    :let [group (get grouped tier)]
                    :when (seq group)]
                (str "<b>" (tier-headers tier) "</b>\n"
                     (str/join "\n" (map render-card group)))))))

;; ══════════════════════ FULL REPLY RENDERING ══════════════════════

(defn render-reply
  "Render full Telegram HTML reply.
   Input: {:mode :intro :cards :cta :assumptions}
   Modes: :error, :no-results, :clarify, :intro, or nil (full card render)."
  [{:keys [mode intro cards cta assumptions]}]
  (case mode
    :error      (str "❌ " (or intro "Произошла ошибка. Попробуйте ещё раз."))
    :no-results (str "🔍 " (or intro "Ничего не найдено по вашему запросу.")
                     (when (seq assumptions)
                       (str "\n\nПредположения: " (if (vector? assumptions) (str/join " · " assumptions) assumptions))))
     :clarify    (str "❗ " (or intro "Уточните, пожалуйста, ваш запрос."))
     ;; Default: full card render
     (str (when (and intro (not (str/blank? intro)))
            (str intro "\n\n"))
          (when (seq cards)
            (render-cards cards))
          (when (seq assumptions)
            (let [a (if (vector? assumptions) (str/join " · " assumptions) (str assumptions))]
              (str "\n\n<i>" a "</i>")))
          (when (and cta (not (str/blank? cta)))
            (str "\n\n" cta)))))

(defn render-welcome
  "Render welcome/greeting message."
  [name]
  (str "👋 Салам, " (escape-html (or name "друг")) "!\n\n"
       "Я <b>TapalakBot</b> — умный помощник по покупкам на Lalafo.kg 🇰🇬\n\n"
       "Просто напиши что ищешь! 🔍"))
