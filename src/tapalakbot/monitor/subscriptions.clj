(ns tapalakbot.monitor.subscriptions
  "Conversation-owned persistent tracking subscriptions."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [tapalakbot.monitor.store :as store]))

(defn- query! [db sql-params]
  (jdbc/execute! db sql-params {:builder-fn rs/as-unqualified-maps}))

(defn- query-one! [db sql-params]
  (jdbc/execute-one! db sql-params {:builder-fn rs/as-unqualified-maps}))

(defn- legacy-user-id [owner]
  (let [chat-id (str (:chat-id owner))
        legacy-id (str "tg-" chat-id)]
    (when (and (nil? (:thread-id owner))
               (re-matches #"[1-9]\d*" chat-id)
               (contains? #{legacy-id (str legacy-id ":" chat-id)} (:id owner)))
      legacy-id)))

(defn- owner-where [owner]
  (let [base "user_id = ? AND chat_id = ? AND
              ((thread_id = ?) OR (thread_id IS NULL AND ? IS NULL))"
        params [(:id owner) (str (:chat-id owner))
                (:thread-id owner) (:thread-id owner)]
        legacy-id (legacy-user-id owner)]
    (if legacy-id
      (into [(str "((" base ") OR
                    (user_id = ? AND chat_id IS NULL AND thread_id IS NULL))")]
            (conj params legacy-id))
      (into [(str "(" base ")")] params))))

(defn get-managed-tracks [owner]
  (let [[where & params] (owner-where owner)]
    (query! (store/get-datasource)
            (into [(str "SELECT * FROM user_tracks WHERE " where
                        " ORDER BY created_at DESC, id DESC")]
                  params))))

(defn get-managed-track [owner track-id]
  (let [[where & params] (owner-where owner)]
    (query-one! (store/get-datasource)
                (into [(str "SELECT * FROM user_tracks WHERE id = ? AND " where)]
                      (into [track-id] params)))))

(defn create-managed-track!
  [{:keys [user-id chat-id thread-id title queries price-min price-max city-id
           notify-interval excluded-ids]}
   seed-ids]
  (jdbc/with-transaction [tx (store/get-datasource)]
    (let [same? "user_id = ? AND chat_id = ? AND
                 ((thread_id = ?) OR (thread_id IS NULL AND ? IS NULL)) AND
                 title = ? AND queries = ? AND
                 ((price_min = ?) OR (price_min IS NULL AND ? IS NULL)) AND
                 ((price_max = ?) OR (price_max IS NULL AND ? IS NULL)) AND
                 ((city_id = ?) OR (city_id IS NULL AND ? IS NULL)) AND
                 excluded_ids = ? AND enabled = 1"
          existing (query-one! tx
                                [(str "SELECT * FROM user_tracks WHERE " same?)
                                 user-id (str chat-id) thread-id thread-id title
                                 (pr-str queries) price-min price-min price-max
                                 price-max city-id city-id
                                 (pr-str (vec excluded-ids))])]
      (if existing
        {:created? false :track existing}
        (let [row (query-one! tx
                              ["INSERT INTO user_tracks
                                (user_id, title, queries, price_min, price_max,
                                 city_id, notify_interval, chat_id, thread_id,
                                 excluded_ids)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                RETURNING *"
                               user-id title (pr-str queries) price-min price-max
                               city-id notify-interval (str chat-id) thread-id
                               (pr-str (vec excluded-ids))])]
          (doseq [item-id (distinct seed-ids)]
            (query! tx
                    ["INSERT OR IGNORE INTO track_seen_items (track_id, item_id)
                      VALUES (?, ?)" (:id row) item-id]))
          {:created? true :track row})))))

(defn update-managed-track!
  [owner track-id fields]
  (jdbc/with-transaction [tx (store/get-datasource)]
    (let [[where & params] (owner-where owner)
          prior (query-one! tx
                            (into [(str "SELECT * FROM user_tracks WHERE id = ? AND " where)]
                                  (into [track-id] params)))
          title-changed? (and prior (not= (:title prior) (:title fields)))
          queries-changed? (and prior
                                (not= (store/parse-track-queries (:queries prior))
                                      (:queries fields)))
          semantic-changed? (or title-changed? queries-changed?)
          changed? (and prior
                        (or semantic-changed?
                            (not= (:price_min prior) (:price-min fields))
                            (not= (:price_max prior) (:price-max fields))
                            (not= (:city_id prior) (:city-id fields))
                            (not= (store/parse-track-excluded-ids (:excluded_ids prior))
                                  (:excluded-ids fields))))
          row (when prior
                (query-one! tx
                            (into [(str "UPDATE user_tracks SET title = ?,
                                         queries = ?, price_min = ?, price_max = ?,
                                         city_id = ?, category_id = ?,
                                         notify_interval = ?,
                                         excluded_ids = ?,
                                         last_checked_at = CASE WHEN ? THEN NULL ELSE last_checked_at END
                                         WHERE id = ? AND " where
                                         " RETURNING *")
                                   (:title fields) (pr-str (:queries fields))
                                   (:price-min fields) (:price-max fields)
                                   (:city-id fields)
                                   (when-not semantic-changed? (:category_id prior))
                                   (:notify-interval fields)
                                   (pr-str (vec (:excluded-ids fields)))
                                   (if changed? 1 0) track-id]
                                  params)))]
      (when changed?
        (query! tx ["DELETE FROM track_seen_items
                    WHERE track_id = ? AND rejected IN (1, -1)"
                    track-id]))
      row)))

(defn set-managed-enabled! [owner track-id enabled?]
  (let [[where & params] (owner-where owner)]
    (query-one! (store/get-datasource)
                (into [(str "UPDATE user_tracks SET enabled = ? WHERE id = ? AND "
                            where " RETURNING *")
                       (if enabled? 1 0) track-id]
                      params))))

(defn delete-managed-track! [owner track-id]
  (jdbc/with-transaction [tx (store/get-datasource)]
    (let [[where & params] (owner-where owner)
          row (query-one! tx
                          (into [(str "SELECT * FROM user_tracks WHERE id = ? AND "
                                      where)]
                                (into [track-id] params)))]
      (when row
        (query! tx ["DELETE FROM track_seen_items WHERE track_id = ?" track-id])
        (query! tx ["DELETE FROM user_tracks WHERE id = ?" track-id]))
      row)))
