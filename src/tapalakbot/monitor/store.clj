(ns tapalakbot.monitor.store
  "SQLite storage for Lalafo price monitoring.
   Tables:
     monitor_categories — what we track (id, name, queries, city_id, enabled)
     monitor_items     — individual listings (id, category_id, title, url, current_price, last_seen)
     monitor_snapshots — price history (id, item_id, price, scanned_at)
     user_tracks       — user tracking filters (id, user_id, title, queries, price_max, ...)
     track_seen_items  — items already notified per track (track_id, item_id)"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ DB ════════════════════════════

(def ^:private db-path (or (System/getenv "MONITOR_DB_PATH")
                           "/tmp/tapalakbot-monitor.db"))

(defonce ^:private ds (atom nil))

(declare init-db!)

(defn- get-ds []
  (or @ds (init-db!)))

(defn get-datasource
  "Public accessor for the JDBC datasource."
  []
  (get-ds))

;; next.jdbc returns qualified keywords by default (:table/col).
;; We use unqualified maps throughout for simpler access.
(defn- q! [sql-params]
  (jdbc/execute! (get-ds) sql-params {:builder-fn rs/as-unqualified-maps}))

(defn- q1! [sql-params]
  (jdbc/execute-one! (get-ds) sql-params {:builder-fn rs/as-unqualified-maps}))

(defn init-db!
  "Initialize SQLite database and create tables."
  []
  (let [d (jdbc/get-datasource (str "jdbc:sqlite:" db-path))]
    (reset! ds d)
    (jdbc/execute! d ["CREATE TABLE IF NOT EXISTS monitor_categories (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        queries TEXT NOT NULL,
        city_id INTEGER DEFAULT 103184,
        enabled INTEGER DEFAULT 1,
        created_at TEXT DEFAULT (datetime('now'))
      )"])
    (jdbc/execute! d ["CREATE TABLE IF NOT EXISTS monitor_items (
        id INTEGER PRIMARY KEY,
        category_id INTEGER NOT NULL,
        title TEXT,
        url TEXT,
        current_price REAL,
        currency TEXT DEFAULT 'KGS',
        image_url TEXT,
        city TEXT,
        first_seen TEXT DEFAULT (datetime('now')),
        last_seen TEXT DEFAULT (datetime('now')),
        FOREIGN KEY (category_id) REFERENCES monitor_categories(id)
      )"])
    (jdbc/execute! d ["CREATE TABLE IF NOT EXISTS monitor_snapshots (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_id INTEGER NOT NULL,
        price REAL NOT NULL,
        scanned_at TEXT DEFAULT (datetime('now')),
        FOREIGN KEY (item_id) REFERENCES monitor_items(id)
      )"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_snapshots_item ON monitor_snapshots(item_id)"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_snapshots_time ON monitor_snapshots(scanned_at)"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_items_category ON monitor_items(category_id)"])
    ;; Tracking tables
    (jdbc/execute! d ["CREATE TABLE IF NOT EXISTS user_tracks (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_id TEXT NOT NULL,
        title TEXT NOT NULL,
        queries TEXT NOT NULL,
        price_min INTEGER,
        price_max INTEGER,
        city_id INTEGER DEFAULT 103184,
        enabled INTEGER DEFAULT 1,
        created_at TEXT DEFAULT (datetime('now')),
        last_checked_at TEXT,
        notify_count INTEGER DEFAULT 0,
        notify_interval INTEGER DEFAULT 24
      )"])
    ;; Migration: add notify_interval if missing.
    ;; Throws "duplicate column" on every restart after the first — benign,
    ;; SQLite has no ADD COLUMN IF NOT EXISTS. Intentionally swallowed.
    (try (jdbc/execute! d ["ALTER TABLE user_tracks ADD COLUMN notify_interval INTEGER DEFAULT 24"])
         (catch Exception _))
    ;; Migration: add category_id if missing (same idempotency note as above).
    (try (jdbc/execute! d ["ALTER TABLE user_tracks ADD COLUMN category_id INTEGER"])
         (catch Exception _))
    (jdbc/execute! d ["CREATE TABLE IF NOT EXISTS track_seen_items (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        track_id INTEGER NOT NULL REFERENCES user_tracks(id) ON DELETE CASCADE,
        item_id INTEGER NOT NULL,
        notified_at TEXT DEFAULT (datetime('now')),
        UNIQUE(track_id, item_id)
      )"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_tracks_user ON user_tracks(user_id)"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_seen_track ON track_seen_items(track_id)"])
    (log/info :monitor-db-init :path db-path)
    d))

;; ════════════════════════════ CATEGORIES ════════════════════════════

(def default-categories
  [{:name "iPhone"
    :queries ["iPhone 13" "iPhone 14" "iPhone 15" "iPhone 12" "айфон 13" "айфон 14"]
    :city_id 103184}
   {:name "Samsung"
    :queries ["Samsung Galaxy S24" "Samsung S23" "Samsung A54" "Samsung Galaxy" "самсунг"]
    :city_id 103184}
   {:name "MacBook"
    :queries ["MacBook Air" "MacBook Pro" "макбук" "Macbook"]
    :city_id 103184}
   {:name "iPad"
    :queries ["iPad" "iPad Air" "iPad Pro" "айпад" "iPad Mini"]
    :city_id 103184}
   {:name "Наушники"
    :queries ["AirPods" "наушники AirPods" "AirPods Pro" "Sony WH" "Наушники Apple"]
    :city_id 103184}
   {:name "PlayStation"
    :queries ["PlayStation 5" "PS5" "PlayStation 4" "PS4" "приставка"]
    :city_id 103184}
   {:name "Ноутбуки"
    :queries ["ноутбук" "laptop" "ThinkPad" "ASUS" "HP Pavilion" "Acer"]
    :city_id 103184}
   {:name "Видеокарты"
    :queries ["RTX 4060" "RTX 4070" "RTX 3060" "видеокарта" "GTX 1660"]
    :city_id 103184}
   {:name "Велосипеды"
    :queries ["велосипед" "bicycle" "mountain bike" "велосипед горный"]
    :city_id 103184}
   {:name "Телевизоры"
    :queries ["Телевизор Samsung" "Телевизор LG" "TV 55" "Smart TV" "телевизор"]
    :city_id 103184}])

(defn seed-categories!
  "Insert default categories if table is empty."
  []
  (let [cat-count (:c (q1! ["SELECT COUNT(*) as c FROM monitor_categories"]))]
    (when (zero? cat-count)
      (doseq [cat default-categories]
        (q! ["INSERT INTO monitor_categories (name, queries, city_id) VALUES (?, ?, ?)"
             (:name cat)
             (pr-str (:queries cat))
             (:city_id cat)]))
      (log/info :categories-seeded :count (count default-categories)))))

(defn get-categories
  "Get all enabled categories."
  []
  (q! ["SELECT * FROM monitor_categories WHERE enabled = 1"]))

;; ════════════════════════════ ITEMS ════════════════════════════

(defn upsert-item!
  "Insert or update an item."
  [{:keys [id category_id title url price currency image_url city]}]
  (q! ["INSERT INTO monitor_items (id, category_id, title, url, current_price, currency, image_url, city, last_seen)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
        ON CONFLICT(id) DO UPDATE SET
          current_price = excluded.current_price,
          title = excluded.title,
          last_seen = datetime('now'),
          image_url = COALESCE(excluded.image_url, monitor_items.image_url)"
       id category_id title url price (or currency "KGS") image_url city]))

(def ^:private max-price 500000)

(defn get-items-by-category
  "Get all items for a category, ordered by price."
  [category-id]
  (q! ["SELECT i.*, c.name as category_name
        FROM monitor_items i
        JOIN monitor_categories c ON i.category_id = c.id
        WHERE i.category_id = ? AND i.current_price > 0 AND i.current_price < ?
        ORDER BY i.current_price ASC"
       category-id max-price]))

(defn get-item
  "Get a single item by ID."
  [item-id]
  (q1! ["SELECT i.*, c.name as category_name
         FROM monitor_items i
         JOIN monitor_categories c ON i.category_id = c.id
         WHERE i.id = ?"
        item-id]))

;; ════════════════════════════ SNAPSHOTS ════════════════════════════

(defn record-snapshots-batch!
  "Record multiple price snapshots in a single transaction."
  [snapshots]
  (when (seq snapshots)
    (jdbc/execute-batch! (get-ds)
                         "INSERT INTO monitor_snapshots (item_id, price) VALUES (?, ?)"
                         (mapv (fn [[item-id price]] [item-id price]) snapshots)
                         {:builder-fn rs/as-unqualified-maps
                          :batch-size 500})))

;; ════════════════════════════ QUERIES ════════════════════════════

(defn get-trending
  "Get latest prices for all categories — top items per category.
   Returns map of category-name → [items]."
  []
  (let [cats (get-categories)]
    (into {}
          (map (fn [cat]
                 [(:name cat)
                  (q! ["SELECT i.*, c.name as category_name,
                              (SELECT COUNT(*) FROM monitor_snapshots s WHERE s.item_id = i.id) as snapshot_count,
                              (SELECT MIN(s.price) FROM monitor_snapshots s WHERE s.item_id = i.id) as min_price,
                              (SELECT MAX(s.price) FROM monitor_snapshots s WHERE s.item_id = i.id) as max_price
                        FROM monitor_items i
                        JOIN monitor_categories c ON i.category_id = c.id
                        WHERE i.category_id = ? AND i.current_price > 0 AND i.current_price < ?
                        ORDER BY i.current_price ASC
                        LIMIT 10"
                       (:id cat) max-price])])
               cats))))

(defn get-deals
  "Find items priced below their average (good deals).
   Threshold: price < avg * 0.8 (20% below average)."
  []
  (q! ["WITH item_stats AS (
          SELECT item_id, AVG(price) as avg_price, MIN(price) as min_price, COUNT(*) as samples
          FROM monitor_snapshots GROUP BY item_id HAVING COUNT(*) >= 2
        )
        SELECT i.*, c.name as category_name,
               s.avg_price, s.min_price, s.samples,
               ROUND((1.0 - (i.current_price / s.avg_price)) * 100, 1) as discount_pct
        FROM item_stats s
        JOIN monitor_items i ON i.id = s.item_id
        JOIN monitor_categories c ON i.category_id = c.id
        WHERE i.current_price < s.avg_price * 0.8 AND i.current_price > 0 AND i.current_price < ?
        ORDER BY discount_pct DESC LIMIT 20"
       max-price]))

(defn get-history
  "Get price history for a specific item."
  [item-id]
  (q! ["SELECT s.price, s.scanned_at FROM monitor_snapshots s
        WHERE s.item_id = ? ORDER BY s.scanned_at ASC"
       item-id]))

(defn get-history-by-category
  "Get price history for all items in a category, aggregated by day."
  [category-id]
  (q! ["SELECT DATE(s.scanned_at) as day, AVG(s.price) as avg_price,
               MIN(s.price) as min_price, MAX(s.price) as max_price,
               COUNT(DISTINCT s.item_id) as items
        FROM monitor_snapshots s
        JOIN monitor_items i ON i.id = s.item_id
        WHERE i.category_id = ?
        GROUP BY DATE(s.scanned_at) ORDER BY day DESC LIMIT 30"
       category-id]))

(defn get-stats
  "Get overall monitoring stats."
  []
  {:categories (:c (q1! ["SELECT COUNT(*) as c FROM monitor_categories WHERE enabled = 1"]))
   :items (:c (q1! ["SELECT COUNT(*) as c FROM monitor_items"]))
   :snapshots (:c (q1! ["SELECT COUNT(*) as c FROM monitor_snapshots"]))
   :last-scan (:t (q1! ["SELECT MAX(scanned_at) as t FROM monitor_snapshots"]))
   :price-range (q1! ["SELECT MIN(current_price) as min_price,
                              MAX(current_price) as max_price,
                              AVG(current_price) as avg_price
                       FROM monitor_items WHERE current_price > 0 AND current_price < ?"
                      max-price])})

(defn get-category-summary
  "Get summary stats for each category."
  []
  (q! ["SELECT c.id, c.name,
               COUNT(DISTINCT i.id) as item_count,
               AVG(i.current_price) as avg_price,
               MIN(i.current_price) as min_price,
               MAX(i.current_price) as max_price,
               COUNT(DISTINCT s.id) as snapshot_count
        FROM monitor_categories c
        LEFT JOIN monitor_items i ON i.category_id = c.id AND i.current_price > 0 AND i.current_price < ?
        LEFT JOIN monitor_snapshots s ON s.item_id = i.id
        WHERE c.enabled = 1
        GROUP BY c.id ORDER BY item_count DESC"
       max-price]))

(defn cleanup-old-snapshots!
  "Remove snapshots older than N days."
  [days]
  (q! ["DELETE FROM monitor_snapshots WHERE scanned_at < datetime('now', ?)"
       (str "-" days " days")]))

;; ══════════════════════ USER TRACKS ══════════════════════

(defn create-track!
  "Create a tracking filter. Returns the new track record."
  [{:keys [user-id title queries price-min price-max city-id notify-interval category-id]
    :or {city-id 103184 notify-interval 24}}]
  (q1! ["INSERT INTO user_tracks (user_id, title, queries, price_min, price_max, city_id, notify_interval, category_id)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         RETURNING *"
        user-id title (pr-str queries) price-min price-max city-id notify-interval category-id]))

(defn update-track-interval!
  "Update notify_interval for a track."
  [track-id interval-hours]
  (q! ["UPDATE user_tracks SET notify_interval = ? WHERE id = ?" interval-hours track-id]))

(defn update-track-enabled!
  "Enable or disable a track."
  [track-id enabled?]
  (q! ["UPDATE user_tracks SET enabled = ? WHERE id = ?" (if enabled? 1 0) track-id]))

(defn get-user-tracks
  "Get all enabled tracks for a user."
  [user-id]
  (q! ["SELECT * FROM user_tracks WHERE user_id = ? AND enabled = 1 ORDER BY created_at DESC"
       user-id]))

(defn get-track
  "Get a single track by ID."
  [track-id]
  (q1! ["SELECT * FROM user_tracks WHERE id = ?" track-id]))

(defn delete-track!
  "Delete a track and its seen items."
  [track-id]
  (q! ["DELETE FROM track_seen_items WHERE track_id = ?" track-id])
  (q! ["DELETE FROM user_tracks WHERE id = ?" track-id])
  true)

(defn delete-user-tracks!
  "Delete all tracks for a user."
  [user-id]
  (let [tracks (get-user-tracks user-id)
        ids (mapv :id tracks)]
    (when (seq ids)
      (doseq [id ids]
        (q! ["DELETE FROM track_seen_items WHERE track_id = ?" id]))
      (q! (into [(str "DELETE FROM user_tracks WHERE id IN ("
                      (str/join "," (repeat (count ids) "?")) ")")]
                ids)))
    (count ids)))

(defn mark-track-checked!
  "Update last_checked_at for a track."
  [track-id]
  (q! ["UPDATE user_tracks SET last_checked_at = datetime('now') WHERE id = ?" track-id]))

(defn increment-notify-count!
  "Increment notify_count for a track."
  [track-id]
  (q! ["UPDATE user_tracks SET notify_count = notify_count + 1 WHERE id = ?" track-id]))

(defn seen-item?
  "Check if an item has already been notified for a track."
  [track-id item-id]
  (let [r (q1! ["SELECT 1 FROM track_seen_items WHERE track_id = ? AND item_id = ?"
                track-id item-id])]
    (boolean r)))

(defn mark-item-seen!
  "Record that an item was notified for a track."
  [track-id item-id]
  (q! ["INSERT OR IGNORE INTO track_seen_items (track_id, item_id) VALUES (?, ?)"
       track-id item-id]))

(defn get-all-active-tracks
  "Get all enabled tracks across all users (for background checker)."
  []
  (q! ["SELECT * FROM user_tracks WHERE enabled = 1 ORDER BY last_checked_at ASC NULLS FIRST"]))

(defn parse-track-queries
  "Parse queries string from DB back to vector. Uses safe edn/read-string."
  [q]
  (try (clojure.edn/read-string q)
       (catch Exception _ [q])))
