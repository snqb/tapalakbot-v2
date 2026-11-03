(ns tapalakbot.purchase-advice
  "Durable, source-owned buying strategy for the current harness session.

  Advice lives under the existing buying request. It never rewrites the
  canonical goal, bounds, exclusions, or listing pool."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def ^:private max-title-chars 300)
(def ^:private max-source-chars 8000)
(def ^:private max-context-chars 12000)

(defn- session-value [session]
  (cond
    (instance? clojure.lang.IDeref session) @session
    (map? session) session
    :else nil))

(defn- normalized-request [session]
  (some-> (get-in (session-value session) ["data" "buying-request"])
          walk/keywordize-keys))

(defn- task-key [request]
  (if-let [id (:task-id request)]
    {:task-id id}
    {:user-want (some-> (:user-want request) str str/trim str/lower-case)
     :queries (mapv #(-> % str str/trim str/lower-case)
                    (or (:queries request) []))}))

(declare safe-stored-source)
(defn- normalize-advice [advice]
  (some-> advice walk/keywordize-keys))

(defn current
  "Return normalized advice for the current buying request, or nil.

  Both keyword and string keys from persisted JSON are accepted on read."
  [session]
  (when-let [request (normalized-request session)]
    (let [advice (normalize-advice (:advice request))
          expected (task-key request)]
      (when advice
        (if (and (:task-key advice)
                 (not= expected (:task-key advice)))
          {:task-key expected :sources []}
          (update advice :sources #(vec (keep safe-stored-source %))))))))

(defn- source-url? [url]
  (try
    (let [uri (java.net.URI/create url)
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)]
      (and (= "https" scheme)
           (not (str/blank? host))
           (nil? (.getUserInfo uri))
           (not (re-find #"(^|\.)(lalafo|olx|ebay|craigslist|facebook|instagram|marketplace)(\.|$)"
                         host))))
    (catch Exception _ false)))

(defn- text-value [value limit]
  (when (and (string? value) (not (str/blank? value)))
    (let [value (str/trim value)]
      (subs value 0 (min limit (count value))))))

(defn- sanitize-source [source]
  (let [source (walk/keywordize-keys source)
        title (text-value (:title source) max-title-chars)
        url (text-value (:url source) 2000)
        text (text-value (:text source) max-source-chars)]
    (when (and title url text (source-url? url))
      {:title title :url url :text text})))
(defn- safe-stored-source [source]
  (let [source (walk/keywordize-keys source)
        id (:id source)]
    (when-let [clean (sanitize-source source)]
      (when (and (string? id) (re-matches #"r[1-9][0-9]*" id))
        (assoc clean :id id)))))

(defn- advice-for-request [request]
  (let [existing (normalize-advice (:advice request))
        expected (task-key request)]
    (if (and (:task-key existing)
             (not= expected (:task-key existing)))
      {:task-key expected :sources []}
      (merge {:task-key expected :sources []} existing))))

(defn- save-advice! [session advice]
  (if (and (instance? clojure.lang.IAtom session)
           (map? (normalized-request session)))
    (do
      (swap! session update-in ["data" "buying-request"]
             (fn [request]
               (-> (or request {})
                   (dissoc "advice")
                   (assoc :advice advice))))
      advice)
    nil))

(defn clear!
  "Clear only durable advice for an explicitly new buying task."
  [session]
  (when (and (instance? clojure.lang.IAtom session)
             (map? (normalized-request session)))
    (swap! session update-in ["data" "buying-request"]
           (fn [request]
             (dissoc (or request {}) "advice" :advice)))
    {:status :cleared}))

(defn- invalid [message]
  {:status :invalid :error message})

(defn- no-request []
  {:status :no-request :error "Нет текущей задачи покупки."})

(defn add-sources!
  "Register valid research sources for the current task.

  Sources merge by URL and retain stable r1, r2, ... IDs. Invalid input is
  rejected atomically; no canonical buying-request field is changed."
  [session sources]
  (let [request (normalized-request session)]
    (cond
      (nil? request) (no-request)
      (not (sequential? sources)) (invalid "Источники должны быть списком.")
      (empty? sources) (invalid "Список источников пуст.")
      :else
      (let [cleaned (mapv sanitize-source sources)]
        (if (some nil? cleaned)
          (invalid "Источник должен содержать безопасные HTTPS URL, заголовок и текст.")
          (let [advice (advice-for-request request)
                prior (vec
                       (keep (fn [source]
                               (let [clean (sanitize-source source)
                                     id (:id (walk/keywordize-keys source))]
                                 (when clean
                                   (assoc clean :id (when (and (string? id)
                                                               (re-matches #"r[1-9][0-9]*" id))
                                                      id)))))
                             (:sources advice)))
                prior-by-url (into {} (map (juxt :url identity) prior))
                merged (reduce (fn [acc source]
                                 (if (contains? acc (:url source)) acc
                                     (assoc acc (:url source) source)))
                               prior-by-url cleaned)
                ordered (mapv merged (distinct (concat (map :url prior) (map :url cleaned))))
                next-number (inc (reduce max 0 (keep (fn [source]
                                                       (some-> (:id source)
                                                               (#(re-matches #"r([1-9][0-9]*)" %))
                                                               second
                                                               parse-long))
                                                     prior)))
                assigned (loop [remaining ordered
                                number next-number
                                output []]
                           (if (empty? remaining)
                             output
                             (let [source (first remaining)
                                   id (:id source)]
                               (if id
                                 (recur (subvec remaining 1) number
                                        (conj output source))
                                 (recur (subvec remaining 1) (inc number)
                                        (conj output (assoc source :id (str "r" number))))))))
                updated (assoc advice :sources assigned)]
            (save-advice! session updated)
            {:status :ok :sources assigned}))))))

(defn- strings-list [value]
  (and (sequential? value)
       (every? #(and (string? %) (not (str/blank? %))) value)
       (mapv str/trim value)))

(defn- strategy-value [strategy key]
  (if (contains? strategy key) (get strategy key) []))

(defn source-excerpts
  "Assign stable references to exact bounded source slices; the model never rewrites quotations."
  [{:keys [id text]}]
  (loop [start 0 result []]
    (if (>= start (count text))
      result
      (let [end (min (+ start 800) (count text))
            window (subs text start end)
            gap (when (< end (count text)) (str/last-index-of window " "))
            end (if (and gap (pos? gap)) (+ start gap) end)
            quote (str/trim (subs text start end))]
        (recur (if (= \space (get text end)) (inc end) end)
               (cond-> result
                 (seq quote) (conj {:id (str id "e" (inc (count result)))
                                    :source-id id :quote quote})))))))

(defn grounded-evidence
  "Resolve only owned excerpt references into exact server-owned quotations."
  [sources evidence]
  (when (and (sequential? evidence) (every? string? evidence))
    (let [by-id (into {} (map (juxt :id identity)) (mapcat source-excerpts sources))
          resolved (mapv by-id evidence)]
      (when (every? some? resolved)
        (mapv #(select-keys % [:source-id :quote]) resolved)))))

(defn- validate-strategy [strategy sources]
  (if-not (map? strategy)
    (invalid "Стратегия должна быть объектом.")
    (let [strategy (walk/keywordize-keys strategy)
          summary (text-value (:summary strategy) 4000)
          directions (:directions strategy)
          checks (strings-list (strategy-value strategy :checks))
          unknowns (strings-list (strategy-value strategy :unknowns))]
      (cond
        (nil? summary) (invalid "Стратегия должна содержать непустое summary.")
        (not (sequential? directions)) (invalid "directions должны быть списком.")
        (nil? checks) (invalid "checks должны быть списком строк.")
        (nil? unknowns) (invalid "unknowns должны быть списком строк.")
        :else
        (let [clean-directions
              (mapv (fn [direction]
                      (let [direction (walk/keywordize-keys direction)
                            name (text-value (:name direction) 300)
                            rationale (text-value (:rationale direction) 2000)
                            evidence (grounded-evidence sources (:evidence direction))]
                        (when (and (map? direction) name rationale evidence)
                          {:name name :rationale rationale :evidence evidence
                           :source-ids (vec (distinct (map :source-id evidence)))})))
                    directions)]
          (if (some nil? clean-directions)
            (invalid "Каждое evidence — список принадлежащих excerpt ID (например r1e1), без цитат и выдуманных ID. Нет основания — [] и явная гипотеза.")
            {:status :ok
             :strategy {:summary summary
                        :directions clean-directions
                        :checks checks
                        :unknowns unknowns}}))))))

(defn save-strategy!
  "Validate and persist strategy while preserving the canonical buying goal."
  [session strategy]
  (let [request (normalized-request session)]
    (if-not request
      (no-request)
      (let [advice (advice-for-request request)
            result (validate-strategy strategy (:sources advice))]
        (if (= :ok (:status result))
          (let [proposed (assoc (:strategy result) :evidence-status :hypothesis)
                updated (assoc advice :strategy proposed)]
            (save-advice! session updated)
            {:status :ok :strategy proposed})
          result)))))

(defn- strip-urls [value]
  (-> (or value "")
      (str/replace #"https?://\S+" "[ссылка удалена]")
      (str/replace #"www\.\S+" "[ссылка удалена]")))

(defn- bounded [text]
  (subs text 0 (min max-context-chars (count text))))

(defn context-text
  "Return bounded strategy/evidence context without source URLs."
  [session]
  (when-let [advice (current session)]
    (let [strategy (:strategy advice)
          lines (concat
                 ["НАКОПЛЕННЫЕ ГИПОТЕЗЫ ПОКУПКИ. Стратегия — прежние предложения агента, НЕ независимое подтверждение характеристик или надёжности. Проверена принадлежность цитат, не истинность выводов. Сверяй каждое утверждение с исходными свидетельствами ниже."]
                 (when strategy
                   (concat ["Предложенная стратегия: " (strip-urls (:summary strategy))]
                           (map (fn [{:keys [name rationale source-ids]}]
                                  (str "Направление " (str/join "," source-ids) ": "
                                       (strip-urls name) " — " (strip-urls rationale)))
                                (:directions strategy))
                           (when (seq (:checks strategy))
                             [(str "Проверки: " (str/join "; " (map strip-urls (:checks strategy))))])
                           (when (seq (:unknowns strategy))
                             [(str "Неизвестно: " (str/join "; " (map strip-urls (:unknowns strategy))))])))
                 (when (seq (:sources advice))
                   (concat ["Источники и доступные excerpt ID:"]
                           (mapcat (fn [source]
                                     (cons (str "[" (:id source) "] " (strip-urls (:title source)))
                                           (map #(str (:id %) ": " (strip-urls (:quote %)))
                                                (source-excerpts source))))
                                   (:sources advice)))))]
      (bounded (str/join "\n" lines)))))

(defn cite-text
  "Expand only owned [[rN]] markers; unknown markers remain visibly unverified."
  [session text]
  (let [sources (or (:sources (current session)) [])
        by-id (into {} (map (juxt :id identity) sources))]
    (str/replace (str text)
                 #"\[\[([^\[\]]+)\]\]"
                 (fn [[_ marker]]
                   (if-let [{:keys [title url]} (get by-id marker)]
                     (str "[" (str/replace title #"[\[\]]" "") "](" url ")")
                     (str "[[" marker "]] (источник не проверен)"))))))
