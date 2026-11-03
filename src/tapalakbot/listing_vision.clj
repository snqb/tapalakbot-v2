(ns tapalakbot.listing-vision
  "Fail-closed visual evidence for Lalafo listing photos.

  This namespace deliberately sends no listing text, prices, currencies, or action
  URLs to the vision model. Images remain source-owned URLs and are returned only
  as internal provenance for the parent tool layer."
  (:require
   [cheshire.core :as json]
   [clj-harness.infra :as infra]
   [clj-harness.observe :as observe]
   [clojure.string :as str]
   [com.brunobonacci.mulog :as u])
  (:import [java.net URI]))

(def ^:private vision-timeout-ms 30000)
(def ^:private max-images 2)
(def ^:private max-focus-chars 500)
(def ^:private max-observation-chars 600)
(def ^:private max-limitation-count 10)
(def ^:private observation-kinds
  #{:brand :model :visible-feature :visible-damage})
(def ^:private confidence-values #{:clear :uncertain})
(def ^:private limitation-text
  {"functionality-not-verified" "Работоспособность по фото не проверена."
   "authenticity-not-verified" "Подлинность по фото не подтверждена."
   "reliability-not-verified" "Надёжность по фото не подтверждена."
   "condition-not-assessed" "Общее состояние по фото не оценено."
   "damage-not-determinable" "Отсутствие повреждений по фото не подтверждено."
   "brand-or-model-not-visible" "Марка или модель на фото не читаются."
   "specifications-not-visible" "Технические характеристики на фото не читаются."
   "accessories-not-visible" "Комплектация и аксессуары по фото не подтверждены."
   "details-not-visible" "Нужные детали недостаточно видны на фото."
   "visibility-limited" "Освещение, ракурс или качество фото ограничивают визуальную оценку."})

(def ^:private limitation-codes (vec (keys limitation-text)))

(defn- nonblank [value]
  (when (and (string? value) (not (str/blank? value))) value))

(defn- lookup [m key]
  (or (get m key) (get m (name key))))

(defn- card-value [card key]
  (or (get card key) (get card (name key))))

(defn- listing-id [card]
  (when-let [raw (card-value card :id)]
    (let [value (str raw)]
      (when-not (str/blank? value) value))))

(defn- image-value [image]
  (cond
    (string? image) image
    (map? image) (or (get image :original-url)
                     (get image :original_url)
                     (get image :originalUrl)
                     (get image "original_url")
                     (get image "originalUrl"))
    :else nil))

(def ^:private lalafo-image-hosts
  #{"images.lalafo.kg" "image.lalafo.kg" "img.lalafo.kg"
    "cdn.lalafo.kg" "static.lalafo.kg" "media.lalafo.kg"})

(defn- lalafo-image-host? [host]
  (and (string? host)
       (or (contains? lalafo-image-hosts host)
           (boolean (re-matches #"img[0-9]+\.lalafo\.com" host)))))

(defn- safe-image-url? [raw]
  (when (and (string? raw) (not (str/blank? raw)))
    (try
      (let [uri (URI/create raw)
            scheme (some-> (.getScheme uri) str/lower-case)
            host (some-> (.getHost uri) str/lower-case)
            port (.getPort uri)]
        (and (= "https" scheme)
             (lalafo-image-host? host)
             (nil? (.getUserInfo uri))
             (or (= -1 port) (= 443 port))
             (not (str/blank? (.getRawPath uri)))))
      (catch IllegalArgumentException _ false))))

(defn- source-images [card]
  (let [images (card-value card :images)
        primary (card-value card :image)
        candidates (concat (if (sequential? images) images []) [primary])]
    (->> candidates
         (map image-value)
         (filter safe-image-url?)
         distinct
         (take max-images)
         vec)))

(defn- focus-text [focus]
  (let [text (or (nonblank focus)
                 "Identify only details visibly supported by the supplied photo(s).")]
    (subs text 0 (min max-focus-chars (count text)))))

(defn- endpoint []
  (let [configured (nonblank (System/getenv "OPENROUTER_BASE_URL"))]
    (cond
      (nil? configured) "https://openrouter.ai/api/v1/chat/completions"
      (re-find #"/chat/completions/?$" configured) configured
      (str/ends-with? configured "/") (str configured "chat/completions")
      :else (str configured "/chat/completions"))))

(defn- model []
  (or (nonblank (infra/cfg :models :gemini-3.5-flash))
      "google/gemini-3.5-flash"))

(defn- schema-failure! [message]
  (throw (ex-info message {:vision-failure :schema})))

(defn- malformed! [message]
  (throw (ex-info message {:vision-failure :malformed})))

(defn- safety-failure! [message]
  (throw (ex-info message {:vision-failure :safety})))


(defn- schema []
  {"type" "object"
   "additionalProperties" false
   "required" ["observations" "limitations"]
   "properties"
   {"observations"
    {"type" "array"
     "maxItems" 20
     "items"
     {"type" "object"
      "additionalProperties" false
      "required" ["kind" "text" "confidence" "image_index"]
      "properties"
      {"kind" {"type" "string"
               "enum" ["brand" "model" "visible-feature" "visible-damage"]}
       "text" {"type" "string" "minLength" 1 "maxLength" max-observation-chars}
       "confidence" {"type" "string" "enum" ["clear" "uncertain"]}
       "image_index" {"type" "integer" "minimum" 0 "maximum" (dec max-images)}}}}
    "limitations"
    {"type" "array"
     "maxItems" max-limitation-count
     "items" {"type" "string" "enum" limitation-codes}}}})

(defn- request-payload [focus image-urls]
  {"model" (model)
   "messages"
   [{"role" "user"
     "content"
     (into [{"type" "text"
             "text"
             (str "Inspect the supplied listing photo(s), not any listing metadata. "
                  "Focus: " (focus-text focus) "\n"
                  "Report only visible/OCR evidence. A clear observation means the "
                  "detail is readable or visibly clear; uncertain means visibility "
                  "is ambiguous. Never infer physical condition, functionality, "
                  "authenticity, seller assertions, price, currency, or URLs. "
                  "Write observation text in Russian. The limitations array MUST "
                  "contain only these codes, not prose: "
                  (str/join ", " limitation-codes) ". "
                  "Use a limitation code whenever a useful unknown remains. "
                  "Do not report that an item works or is undamaged. ")}
            ]
           (map (fn [url]
                  {"type" "image_url"
                   "image_url" {"url" url}})
                image-urls))}]
   "response_format"
   {"type" "json_schema"
    "json_schema" {"name" "listing_visual_observations"
                    "strict" true
                    "schema" (schema)}}
   ;; Thinking shares the output budget; leave room for the structured observations.
   "reasoning" {"effort" "minimal"}
   "max_tokens" 1200})

(defn- usage-data [response]
  (let [usage (or (lookup response :usage) {})
        choices (lookup response :choices)
        choice (first (when (sequential? choices) choices))]
    {:prompt-tokens (or (lookup usage :input_tokens)
                        (lookup usage :prompt_tokens))
     :completion-tokens (or (lookup usage :output_tokens)
                            (lookup usage :completion_tokens))
     :total-tokens (lookup usage :total_tokens)
     :cost-usd (or (lookup usage :cost) (lookup usage :cost_usd))
     :finish-reason (lookup choice :finish_reason)}))

(defn- record-call! [listing-id image-count started ok? response error]
  (let [latency (long (/ (- (System/nanoTime) started) 1000000))
        usage (if response (usage-data response) {})
        event (merge {:type :llm-call
                      :model (model)
                      :provider :openrouter
                      :latency-ms latency
                      :listing-id listing-id
                      :image-count image-count
                      :ok? ok?}
                     usage
                     (when error {:error-class (.getName (class error))
                                  :failure-kind (or (:vision-failure (ex-data error)) :provider)}))]
    (observe/record! event)
    (u/log ::listing-vision.llm-call
           :model (model) :provider :openrouter :latency-ms latency
           :listing-id listing-id :image-count image-count :ok ok?
           :prompt-tokens (:prompt-tokens usage)
           :completion-tokens (:completion-tokens usage)
           :total-tokens (:total-tokens usage)
           :cost-usd (:cost-usd usage)
           :finish-reason (:finish-reason usage)
           :failure-kind (when error (or (:vision-failure (ex-data error)) :provider))
           :error-class (when error (.getName (class error))))))

(defn- call-provider [image-urls focus]
  (let [api-key (nonblank (infra/read-api-key :openrouter))]
    (when-not api-key
      (throw (ex-info "Vision API key unavailable" {:vision-failure :provider})))
    (infra/http-post
     (endpoint)
     (json/generate-string (request-payload focus image-urls))
     :headers {"Authorization" (str "Bearer " api-key)
               "HTTP-Referer" "http://localhost"
               "X-Title" "TapalakBot listing vision"
               "X-Correlation-Id" (str (java.util.UUID/randomUUID))}
     :timeout-ms vision-timeout-ms)))

(defn- response-content [response]
  (let [choices (lookup response :choices)
        choice (first (when (sequential? choices) choices))
        message (when (map? choice) (lookup choice :message))
        content (when (map? message) (lookup message :content))]
    (when-not (string? content)
      (malformed! "Vision response has no structured message content"))
    (try
      (json/parse-string content false)
      (catch Exception _
        (malformed! "Vision response content is not valid JSON")))))

(defn- exact-keys? [value expected]
  (= expected (set (map #(if (keyword? %) (name %) (str %)) (keys value)))))

(defn- forbidden-observation? [text]
  (let [lower (-> (str/lower-case text)
                  (str/replace #"ключ(?:а|ом|у|е)? безопасности" "ключ"))]
    (or (re-find #"https?://|www\.|lalafo\.(?:kg|com)" lower)
        (re-find #"(?iu)(?:цена|стоимость|price|cost)\s*[:—-]?\s*\d|\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/\S*)?" lower)
        (re-find #"(?iu)\b(?:сом(?:ов|а)?|kgs|руб(?:лей|ля)?)\b|₽|\$|€" lower)
        (re-find #"без.{0,40}(?:повреждени|дефект|царапин)" lower)
        (re-find #"(?:продавец|работает|исправн|рабоч(?:ее|ий)|оригинальн|подлинн|над[её]жн|долговечн|износоустойчив|безопасн|гаранти|(?:выглядит|состояни[ея]).{0,12}как нов|без повреждени|без дефект|повреждени[йя]\s+(?:нет|не)|не\s+(?:обнаружен|видн).{0,20}повреждени|полностью проверен|\b(?:seller|works|working|functional|operational|tested|genuine|authentic|reliable|durable|safe)\b|no damage|good condition|excellent condition|like new)" lower))))

(defn- normalize-observation [value image-count]
  (when-not (map? value)
    (schema-failure! "Vision observation is not an object"))
  (when-not (exact-keys? value #{"kind" "text" "confidence" "image_index"})
    (schema-failure! "Vision observation contains unsupported fields"))
  (let [kind-raw (lookup value :kind)
        text (lookup value :text)
        confidence-raw (lookup value :confidence)
        image-index (lookup value :image_index)
        kind (when (or (string? kind-raw) (keyword? kind-raw))
               (keyword (if (keyword? kind-raw) (name kind-raw) kind-raw)))
        confidence (when (or (string? confidence-raw) (keyword? confidence-raw))
                     (keyword (if (keyword? confidence-raw)
                                (name confidence-raw)
                                confidence-raw)))]
    (when-not (and (contains? observation-kinds kind)
                   (string? text)
                   (not (str/blank? text))
                   (<= (count text) max-observation-chars)
                   (contains? confidence-values confidence)
                   (integer? image-index)
                   (<= 0 image-index (dec image-count)))
      (schema-failure! "Vision observation is outside the supported evidence shape"))
    (when (forbidden-observation? text)
      (safety-failure! "Vision observation crossed the visual evidence boundary"))
    {:kind kind :text text :confidence confidence :image-index image-index}))

(defn- normalize-limitation [value]
  (let [code (when (or (string? value) (keyword? value))
               (if (keyword? value) (name value) value))]
    (when-not (contains? limitation-text code)
      (schema-failure! "Vision limitation uses an unsupported code"))
    (get limitation-text code)))

(defn- normalize-response [response image-urls]
  (let [value (response-content response)]
    (when-not (and (map? value)
                   (exact-keys? value #{"observations" "limitations"}))
      (schema-failure! "Vision JSON has an unexpected shape"))
    (let [observations (lookup value :observations)
          limitations (lookup value :limitations)]
      (when-not (and (sequential? observations)
                     (<= (count observations) 20)
                     (sequential? limitations)
                     (<= (count limitations) max-limitation-count))
        (schema-failure! "Vision JSON has invalid observations or limitations"))
      {:image-urls image-urls
       :observations (mapv #(normalize-observation % (count image-urls)) observations)
       :limitations (vec (concat
                          ["Свидетельства получены только из предоставленных фотографий; прочитанные надписи — визуальные наблюдения."
                           "Уверенность относится к видимости, не к исправности."
                           "Описание продавца не использовалось как визуальное доказательство."]
                          (mapv normalize-limitation limitations)))
       :condition-verified? false})))

(defn- unavailable
  ([listing-id message] (unavailable listing-id :source message))
  ([listing-id kind message]
   {:status :unavailable :listing-id (or listing-id "") :failure-kind kind :error message}))

(def ^:private failure-messages
  {:malformed "Vision provider returned malformed structured output."
   :schema "Vision provider returned unsupported structured fields."
   :safety "Vision output violated the visual evidence safety boundary."
   :provider "Vision provider is unavailable."})


(defn inspect-card
  "Inspect at most two original HTTPS Lalafo image URLs for a neutral visual focus.
   Provider failures, malformed JSON, unsafe URLs, and unsupported claims fail closed."
  [card focus]
  (let [id (listing-id card)
        image-urls (source-images card)]
    (cond
      (nil? id) (unavailable "" "Listing has no stable source identifier.")
      (empty? image-urls) (unavailable id "Listing has no safe original Lalafo image.")
      :else
      (let [started (System/nanoTime)
            response* (atom nil)]
        (try
          (let [response (call-provider image-urls focus)]
            (reset! response* response)
            (let [result (normalize-response response image-urls)]
              (record-call! id (count image-urls) started true response nil)
              (assoc result :status :ok :listing-id id)))
          (catch clojure.lang.ExceptionInfo error
            (record-call! id (count image-urls) started false @response* error)
            (let [kind (or (:vision-failure (ex-data error)) :provider)]
              (unavailable id kind (get failure-messages kind (:provider failure-messages)))))
          (catch Exception error
            (record-call! id (count image-urls) started false @response* error)
            (unavailable id :provider (:provider failure-messages))))))))
