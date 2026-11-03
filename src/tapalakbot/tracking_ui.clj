(ns tapalakbot.tracking-ui
  "Telegram controls for the same owned subscriptions exposed to the agent."
  (:require [clj-harness.telegram :as tg]
            [clojure.string :as str]
            [tapalakbot.bot-state :as state]
            [tapalakbot.core :as core]
            [tapalakbot.tracking :as tracking]))

(defn- owner [message]
  {:id (state/conversation-id message)
   :chat-id (:chat-id message) :thread-id (:thread-id message)})

(defn- send! [{:keys [chat-id thread-id]} html keyboard]
  (tg/send-rich-message chat-id :html html
                        :reply-markup keyboard :thread-id thread-id))

(defn- list-button []
  (tg/inline-keyboard [["📋 Подписки в этой беседе" {:callback_data "track_list"}]]))

(defn context-button [owner-id request]
  (when (and (seq (:user-want request)) (seq (:queries request)))
    (let [spec {:title (:user-want request) :queries (:queries request)
                :price-min (:price-min request) :price-max (:price-max request)
                :city-id (:city-id request) :excluded-ids (:excluded-ids request)
                :seed-ids (:pool-ids request)}
          token (state/cache-track-query! owner-id spec)]
      (tg/inline-keyboard
       [["🔔 Следить за этим поиском" {:callback_data (str "track_quick:" token)}]]))))

(defn- summary [track]
  (str "<b>#" (:id track) " · " (if (= 1 (:enabled track)) "Включено" "На паузе") "</b>\n"
       (tg/escape-html (:title track)) "\n"
       (when (or (:price_min track) (:price_max track))
         (str "Бюджет: " (when (:price_min track) (str "от " (:price_min track) " "))
              (when (:price_max track) (str "до " (:price_max track) " ")) "сом\n"))
       "Проверка каждые " (:notify_interval track) " ч."))

(defn show-list! [message]
  (let [{:keys [status tracks]} (tracking/manage! (owner message) :list {})]
    (cond
      (not= :ok status) (send! message "Не удалось загрузить отслеживание." nil)
      (empty? tracks) (send! message "В этой беседе нет подписок. Напишите, за каким товаром следить, или сначала найдите варианты." nil)
      :else
      (send! message
             (str "<b>Отслеживание в этой беседе</b>\n\n"
                  (str/join "\n\n" (map summary tracks)))
             (apply tg/inline-keyboard
                    (mapv (fn [track]
                            (let [id (:id track)]
                              [[(str "⚙ #" id) {:callback_data (str "track_settings:" id)}]
                               [(str (if (= 1 (:enabled track)) "⏸ Пауза #" "▶ Продолжить #") id)
                                {:callback_data (str (if (= 1 (:enabled track)) "track_pause:" "track_resume:") id)}]
                               [(str "Остановить #" id) {:callback_data (str "track_del_ask:" id)}]]))
                          tracks))))))

(defn- owned-track [destination id]
  (some #(when (= id (:id %)) %) (:tracks (tracking/manage! destination :list {}))))

(defn- show-settings! [message track]
  (let [id (:id track)]
    (send! message (str (summary track) "\n\nЧастоту можно менять и обычным сообщением.")
           (tg/inline-keyboard
            [["Каждые 3 часа" {:callback_data (str "track_set_freq:" id ":3")}]
             ["Каждые 24 часа" {:callback_data (str "track_set_freq:" id ":24")}]
             ["Каждые 72 часа" {:callback_data (str "track_set_freq:" id ":72")}]
             ["← Подписки" {:callback_data "track_list"}]]))))

(defn- report! [message result]
  (core/remember-tracking! (state/conversation-id message) result)
  (let [status (:status result)]
    (send! message
           (case status
             :created (str "<b>Отслеживание сохранено.</b> Новые подходящие объявления придут в эту беседу.\n\n"
                           (summary (:track result)))
             :existing "Такое отслеживание уже включено в этой беседе."
             :updated (str "Фильтр обновлён.\n\n" (summary (:track result)))
             :paused "Отслеживание на паузе. Уведомления не отправляются."
             :resumed "Отслеживание возобновлено."
             :stopped "Отслеживание остановлено и удалено."
             :not-found "Подписка не найдена в этой беседе."
             "Не удалось сохранить изменение отслеживания.")
           (list-button))))

(defn handle-callback!
  "Return true only for tracking callbacks; every action checks the current destination."
  [{:keys [data callback-id] :as message}]
  (let [quick (re-matches #"track_quick:(.+)" data)
        operation (re-matches #"(track_info|track_settings|track_freq|track_set_freq|track_pause|track_resume|track_del_ask|track_del_yes):(\d+)(?::(\d+))?" data)]
    (when (or (= data "track_list") quick operation)
      (tg/answer-callback-query callback-id)
      (let [destination (owner message)]
        (cond
          (= data "track_list") (show-list! message)
          quick
          (if-let [spec (state/take-track-query! (:id destination) (second quick))]
            (report! message (tracking/manage! destination :create spec))
            (send! message "Эта кнопка устарела или принадлежит другой беседе. Откройте исходный поиск или выполните новый." nil))
          :else
          (let [[_ action id interval] operation
                id (Long/parseLong id)
                track (owned-track destination id)]
            (if-not track
              (send! message "Подписка не найдена в этой беседе." nil)
              (case action
                ("track_info" "track_settings" "track_freq") (show-settings! message track)
                "track_del_ask"
                (send! message (str "Остановить отслеживание?\n\n" (tg/escape-html (:title track)))
                       (tg/inline-keyboard
                        [["Да, остановить" {:callback_data (str "track_del_yes:" id)}]
                         ["← Назад" {:callback_data "track_list"}]]))
                (report! message
                         (tracking/manage! destination
                                           (case action
                                             "track_pause" :pause "track_resume" :resume
                                             "track_del_yes" :stop :update)
                                           (cond-> {:id id}
                                             interval (assoc :notify-interval (Long/parseLong interval))))))))))
      true)))
