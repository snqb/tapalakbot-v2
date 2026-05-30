(ns tapalakbot.monitor.store
  "SQLite storage for Lalafo price monitoring.
   Tables:
     monitor_categories — what we track (id, name, queries, city_id, enabled)
     monitor_items     — individual listings (id, category_id, title, url, current_price, last_seen)
     monitor_snapshots — price history (id, item_id, price, scanned_at)"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.tools.logging :as log]))

;; ════════════════════════════ DB ════════════════════════════

(def ^:private db-path (or (System/getenv "MONITOR_DB_PATH")
                           "/tmp/tapalakbot-monitor.db"))

(defonce ^:private ds (atom nil))

(defn init-db!
  "Initialize SQLite database and create tables."
  []
  (let [d (jdbc/get-datasource (str "jdbc:sqlite:" db-path))]
    (reset! ds d)
    (jdbc/execute! d ["
      CREATE TABLE IF NOT EXISTS monitor_categories (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        queries TEXT NOT NULL,
        city_id INTEGER DEFAULT 103184,
        enabled INTEGER DEFAULT 1,
        created_at TEXT DEFAULT (datetime('now'))
      )"])
    (jdbc/execute! d ["
      CREATE TABLE IF NOT EXISTS monitor_items (
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
    (jdbc/execute! d ["
      CREATE TABLE IF NOT EXISTS monitor_snapshots (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_id INTEGER NOT NULL,
        price REAL NOT NULL,
        scanned_at TEXT DEFAULT (datetime('now')),
        FOREIGN KEY (item_id) REFERENCES monitor_items(id)
      )"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_snapshots_item ON monitor_snapshots(item_id)"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_snapshots_time ON monitor_snapshots(scanned_at)"])
    (jdbc/execute! d ["CREATE INDEX IF NOT EXISTS idx_items_category ON monitor_items(category_id)"])
    (log/info :monitor-db-init :path db-path)
    d))

(defn- get-ds []
  (or @ds (init-db!)))

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
    :queries ["велосипед" "bicycle" " mountain bike" "велосипед горный"]
    :city_id 103184}
   {:name "Телевизоры"
    :queries ["Телевизор Samsung" "Телевизор LG" "TV 55" "Smart TV" "телевизор"]
    :city_id 103184}])

(defn seed-categories!
  "Insert default categories if table is empty."
  []
  (let [d (get-ds)
        count (-> (jdbc/execute! d ["SELECT COUNT(*) as c FROM monitor_categories"])
                  first :c)]
    (when (zero? count)
      (doseq [cat default-categories]
        (jdbc/execute! d ["INSERT INTO monitor_categories (name, queries, city_id) VALUES (?, ?, ?)"
                          (:name cat)
                          (pr-str (:queries cat))
                          (:city_id cat)]))
      (log/info :categories-seeded :count (count default-categories)))))

(defn get-categories
  "Get all enabled categories."
  []
  (jdbc/execute! (get-ds) ["SELECT * FROM monitor_categories WHERE enabled = 1"]))

;; ════════════════════════════ ITEMS ════════════════════════════

(defn upsert-item!
  "Insert or update an item. Returns item id."
  [{:keys [id category_id title url price currency image_url city]}]
  (jdbc/execute-one! (get-ds)
                     ["INSERT INTO monitor_items (id, category_id, title, url, current_price, currency, image_url, city, last_seen)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, datetime('now'))
                       ON CONFLICT(id) DO UPDATE SET
                         current_price = excluded.current_price,
                         title = excluded.title,
                         last_seen = datetime('now'),
                         image_url = COALESCE(excluded.image_url, monitor_items.image_url)"
                      id category_id title url price (or currency "KGS") image_url city]))

(defn get-items-by-category
  "Get all items for a category, ordered by price."
  [category-id]
  (jdbc/execute! (get-ds)
                 ["SELECT i.*, c.name as category_name
                   FROM monitor_items i
                   JOIN monitor_categories c ON i.category_id = c.id
                   WHERE i.category_id = ? AND i.current_price > 0
                   ORDER BY i.current_price ASC"
                  category-id]))

(defn get-item
  "Get a single item by ID."
  [item-id]
  (jdbc/execute-one! (get-ds)
                     ["SELECT i.*, c.name as category_name
                       FROM monitor_items i
                       JOIN monitor_categories c ON i.category_id = c.id
                       WHERE i.id = ?"
                      item-id]))

;; ════════════════════════════ SNAPSHOTS ════════════════════════════

(defn record-snapshot!
  "Record a price snapshot for an item."
  [item-id price]
  (jdbc/execute! (get-ds)
                 ["INSERT INTO monitor_snapshots (item_id, price) VALUES (?, ?)"
                  item-id price]))

(defn record-snapshots-batch!
  "Record multiple price snapshots in a single transaction."
  [snapshots]
  (jdbc/execute-batch! (get-ds)
                       "INSERT INTO monitor_snapshots (item_id, price) VALUES (?, ?)"
                       (mapv (fn [[item-id price]] [item-id price]) snapshots)
                       {:batch-size 500}))

;; ════════════════════════════ QUERIES ════════════════════════════

(defn get-trending
  "Get latest prices for all categories — top items per category.
   Returns map of category-name → [items]."
  []
  (let [cats (get-categories)]
    (into {}
          (map (fn [cat]
                 [(:name cat)
                  (jdbc/execute! (get-ds)
                                 ["SELECT i.*, c.name as category_name,
                                          (SELECT COUNT(*) FROM monitor_snapshots s WHERE s.item_id = i.id) as snapshot_count,
                                          (SELECT MIN(s.price) FROM monitor_snapshots s WHERE s.item_id = i.id) as min_price,
                                          (SELECT MAX(s.price) FROM monitor_snapshots s WHERE s.item_id = i.id) as max_price
                                   FROM monitor_items i
                                   JOIN monitor_categories c ON i.category_id = c.id
                                   WHERE i.category_id = ? AND i.current_price > 0
                                   ORDER BY i.current_price ASC
                                   LIMIT 10"
                                  (:id cat)])])
               cats))))

(defn get-deals
  "Find items priced below their average (good deals).
   Threshold: price < avg * 0.8 (20% below average)."
  []
  (jdbc/execute! (get-ds)
                 ["WITH item_stats AS (
                     SELECT item_id,
                            AVG(price) as avg_price,
                            MIN(price) as min_price,
                            COUNT(*) as samples
                     FROM monitor_snapshots
                     GROUP BY item_id
                     HAVING COUNT(*) >= 2
                   )
                   SELECT i.*, c.name as category_name,
                          s.avg_price, s.min_price, s.samples,
                          ROUND((1.0 - (i.current_price / s.avg_price)) * 100, 1) as discount_pct
                   FROM item_stats s
                   JOIN monitor_items i ON i.id = s.item_id
                   JOIN monitor_categories c ON i.category_id = c.id
                   WHERE i.current_price < s.avg_price * 0.8
                     AND i.current_price > 0
                   ORDER BY discount_pct DESC
                   LIMIT 20"]))

(defn get-history
  "Get price history for a specific item."
  [item-id]
  (jdbc/execute! (get-ds)
                 ["SELECT s.price, s.scanned_at
                   FROM monitor_snapshots s
                   WHERE s.item_id = ?
                   ORDER BY s.scanned_at ASC"
                  item-id]))

(defn get-history-by-category
  "Get price history for all items in a category, aggregated by day."
  [category-id]
  (jdbc/execute! (get-ds)
                 ["SELECT DATE(s.scanned_at) as day,
                         AVG(s.price) as avg_price,
                         MIN(s.price) as min_price,
                         MAX(s.price) as max_price,
                         COUNT(DISTINCT s.item_id) as items
                   FROM monitor_snapshots s
                   JOIN monitor_items i ON i.id = s.item_id
                   WHERE i.category_id = ?
                   GROUP BY DATE(s.scanned_at)
                   ORDER BY day DESC
                   LIMIT 30"
                  category-id]))

(defn get-stats
  "Get overall monitoring stats."
  []
  (let [d (get-ds)]
    {:categories (-> (jdbc/execute-one! d ["SELECT COUNT(*) as c FROM monitor_categories WHERE enabled = 1"]) :c)
     :items (-> (jdbc/execute-one! d ["SELECT COUNT(*) as c FROM monitor_items"]) :c)
     :snapshots (-> (jdbc/execute-one! d ["SELECT COUNT(*) as c FROM monitor_snapshots"]) :c)
     :last-scan (-> (jdbc/execute-one! d ["SELECT MAX(scanned_at) as t FROM monitor_snapshots"]) :t)
     :price-range (jdbc/execute-one! d ["SELECT MIN(current_price) as min_price,
                                               MAX(current_price) as max_price,
                                               AVG(current_price) as avg_price
                                        FROM monitor_items WHERE current_price > 0"])}))

(defn get-category-summary
  "Get summary stats for each category."
  []
  (jdbc/execute! (get-ds)
                 ["SELECT c.id, c.name,
                         COUNT(DISTINCT i.id) as item_count,
                         AVG(i.current_price) as avg_price,
                         MIN(i.current_price) as min_price,
                         MAX(i.current_price) as max_price,
                         COUNT(DISTINCT s.id) as snapshot_count
                   FROM monitor_categories c
                   LEFT JOIN monitor_items i ON i.category_id = c.id AND i.current_price > 0
                   LEFT JOIN monitor_snapshots s ON s.item_id = i.id
                   WHERE c.enabled = 1
                   GROUP BY c.id
                   ORDER BY item_count DESC"]))

(defn cleanup-old-snapshots!
  "Remove snapshots older than N days."
  [days]
  (jdbc/execute! (get-ds)
                 ["DELETE FROM monitor_snapshots WHERE scanned_at < datetime('now', ?)"
                  (str "-" days " days")]))
