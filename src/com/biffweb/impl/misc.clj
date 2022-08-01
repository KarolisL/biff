(ns com.biffweb.impl.misc
  (:require [buddy.core.nonce :as nonce]
            [buddy.sign.jwt :as jwt]
            [chime.core :as chime]
            [clj-http.client :as http]
            [clojure.stacktrace :as st]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.biffweb.impl.time :as time]
            [com.biffweb.impl.util :as util]
            [hawk.core :as hawk]
            [nextjournal.beholder :as beholder]
            [reitit.ring :as reitit-ring]
            [ring.adapter.jetty9 :as jetty]))

(defn use-beholder [{:biff.beholder/keys [on-save exts paths]
                     :or {paths ["src" "resources"]}
                     :as sys}]
  (let [;; Poor man's debouncer -- don't want to pull in core.async just for
        ;; this, and don't want to spend time figuring out how else to do it.
        last-called (atom #inst "1970")
        watch (apply beholder/watch
                     (fn [{:keys [path]}]
                       (when (and (or (empty? exts)
                                      (some #(str/ends-with? path %) exts))
                                  (time/elapsed? @last-called :now 1 :seconds))
                         ;; Give all the files some time to get written before invoking the callback.
                         (Thread/sleep 100)
                         (util/catchall-verbose (on-save sys))
                         (reset! last-called (java.util.Date.))))
                     paths)]
    (update sys :biff/stop conj #(beholder/stop watch))))

;; Deprecated
(defn use-hawk [{:biff.hawk/keys [on-save exts paths]
                 :or {paths ["src" "resources"]}
                 :as sys}]
  (let [watch (hawk/watch!
                [(merge {:paths paths
                         ;; todo debounce this properly
                         :handler (fn [{:keys [last-ran]
                                        :or {last-ran 0}} _]
                                    (when (< 500 (- (inst-ms (java.util.Date.)) last-ran))
                                      (on-save sys))
                                    {:last-ran (inst-ms (java.util.Date.))})}
                        (when exts
                          {:filter (fn [_ {:keys [^java.io.File file]}]
                                     (let [path (.getPath file)]
                                       (some #(str/ends-with? path %) exts)))}))])]
    (update sys :biff/stop conj #(hawk/stop! watch))))

(defn reitit-handler [{:keys [router routes on-error]
                       :or {on-error util/default-on-error}}]
  (reitit-ring/ring-handler
    (or router (reitit-ring/router routes))
    (reitit-ring/routes
      (reitit-ring/redirect-trailing-slash-handler)
      (reitit-ring/create-default-handler
        {:not-found          #(on-error (assoc % :status 404))
         :method-not-allowed #(on-error (assoc % :status 405))
         :not-acceptable     #(on-error (assoc % :status 406))}))))

(defn use-jetty [{:biff/keys [host port handler]
                  :or {host "localhost"
                       port 8080}
                  :as sys}]
  (let [server (jetty/run-jetty handler
                                {:host host
                                 :port port
                                 :join? false
                                 :allow-null-path-info true})]
    (log/info "Jetty running on" (str "http://" host ":" port))
    (update sys :biff/stop conj #(jetty/stop-server server))))

(defn mailersend [{:keys [mailersend/api-key
                          mailersend/defaults]}
                  opts]
  (let [opts (reduce (fn [opts [path x]]
                       (update-in opts path #(or % x)))
                     opts
                     defaults)]
    (try
      (get-in
        (http/post "https://api.mailersend.com/v1/email"
                   {:content-type :json
                    :oauth-token api-key
                    :form-params opts})
        [:headers "X-Message-Id"])
      (catch Exception e
        (log/error e "MailerSend exception")
        false))))

(defn jwt-encrypt
  [claims secret]
  (jwt/encrypt
    (-> claims
        (assoc :exp (time/add-seconds (time/now) (:exp-in claims)))
        (dissoc :exp-in))
    (util/base64-decode secret)
    {:alg :a256kw :enc :a128gcm}))

(defn jwt-decrypt
  [token secret]
  (try
    (jwt/decrypt
      token
      (util/base64-decode secret)
      {:alg :a256kw :enc :a128gcm})
    (catch Exception _
      nil)))

(defn use-chime
  [{:biff.chime/keys [tasks] :as sys}]
  (reduce (fn [sys {:keys [schedule task]}]
            (let [scheduler (chime/chime-at (schedule) (fn [_] (task sys)))]
              (update sys :biff/stop conj #(.close scheduler))))
          sys
          tasks))

(defn generate-secret [length]
  (util/base64-encode (nonce/random-bytes length)))

(defn use-random-default-secrets [sys]
  (merge sys
         (when (nil? (:biff.middleware/cookie-secret sys))
           (log/warn ":biff.middleware/cookie-secret is empty, using random value")
           {:biff.middleware/cookie-secret (generate-secret 16)})
         (when (nil? (:biff/jwt-secret sys))
           (log/warn ":biff/jwt-secret is empty, using random value")
           {:biff/jwt-secret (generate-secret 32)})))
