(ns tapalakbot.render
  "Deterministic card renderer for Telegram HTML.
   Multi-line blocks, bold titles, location chips, separators.
   LLM never touches this."
  (:require [clojure.string :as str]))

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

(defn- convert-tables-to-lists
  "Convert markdown tables to bullet lists. Processes entire text."
  [text]
  (let [lines (clojure.string/split-lines text)]
    (loop [remaining lines
           result []
           in-table false
           headers []
           rows []]
      (if-let [line (first remaining)]
        (let [is-table-row? (and (clojure.string/starts-with? (clojure.string/trim line) "|")
                                 (clojure.string/ends-with? (clojure.string/trim line) "|"))
              is-separator? (re-matches #"\|[\s\-:|]+\|" (clojure.string/trim line))]
          (cond
            ;; Start of table
            (and is-table-row? (not is-separator?) (not in-table))
            (recur (rest remaining) result true
                   (->> (clojure.string/split line #"\|")
                        (map clojure.string/trim)
                        (remove clojure.string/blank?)
                        vec)
                   [])
            ;; Separator line (skip)
            (and is-table-row? is-separator? in-table)
            (recur (rest remaining) result in-table headers rows)
            ;; Table data row
            (and is-table-row? (not is-separator?) in-table)
            (let [cells (->> (clojure.string/split line #"\|")
                             (map clojure.string/trim)
                             (remove clojure.string/blank?)
                             vec)]
              (recur (rest remaining) result in-table headers (conj rows cells)))
            ;; End of table (non-table row after table)
            (and (not is-table-row?) in-table)
            (let [table-lines (map (fn [row]
                                     (str "• " (clojure.string/join " — "
                                                (map (fn [h c] (str h ": " c))
                                                     (take (count row) headers)
                                                     row))))
                                   rows)]
              (recur (rest remaining)
                     (into result (concat table-lines [""]))
                     false [] []))
            ;; Regular line
            :else
            (recur (rest remaining) (conj result line) false [] [])))
        ;; End of text — flush remaining table
        (if in-table
          (let [table-lines (map (fn [row]
                                   (str "• " (clojure.string/join " — "
                                              (map (fn [h c] (str h ": " c))
                                                   (take (count row) headers)
                                                   row))))
                                 rows)]
            (clojure.string/join "\n" (into result table-lines)))
          (clojure.string/join "\n" result))))))

(defn strip-markdown
  "Convert common Markdown to Telegram HTML.
   Handles: **bold**, ### headings, --- separators, *italic*, [text](url) links, tables."
  [text]
  (when text
    (-> text
        ;; Convert markdown tables to bullet lists (LLM ignores "no tables" instruction)
        convert-tables-to-lists
        (clojure.string/replace #"(?m)^---$" "")
        (clojure.string/replace #"(?m)^#{1,4}\s+(.+)$" "<b>$1</b>")
        (clojure.string/replace #"(?<!\*)\*\*([^*]+)\*\*(?!\*)" "<b>$1</b>")
        (clojure.string/replace #"\[([^\]]+)\]\(([^)]+)\)" "<a href=\"$2\">$1</a>")
        (clojure.string/replace #"(?m)^\s*[-•]\s+" "• ")
        (clojure.string/replace #"(?:\n\s*){3,}" "\n\n")
        clojure.string/trim)))


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
