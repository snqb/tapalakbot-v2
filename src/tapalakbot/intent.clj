(ns tapalakbot.intent
  "LLM-powered intent classifier. Used when regex policy can't classify a message.
   Understands conversational intent: follow-up questions, research requests, chat."
  (:require [clj-harness.llm :as llm]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════ INTENT PROMPT ════════════════════

(def ^:private intent-prompt
  "You are an intent classifier for a marketplace Telegram bot (Lalafo.kg, Mashina.kg) in Kyrgyzstan.
The bot helps users find products. Classify the user message into ONE intent.

## Intent types
- search: User wants to find specific product listings. E.g. 'iphone 13', 'найди макбук', 'toyota camry', 'сколько стоит samsung'. The user knows what they want.
- research: User wants market intelligence, price guidance, or category exploration. E.g. 'хочу айфон', 'нужен велосипед для города', 'что купить для работы', 'посоветуй ноутбук', 'какие цены на машины'. They're exploring, not searching for a specific item.
- followup: User asks a question ABOUT previously shown results. E.g. 'which is better', 'а какой норм', 'покажи самый дешёвый', 'расскажи про второй', 'а что по ценам', 'это дорого?'. These reference items the bot already showed.
- compare: Explicit comparison between two specific products. E.g. 'что лучше iphone или samsung', 'сравни macbook и zenbook'
- refine: Narrowing/filtering a previous search. E.g. 'дешевле', 'только в бишкеке', 'только новые', 'до 30000', 'подороже'
- chat: Small talk, general conversation. E.g. 'спасибо', 'как дела', 'что ты умеешь', jokes, personal questions

## Context
Previous search: %s
Previous mode: %s
Items shown: %s
User message: %s

## Output
Return ONLY valid JSON (no markdown, no commentary):
{\"intent\":\"search|research|followup|compare|refine|chat\",\"query\":\"extracted search or refine query\",\"confidence\":0.9}

For search/refine: query is the search terms. For followup/chat: query can be the original message.
For research: query is the product/category the user is exploring.
For compare: query is the comparison text.")

(defn classify-intent
  "Classify user intent using LLM.
   text — user message string
   session-state — map with :last-search, :last-mode, :last-card-count keys (can be nil)
   returns {:intent :search/:research/:followup/:compare/:refine/:chat
             :query \"...\"
             :confidence 0.0-1.0}"
  [text session-state]
  (try
    (let [prompt (format intent-prompt
                         (or (:last-search session-state) "none")
                         (or (:last-mode session-state) "none")
                         (or (:last-card-count session-state) 0)
                         text)
          messages [{"role" "system" "content" prompt}
                    {"role" "user" "content" text}]
          resp (llm/llm :kimi-k2 messages [] :provider :openrouter
                        :max-tokens 200 :timeout-ms 15000)
          content (get-in resp ["choices" 0 "message" "content"])
          json-str (or (re-find #"(?s)\{.*\}" (or content "{}")) "{}")
          parsed (try (json/parse-string json-str true) (catch Exception _ {}))
          intent-str (or (:intent parsed) "search")
          ;; Normalize intent keyword
          intent (keyword (str/lower-case intent-str))]
      (log/info :llm-intent-classified :text text :intent intent)
      {:intent     (if (#{:search :research :followup :compare :refine :chat} intent)
                     intent
                     :search)
       :query      (or (:query parsed) text)
       :confidence (or (:confidence parsed) 0.5)})
    (catch Exception e
      (log/warn :intent-classifier-failed :text text :error (.getMessage e))
      ;; Fallback: if session exists, treat as research. Otherwise search.
      (if (:last-search session-state)
        {:intent :research :query text :confidence 0.3}
        {:intent :search :query text :confidence 0.3}))))
