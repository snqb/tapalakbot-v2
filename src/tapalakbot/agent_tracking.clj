(ns tapalakbot.agent-tracking
  "Agent-facing tracking operations; identity and destinations come only from transport."
  (:require [cheshire.core :as json]
            [tapalakbot.conversation :as conversation]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.tracking :as tracking]))

(def schema
  [:map
   [:action [:enum "create" "list" "update" "pause" "resume" "stop"]]
   [:id {:optional true} :int]
   [:user_want {:optional true} :string]
   [:queries {:optional true} [:vector :string]]
   [:price_min {:optional true} [:maybe :int]]
   [:price_max {:optional true} [:maybe :int]]
   [:city_id {:optional true} [:maybe :int]]
   [:notify_interval {:optional true} :int]
   [:new_request {:optional true} :boolean]])

(defn- safe-track [row]
  (assoc (select-keys row [:id :title :price_min :price_max :city_id
                           :notify_interval :enabled :notify_count])
         :queries (store/parse-track-queries (:queries row))))

(defn context-text [owner]
  (when (:chat-id owner)
    (let [result (tracking/manage! owner :list {})]
      (when (= :ok (:status result))
        (str "\nРЕАЛЬНО СОХРАНЁННОЕ ОТСЛЕЖИВАНИЕ В ЭТОЙ БЕСЕДЕ\n"
             (json/generate-string (mapv safe-track (:tracks result)))
             "\nДля изменения фильтра обновляй полный user_want вместе с новыми границами. "
             "enabled=0 означает паузу. Не обещай действие без подтверждения инструмента.")))))

(def ^:private argument-fields
  {"user_want" :title "queries" :queries
   "price_min" :price-min "price_max" :price-max "city_id" :city-id
   "notify_interval" :notify-interval})

(defn- supplied-fields [args]
  (reduce-kv (fn [fields argument field]
               (if (contains? args argument)
                 (assoc fields field (get args argument))
                 fields))
             {} argument-fields))

(defn- creation-spec [request args]
  (let [request (when-not (true? (get args "new_request")) request)]
    (merge {:title (:user-want request) :queries (:queries request)
            :price-min (:price-min request) :price-max (:price-max request)
            :city-id (:city-id request) :excluded-ids (vec (:excluded-ids request))
            :seed-ids (vec (:pool-ids request))}
           (supplied-fields args))))

(defn- remembered-request [track]
  {:user-want (:title track)
   :queries (store/parse-track-queries (:queries track))
   :price-min (:price_min track) :price-max (:price_max track)
   :city-id (:city_id track)
   :excluded-ids (store/parse-track-excluded-ids (:excluded_ids track))})

(defn execute
  "The model chooses the action/spec, never the owner or notification destination."
  [session owner args]
  (let [action (keyword (get args "action" ""))
        request (conversation/current session)
        id (or (get args "id") (:tracking-id request)
               (when-not (contains? #{:create :list} action)
                 (let [tracks (:tracks (tracking/manage! owner :list {}))]
                   (when (= 1 (count tracks)) (:id (first tracks))))))
        spec (if (= :create action)
               (creation-spec request args)
               (assoc (supplied-fields args) :id id))
        result (if (and (contains? #{:update :pause :resume :stop} action) (nil? id))
                 {:status :ambiguous :error "Уточните, какое отслеживание изменить."}
                 (tracking/manage! owner action spec))
        track (:track result)]
    (when (and session track
               (or (= :create action) (= (:tracking-id request) (:id track)))
               (contains? #{:created :existing :updated :paused :resumed :stopped} (:status result)))
      (conversation/remember-tracking!
       session (if (contains? #{:create :update} action) (remembered-request track) {})
       (when-not (= :stopped (:status result)) (:id track))
       (and (not= :stopped (:status result)) (= 1 (:enabled track)))
       (and (= :create action) (true? (get args "new_request")))))
    (cond-> (select-keys result [:status :error])
      track (assoc :track (safe-track track))
      (:tracks result) (assoc :tracks (mapv safe-track (:tracks result))))))
