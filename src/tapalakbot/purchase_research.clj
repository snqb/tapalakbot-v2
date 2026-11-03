(ns tapalakbot.purchase-research
  "Bounded, provenance-preserving web research for buying decisions.

  This namespace only retrieves Exa sources. It does not rank sources with an
  LLM and it never turns a source into a seller or marketplace fact."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private exa-endpoint "https://api.exa.ai/search")
(def ^:private max-results 4)
(def ^:private max-source-chars 2400)
(def ^:private request-timeout-ms 15000)

(defn- nonblank [value]
  (when (and (string? value) (not (str/blank? value)))
    (str/trim value)))

(defn api-key
  "Read the Exa credential from EXA_API_KEY or the existing pass entry."
  []
  (or (nonblank (System/getenv "EXA_API_KEY"))
      (try
        (let [{:keys [out exit]} (shell/sh "pass" "show" "api/exa")]
          (when (zero? exit) (nonblank out)))
        (catch Exception _ nil))))

(defn- safe-error [message]
  (let [message (-> (or message "unknown error")
                    (str/replace #"(?i)(api[-_ ]?key|authorization|bearer)\s*[:=]\s*\S+"
                                 "$1: [redacted]")
                    (str/replace #"\s+" " ")
                    str/trim)]
    (subs message 0 (min 240 (count message)))))

(defn- unavailable [message]
  {:status :unavailable :error (safe-error message)})

(defn- valid-source-url? [value]
  (try
    (let [uri (java.net.URI/create value)
          scheme (some-> (.getScheme uri) str/lower-case)
          host (some-> (.getHost uri) str/lower-case)]
      (and (= "https" scheme)
           (not (str/blank? host))
           (nil? (.getUserInfo uri))
           (not (contains? #{"localhost" "localhost.localdomain" "0.0.0.0" "127.0.0.1" "::1"} host))))
    (catch Exception _ false)))

(defn- marketplace-host? [value]
  (try
    (let [host (some-> (.getHost (java.net.URI/create value)) str/lower-case)]
      (boolean
       (and host
            (re-find #"(^|\.)(lalafo|olx|ebay|craigslist|facebook|instagram|marketplace)(\.|$)"
                     host))))
    (catch Exception _ false)))

(defn- source-from-result [result]
  (let [title (nonblank (or (get result "title") (get result :title)))
        url (nonblank (or (get result "url") (get result :url)))
        text (nonblank (or (get result "text")
                           (get result :text)
                           (get result "contents")
                           (get result :contents)))]
    (when (and title url text
               (valid-source-url? url)
               (not (marketplace-host? url)))
      {:title (subs title 0 (min 300 (count title)))
       :url url
       :text (subs text 0 (min max-source-chars (count text)))})))

(defn- parse-sources [response]
  (let [results (or (get response "results") (get response :results))]
    (when-not (sequential? results)
      (throw (ex-info "Exa response has no results" {})))
    (let [sources (->> results
                       (keep source-from-result)
                       (reduce (fn [seen source]
                                 (if (some #(= (:url %) (:url source)) seen)
                                   seen
                                   (conj seen source)))
                               [])
                       vec)]
      (when (empty? sources)
        (throw (ex-info "Exa returned no readable HTTPS sources" {})))
      sources)))

(defn- fetch-sources [key body]
  ;; The shared generic helper adds Content-Type twice; Exa then rejects the body.
  (let [timeout (Duration/ofMillis request-timeout-ms)
        client (-> (HttpClient/newBuilder) (.connectTimeout timeout) .build)
        request (-> (HttpRequest/newBuilder (URI/create exa-endpoint))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" key)
                    (.timeout timeout)
                    (.POST (HttpRequest$BodyPublishers/ofString body)) .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode response))
      (throw (ex-info (str "Exa HTTP " (.statusCode response)) {})))
    (json/parse-string (.body response))))

(defn search
  "Search Exa and return bounded readable sources with HTTPS provenance.

  A failed credential, transport request, malformed response, or unusable
  source list is explicit :unavailable rather than a successful empty result."
  [query]
  (if-not (nonblank query)
    (unavailable "Research query is blank")
    (if-let [key (api-key)]
      (try
        (let [body (json/generate-string
                    {:query (str/trim query)
                     :type "auto"
                     :numResults max-results
                     :contents {:text {:maxCharacters max-source-chars}}})
              response (fetch-sources key body)]
          {:status :ok :sources (parse-sources response)})
        (catch Exception e
          (unavailable (or (.getMessage e) "Exa request failed"))))
      (unavailable "EXA API key is unavailable")))
)
