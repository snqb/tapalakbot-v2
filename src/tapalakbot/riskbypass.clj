(ns tapalakbot.riskbypass
  "RiskBypass API client for Cloudflare challenge solving.
  
  Flow:
  1. Submit target URL to RiskBypass API
  2. Poll for solution (cf_clearance cookie + User-Agent)
  3. Return session data for use with protected sites
  
  Example:
    (require '[tapalakbot.riskbypass :as rb])
    (rb/solve-cloudflare! \"https://api.mashina.kg/api/catalog\")"
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io])
  (:import [java.time Instant Duration]
           [java.nio.file Files Path Paths]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private session-dir
  (Paths/get (System/getProperty "java.io.tmpdir")
             (into-array String ["tapalakbot-sessions"])))

(defn- ensure-session-dir! []
  (Files/createDirectories session-dir (into-array java.nio.file.attribute.FileAttribute [])))

(defn- session-file [site]
  (.toFile (.resolve session-dir (str site "_session.json"))))

;; ---------------------------------------------------------------------------
;; API calls
;; ---------------------------------------------------------------------------

(defn- api-key []
  (or (System/getenv "RISKBYPASS_API_KEY")
      (when-let [config (try (require 'aero.core)
                             (let [read-config (resolve 'aero.core/read-config)]
                               (read-config "resources/config.edn"))
                             (catch Exception _ nil))]
        (:riskbypass-key config))
      ""))

(defn- get-proxy
  "Build sticky proxy URL for RiskBypass.
   Uses session token to ensure same IP for cookie + requests."
  []
  (let [session-token (str (java.util.UUID/randomUUID))
        username (or (System/getenv "SMARTPROXY_USERNAME") "smart-elixir")
        password (or (System/getenv "SMARTPROXY_PASSWORD") "sukapidr19")
        endpoint (or (System/getenv "SMARTPROXY_ENDPOINT") "proxy.smartproxy.net:3120")
        ;; Build sticky session username: user_life-60_session-token
        sticky-user (str username "_life-60_session-" session-token)]
    {:proxy (str "http://" sticky-user ":" password "@" endpoint)
     :session-token session-token}))

(defn- submit-task!
  "Submit a Cloudflare challenge to RiskBypass API.
   Returns task-id on success."
  [target-url & {:keys [task-type proxy method] :or {task-type "cloudflare_waf" method "GET"}}]
  (let [key (api-key)
        proxy-info (get-proxy)
        proxy-string (or proxy (:proxy proxy-info))]
    (when (clojure.string/blank? key)
      (log/error "RISKBYPASS_API_KEY not configured")
      (throw (ex-info "RiskBypass API key not configured" {})))
    (let [payload (cond-> {"task_type" task-type
                           "target_url" target-url
                           "target_method" method}
                    proxy-string (assoc "proxy" proxy-string))
          _ (log/info "RiskBypass payload:" (dissoc payload "proxy") "proxy:" (if proxy-string (str (subs proxy-string 0 (min 30 (count proxy-string))) "...") "none"))
          resp (http/post "https://riskbypass.com/task/submit"
                          {:headers {"Content-Type" "application/json"
                                     "x-api-key" key}
                           :body (json/generate-string payload)
                           :as :json
                           :socket-timeout 60000
                           :conn-timeout 60000})]
      (if (get-in resp [:body :ok])
        {:task-id (get-in resp [:body :task_id])
         :session-token (:session-token proxy-info)
         :proxy proxy-string}
        (do
          (log/error "RiskBypass submit failed:" (:body resp))
          nil)))))

(defn- poll-result!
  "Poll RiskBypass for task result. Returns solution map or nil."
  [task-id & {:keys [timeout-ms] :or {timeout-ms 300000}}]
  (let [key (api-key)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (log/error "RiskBypass poll timeout for task:" task-id)
        (throw (ex-info "RiskBypass timeout" {:task-id task-id})))
      (let [resp (http/get (str "https://riskbypass.com/task/result/" task-id)
                           {:headers {"Cache-Control" "no-cache"
                                      "x-api-key" key}
                            :as :json
                            :socket-timeout 60000
                            :conn-timeout 60000})
            status (get-in resp [:body :status])]
        (case status
          ("RUNNING" "QUEUED") (do (Thread/sleep 5000) (recur))
          "SUCCESS" (let [body (:body resp)
                          _ (log/info "RiskBypass response body:" body)
                          result (get body :result {})
                          _ (log/info "RiskBypass result:" result)
                          cookies (let [c (or (get result "cookies")
                                              (get result :cookies))]
                                    (if (and c (seq c)) c {}))
                          _ (log/info "RiskBypass cookies:" cookies)
                          user-agent (or (get result "ua")
                                         (get result :ua)
                                         (get result "user_agent")
                                         (get result :user_agent)
                                         "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                          cf-clearance (or (get cookies "cf_clearance")
                                           (get cookies :cf_clearance))]
                      {:cookies cookies
                       :user-agent user-agent
                       :cf-clearance cf-clearance})
          ("FAILED" "NOT_FOUND") (do
                                   (log/error "RiskBypass task failed:" status (:body resp))
                                   nil)
          (do
            (log/warn "RiskBypass unexpected status:" status)
            (Thread/sleep 2000)
            (recur)))))))

;; ---------------------------------------------------------------------------
;; Session management
;; ---------------------------------------------------------------------------

(defn solve-cloudflare!
  "Solve Cloudflare challenge for a target URL.
   Returns {:cookies, :user-agent, :cf-clearance, :proxy} or nil."
  [target-url & {:keys [task-type proxy method timeout-ms]
                 :or {task-type "cloudflare_waf" method "GET" timeout-ms 300000}}]
  (log/info "Solving Cloudflare challenge for:" target-url)
  (let [result (submit-task! target-url
                             :task-type task-type
                             :proxy proxy
                             :method method)]
    (when result
      (log/info "Task submitted:" (:task-id result) "- polling for result...")
      (let [solution (poll-result! (:task-id result) :timeout-ms timeout-ms)]
        (when solution
          (assoc solution
                 :proxy (:proxy result)
                 :session-token (:session-token result)))))))

(defn get-or-solve-session!
  "Get cached session or solve Cloudflare challenge.
   Sessions are cached per site in /tmp/tapalakbot-sessions/"
  [site target-url & {:keys [force? ttl-minutes] :or {force? false ttl-minutes 120}}]
  (ensure-session-dir!)
  (let [f (session-file site)]
    (if (and (not force?) (.exists f))
      (try
        (let [data (json/parse-string (slurp f) true)
              expires-at (Instant/parse (:expires-at data))
              now (Instant/now)]
          (if (.isAfter expires-at (.plus now (Duration/ofMinutes 5)))
            (do
              (log/info "Using cached session for" site)
              data)
            (do
              (log/info "Session expired for" site "- refreshing...")
              (when-let [session (solve-cloudflare! target-url)]
                (let [session-data (assoc session
                                          :site site
                                          :refreshed-at (.toString now)
                                          :expires-at (.toString (.plus now (Duration/ofMinutes ttl-minutes))))]
                  (log/info "Saving session to:" (.getAbsolutePath f))
                  (spit f (json/generate-string session-data {:pretty true}))
                  session-data)))))
        (catch Exception e
          (log/warn "Failed to read session cache:" (.getMessage e))
          (when-let [session (solve-cloudflare! target-url)]
            (let [now (Instant/now)
                  session-data (assoc session
                                      :site site
                                      :refreshed-at (.toString now)
                                      :expires-at (.toString (.plus now (Duration/ofMinutes ttl-minutes))))]
              (log/info "Saving session to:" (.getAbsolutePath f))
              (spit f (json/generate-string session-data {:pretty true}))
              session-data))))
      (when-let [session (solve-cloudflare! target-url)]
        (let [now (Instant/now)
              session-data (assoc session
                                  :site site
                                  :refreshed-at (.toString now)
                                  :expires-at (.toString (.plus now (Duration/ofMinutes ttl-minutes))))]
          (log/info "Saving session to:" (.getAbsolutePath f))
          (spit f (json/generate-string session-data {:pretty true}))
          session-data)))))

(defn clear-session!
  "Clear cached session for a site."
  [site]
  (let [f (session-file site)]
    (when (.exists f)
      (.delete f)
      (log/info "Cleared session for" site))))

;; ---------------------------------------------------------------------------
;; Debug / CLI
;; ---------------------------------------------------------------------------

(defn -main
  "Test RiskBypass with mashina.kg"
  [& args]
  (let [url (or (first args) "https://api.mashina.kg/api/catalog")]
    (println "Testing RiskBypass with:" url)
    (if-let [session (solve-cloudflare! url)]
      (do
        (println "✅ Success!")
        (println "  cf_clearance:" (subs (:cf-clearance session "") 0 (min 40 (count (:cf-clearance session "")))))
        (println "  user-agent:" (subs (:user-agent session) 0 (min 60 (count (:user-agent session))))))
      (println "❌ Failed to solve challenge"))))
