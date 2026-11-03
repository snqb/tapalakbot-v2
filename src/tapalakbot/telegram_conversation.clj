(ns tapalakbot.telegram-conversation
  "Per-turn agent streaming and deterministic result delivery to an immutable topic."
  (:require [clj-harness.telegram :as tg]
            [clj-harness.telegram.format :as hfmt]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [tapalakbot.bot-state :as state]
            [tapalakbot.core :as core]
            [tapalakbot.result-delivery :as delivery]
            [tapalakbot.result-order :as result-order]
            [tapalakbot.result-store :as result-store]
            [tapalakbot.tracking-ui :as tracking-ui]))

(defn ensure-topic!
  ([chat-id uid] (ensure-topic! chat-id uid "Новый диалог"))
  ([chat-id uid topic-name]
   (when-let [envelope (try (tg/create-forum-topic chat-id topic-name :icon-color 0x6FB9F0)
                            (catch Exception e
                              (log/info :topic-create-skipped :chat chat-id :reason (.getMessage e))
                              nil))]
     (let [result (get envelope "result" envelope)
           thread-id (get result "message_thread_id")]
       (log/info :topic-created :user uid :thread-id thread-id :name topic-name)
       thread-id))))

(defn- rename-topic! [chat-id thread-id query]
  (when thread-id
    (let [name (str/trim (subs query 0 (min 60 (count query))))]
      (try (tg/edit-forum-topic chat-id thread-id :name name)
           (catch Exception e (log/warn e :topic-rename-failed :thread-id thread-id))))))

(defn- send-typing! [chat-id thread-id]
  ;; The pinned helper has no topic argument: never leak the action to General.
  (when-not thread-id (tg/send-typing chat-id)))

(defn- safe-agent-text [text pool sources]
  (if (or (seq pool) (seq sources))
    (let [valid-urls (set (concat (keep :url pool) (keep :url sources)))]
      (str/replace (or text "") #"\[([^\]]*)\]\(([^)]+)\)"
                   (fn [[_ label url]]
                     (if (valid-urls url)
                       (str "[" label "](" url ")")
                       (str "⚠️ " label " (ссылка недоступна)")))))
    text))

(defn handle-message!
  [{:keys [chat-id user-id text thread-id] :as message}]
  (let [user-uid (str "tg-" user-id)
        is-dm? (pos? chat-id)
        thread-id (if is-dm? thread-id
                      (or thread-id (ensure-topic! chat-id user-uid
                                                  (str "🔍 " (subs text 0 (min 30 (count text)))))))
        session-id (state/conversation-id (assoc message :thread-id thread-id))
        buf (StringBuilder.)
        last-edit (atom 0)
        last-typing (atom 0)
        draft-id (when is-dm? (long (+ (System/currentTimeMillis) (rand-int 10000))))
        status-msg (atom nil)
        send-draft! (fn [preview]
                      (let [now (System/currentTimeMillis)]
                        (when (> (- now @last-edit) 800)
                          (reset! last-edit now)
                          (try (tg/send-rich-message-draft chat-id draft-id :markdown preview :thread-id thread-id)
                               (catch Exception _)))))
        show-status! (fn [status-text]
                       (if-let [mid (get @status-msg "message_id")]
                         (try (tg/edit-message chat-id mid status-text :parse-mode nil :thread-id thread-id)
                              (catch Exception _))
                         (reset! status-msg
                                 (try (tg/send-message chat-id status-text :parse-mode nil :thread-id thread-id)
                                      (catch Exception _)))))
        stream-cb (fn [delta]
                    (.append buf delta)
                    (let [now (System/currentTimeMillis)]
                      (when (> (- now @last-typing) 3000)
                        (reset! last-typing now)
                        (try (send-typing! chat-id thread-id) (catch Exception _)))
                      (let [preview (hfmt/streaming-preview
                                     (subs (.toString buf) 0 (min (.length buf) 2000)))]
                        (when-not (str/blank? preview)
                          (if is-dm? (send-draft! preview)
                              (when (> (- now @last-edit) 1500)
                                (reset! last-edit now)
                                (show-status! (hfmt/strip-md preview))))))))
        status-cb (fn [status]
                    (when status (if is-dm? (send-draft! status) (show-status! status))))]
    (if is-dm?
      (try (send-typing! chat-id thread-id) (catch Exception _))
      (show-status! "Разбираюсь в запросе…"))
    (try
      (let [city-id @(:city-id (state/get-user-state user-uid))
            result (core/ask-stream session-id text status-cb
                                    (merge {:stream-cb stream-cb :city-id city-id
                                            :chat-id chat-id :thread-id thread-id
                                            :search-status-cb status-cb}
                                           (select-keys message [:price-min :price-max])))
            query (or (:query result) text)
            sort-mode (or (:sort-mode result) result-order/default-mode)
            pool (result-order/order-cards (vec (or (seq (:pool result)) (:cards result) []))
                                            sort-mode)
            cards (vec (take result-store/page-size pool))
            context {:query query :cards pool :selection (:selection result)
                     :collection (:collection result) :sort-mode sort-mode
                     :cache-created-at (:cache-created-at result)}
            cursor (when (seq cards)
                     (result-store/cache-result-pool! session-id query pool (count cards) context))
            keyboard (when cursor
                       (delivery/results-keyboard query cursor (> (count pool) (count cards))
                                                  session-id context))
            reply {:mode (if (seq cards) :shortlist :no-results)
                   :intro (or (safe-agent-text (:text result) pool
                                               (get-in result [:request :advice :sources])) "")
                   :cards cards :selection (:selection result) :collection (:collection result)
                   :pool-count (count pool) :sort-mode sort-mode
                   :cta nil :assumptions []}]
        (when-let [mid (get @status-msg "message_id")]
          (try (tg/delete-message chat-id mid) (catch Exception _)))
        (when (seq pool) (result-store/cache-ads! session-id pool))
        (delivery/send-reply! chat-id reply {:keyboard keyboard :thread-id thread-id})
        (when (seq cards) (core/remember-view! session-id cards))
        ;; A clarification or subscription operation must not rename a purchase topic.
        (when (and (:query result) (= :ok (:search-status result)))
          (rename-topic! chat-id thread-id (:query result))
          (when-let [button (tracking-ui/context-button session-id (:request result))]
            (tg/send-message chat-id "Новые подходящие объявления можно получать в этой беседе."
                             :reply_markup button :thread-id thread-id)))
        (log/info :conversation-delivered :session-id session-id
                  :cards (count cards) :pool (count pool) :search-status (:search-status result)))
      (catch Exception e
        (log/error e :agent-error :session-id session-id)
        (when-let [mid (get @status-msg "message_id")]
          (try (tg/delete-message chat-id mid) (catch Exception _)))
        (try (tg/send-message chat-id "Не удалось обработать запрос. Попробуйте позже."
                              :parse-mode nil :thread-id thread-id)
             (catch Exception _))))))
