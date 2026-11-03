(ns tapalakbot.tracking
  "Conversation-owned tracking service. No model or Telegram concerns belong here."
  (:require [clojure.string :as str]
            [tapalakbot.monitor.store :as store]
            [tapalakbot.monitor.subscriptions :as subscriptions]))

(def ^:private actions #{:create :list :update :pause :resume :stop})

(defn- safe-error [message]
  {:status :invalid :error message})

(defn- nonblank? [value]
  (and (string? value) (not (str/blank? value))))

(defn- valid-owner-id? [value]
  (or (integer? value) (nonblank? value)))

(defn- integer-string? [value pattern]
  (and (string? value) (re-matches pattern value)))

(defn- valid-chat-id? [value]
  (or (integer? value)
      (integer-string? value #"-?\d+")))

(defn- valid-thread-id? [value]
  (or (and (integer? value) (pos? value))
      (integer-string? value #"[1-9]\d*")))

(defn- normalize-owner [owner]
  (cond
    (not (map? owner)) (safe-error "owner is required")
    (not (valid-owner-id? (:id owner))) (safe-error "owner id is required")
    (not (valid-chat-id? (:chat-id owner)))
    (safe-error "chat destination is required")
    (and (some? (:thread-id owner))
         (not (valid-thread-id? (:thread-id owner))))
    (safe-error "thread destination is invalid")
    :else (try
            (assoc owner
                   :id (str (:id owner))
                   :chat-id (str (:chat-id owner))
                   :thread-id (when (some? (:thread-id owner))
                                (long (if (string? (:thread-id owner))
                                        (Long/parseLong (:thread-id owner))
                                        (:thread-id owner)))))
            (catch Exception _
              (safe-error "thread destination is invalid")))))

(defn- integer-value? [value]
  (or (integer? value)
      (and (string? value) (re-matches #"-?\d+" value))))

(defn- normalize-int [value]
  (when (some? value)
    (long (if (string? value) (Long/parseLong value) value))))

(defn- normalize-ids [value field]
  (cond
    (nil? value) []
    (and (sequential? value) (every? some? value)) (vec value)
    :else (throw (ex-info (str field " must be a list") {}))))

(defn- normalized-spec [spec]
  (when-not (map? spec)
    (throw (ex-info "spec is required" {})))
  (let [title (:title spec)
        queries (normalize-ids (:queries spec) :queries)
        min-price (:price-min spec)
        max-price (:price-max spec)
        city-id (:city-id spec)
        interval (if (contains? spec :notify-interval)
                   (:notify-interval spec)
                   24)]
    (when-not (nonblank? title)
      (throw (ex-info "title is required" {})))
    (when (or (empty? queries) (> (count queries) 3)
              (not (every? nonblank? queries)))
      (throw (ex-info "queries must contain one to three non-empty strings" {})))
    (when (or (and (some? min-price)
                   (or (not (integer-value? min-price))
                       (neg? (normalize-int min-price))))
              (and (some? max-price)
                   (or (not (integer-value? max-price))
                       (neg? (normalize-int max-price))))
              (and (some? city-id) (not (integer-value? city-id)))
              (not (integer-value? interval))
              (not (pos? (normalize-int interval))))
      (throw (ex-info "numeric tracking bounds are invalid" {})))
    (when (and (some? min-price) (some? max-price)
               (> (normalize-int min-price) (normalize-int max-price)))
      (throw (ex-info "price-min cannot exceed price-max" {})))
    {:title (str/trim title)
     :queries (mapv str queries)
     :price-min (normalize-int min-price)
     :price-max (normalize-int max-price)
     :city-id (normalize-int city-id)
     :notify-interval (normalize-int interval)
     :excluded-ids (normalize-ids (:excluded-ids spec) :excluded-ids)
     :seed-ids (normalize-ids (:seed-ids spec) :seed-ids)}))

(defn- track-result [status row]
  {:status status :track row})

(defn- owner-track [owner spec]
  (subscriptions/get-managed-track owner (:id spec)))

(defn- merged-fields [row spec]
  {:title (if (contains? spec :title) (:title spec) (:title row))
   :queries (if (contains? spec :queries)
              (:queries spec)
              (store/parse-track-queries (:queries row)))
   :price-min (if (contains? spec :price-min) (:price-min spec) (:price_min row))
   :price-max (if (contains? spec :price-max) (:price-max spec) (:price_max row))
   :city-id (if (contains? spec :city-id) (:city-id spec) (:city_id row))
   :notify-interval (if (contains? spec :notify-interval)
                       (:notify-interval spec) (or (:notify_interval row) 24))
   :excluded-ids (if (contains? spec :excluded-ids)
                   (:excluded-ids spec)
                   (store/parse-track-excluded-ids (:excluded_ids row)))})

(defn- validate-update [row spec]
  (let [candidate (merged-fields row spec)]
    (normalized-spec (assoc candidate :seed-ids []))))

(defn- manage-create [owner spec]
  (let [fields (normalized-spec spec)
        result (subscriptions/create-managed-track!
                (assoc fields :user-id (:id owner) :chat-id (:chat-id owner)
                       :thread-id (:thread-id owner))
                (:seed-ids fields))]
    (track-result (if (:created? result) :created :existing) (:track result))))

(defn- manage-list [owner]
  {:status :ok :tracks (subscriptions/get-managed-tracks owner)})

(defn- manage-update [owner spec]
  (let [row (owner-track owner spec)]
    (if-not row
      {:status :not-found :error "tracking subscription not found"}
      (track-result :updated
                    (subscriptions/update-managed-track! owner (:id spec)
                                                          (validate-update row spec))))))

(defn- manage-enable [owner spec enabled? status]
  (let [row (owner-track owner spec)]
    (if-not row
      {:status :not-found :error "tracking subscription not found"}
      (track-result status (subscriptions/set-managed-enabled! owner (:id spec) enabled?)))))

(defn- manage-stop [owner spec]
  (let [row (subscriptions/delete-managed-track! owner (:id spec))]
    (if row
      (track-result :stopped row)
      {:status :not-found :error "tracking subscription not found"})))

(defn manage!
  "Manage a conversation-owned subscription.
   Results are {:status keyword ...} with safe, non-model errors." 
  [owner action spec]
  (let [owner* (normalize-owner owner)]
    (if (= :invalid (:status owner*))
      owner*
      (try
        (if-not (contains? actions action)
          (safe-error "unknown tracking action")
          (case action
            :create (manage-create owner* spec)
            :list (manage-list owner*)
            :update (manage-update owner* spec)
            :pause (manage-enable owner* spec false :paused)
            :resume (manage-enable owner* spec true :resumed)
            :stop (manage-stop owner* spec)))
        (catch clojure.lang.ExceptionInfo e
          (safe-error (.getMessage e)))
        (catch Exception _
          {:status :error :error "tracking operation failed"})))))
