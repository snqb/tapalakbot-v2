(ns tapalakbot.policy
  "Deterministic turn classifier for TapalakBot.
   Classifies user messages into intent categories without LLM."
  (:require [clojure.string :as str]))

;; ══════════════════════ REGEX PATTERNS ══════════════════════

(def greeting-re
  "Greeting messages — no search needed."
  #"(?i)^\s*(привет|приветств|салам|хай|hello|hi|добр[оы]й\s*(день|вечер|утро)|здравствуй)")

(def reset-re
  "Reset / new dialog intent."
  #"(?i)(новый диалог|сброс|забудь|очисти|начать\s*сначала|сначала|заново)")

(def tracking-re
  "Tracking subscription intent."
  #"(?i)(отслежив|подписа|уведомля|монитор|следить|оповещ)")

(def help-re
  "Help / info intent."
  #"(?i)(помощь|помоги|help|что\s+умеешь|как\s+пользов|что\s+делаешь|инструкци)")

(def thanks-re
  "Thanks / acknowledgement — conversation can end."
  #"(?i)^\s*(спасибо|спс|благодар|ок|окей|понял|ясно|хорошо|ладно|thanks|thank\s+you)\s*$")

;; ══════════════════════ KEYWORD SETS ══════════════════════

(def refine-keywords
  "Short refinement phrases — modify a previous search."
  #{"дешевле" "дешевлe" "подешевле" "дороже" "подороже"
    "только новые" "только б/у" "только бу"
    "чёрный" "белый" "синий" "красный"
    "серый" "розовый" "золотой"
    "в бишкеке" "в оше" "bishkek" "osh"
    "побольше" "поменьше" "получше" "попроще"
    "без фото" "с фото" "с доставкой"})

(def comparison-re
  "Comparison requests — need LLM."
  #"(?i)(что\s+лучше|сравни|сравнени|разница\s+между|чем\s+отлича|что\s+выбрать|что\s+предпоч|лучше\s+взять)")

(def purchase-intent-re
  "Purchase/search intent — extracted from core.clj purchase-intent-pattern."
  #"(?i)(найди|ищ[уе]|купи[ть]|сколько\s+стоит|цена|в\s+продаже|покажи|хочу|ищу|надо|нужен|нужна|нужно|прода[ею]|до\s+\d+|от\s+\d+|б/?у|подерж|бу\b|нов[аы]я|планшет|айпад|ipad|ноут|телефон|айфо|iphone|samsung|xiaomi|макбук|пылесос|роутер|телевиз|монитор|наушник|мышк[аи]|клавиатур|видеокарт|процессор|холодильник|стирал|велосипед|самокат|hyundai|toyota|honda|bmw|mercedes|lexus|квартир|участ[ко])")

;; ══════════════════════ CLASSIFIER ══════════════════════

(defn classify
  "Classify user text into an intent keyword.
   Takes text (string) and session-state (map or nil).
   Returns one of: :greeting :reset :tracking :help :thanks
                   :refine :compare :search :unknown"
  [text session-state]
  (let [t (str/trim (or text ""))]
    (cond
      ;; Blank → unknown
      (str/blank? t)
      :unknown

      ;; Exact fast-path matches (short messages, high confidence)
      (re-find greeting-re t)    :greeting
      (re-find reset-re t)       :reset
      (re-find tracking-re t)    :tracking
      (re-find help-re t)        :help
      (re-find thanks-re t)      :thanks

      ;; Refine: short message + has session state + refine keyword
      (and session-state
           (< (count t) 30)
           (contains? refine-keywords (str/lower-case t)))
      :refine

      ;; Comparison
      (re-find comparison-re t)  :compare

      ;; Purchase / search
      (re-find purchase-intent-re t) :search

      ;; Fallback
      :else
      :unknown)))

;; ══════════════════════ DECISION HELPERS ══════════════════════

(defn should-search?
  "True if this intent should trigger a marketplace search."
  [intent]
  (contains? #{:search :refine} intent))

(defn needs-llm?
  "True if this intent requires LLM processing (not fully deterministic)."
  [intent]
  (contains? #{:unknown :compare :refine} intent))

(defn needs-reset?
  "True if session should be reset."
  [intent]
  (= intent :reset))

(defn is-greeting?
  "True if this is a greeting with no search intent."
  [intent]
  (= intent :greeting))
