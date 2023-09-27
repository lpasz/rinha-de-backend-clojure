(ns core
  (:gen-class)
  (:require [clojure.java.jdbc :as j]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            ;; [clojure.core.async :as a]
            [honey.sql :as sql]
            [muuntaja.core :as m]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :as rag]
            [reitit.coercion.spec]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [ragtime.strategy :as rs]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [hikari-cp.core :as hcp]
            [ring.util.response :as resp]
            [clojure.pprint :as pp])
  (:import [java.sql Date]))

;; Database

(def postgres-url (or (System/getenv "POSTGRES_URL")
                      "postgres://postgres:postgres@localhost:5432/postgres"))

(def db-uri (java.net.URI. postgres-url))

(defn datasource-options []
  (let [[username password] (str/split (or (.getUserInfo db-uri) ":") #":")]
    {:username           (or username (System/getProperty "user.name"))
     :password           (or password "")
     :port-number        (.getPort db-uri)
     :database-name      (str/replace-first (.getPath db-uri) "/" "")
     :server-name        (.getHost db-uri)
     :auto-commit        true
     :read-only          false
     :adapter            "postgresql"
     :connection-timeout 30000
     :validation-timeout 5000
     :idle-timeout       600000
     :max-lifetime       1800000
     :minimum-idle       10
     :maximum-pool-size  50
     :pool-name          (str "db-pool" (java.util.UUID/randomUUID))
     :register-mbeans    false}))

(def db-conn
  (delay {:datasource (hcp/make-datasource (datasource-options))}))

(defn query [q]
  (->> q
       (sql/format)
       (j/query @db-conn)))

(defn one [q]
  (first (query q)))

(defn insert [q]
  (-> @db-conn
      (j/execute! (sql/format q) {:return-keys ["id"]})
      (:id)))

;; Migrations

(defn config []
  {:datastore  (jdbc/sql-database postgres-url)
   :migrations (jdbc/load-directory "./migrations")
   :strategy rs/rebase})

(defn migrate []
  (rag/migrate (config)))

(defn rollback []
  (rag/rollback (config)))

;; Context 

(defn max-n-characters [str n]
  (>= n (count str)))

(s/def ::tech (s/coll-of (s/and string? #(max-n-characters % 32))))
(s/def ::stack (s/or :nil nil?
                     :stack ::tech))

(defn uuid [] (java.util.UUID/randomUUID))

(defn parse-stack [{:keys [stack]}]
  (if (s/valid? ::stack stack)
    (str/join ";" stack)
    (throw (Exception. "Invalid Stack"))))

(defn parse-nascimento [{:keys [nascimento]}]
  (Date/valueOf nascimento))

(defn parse-search-term [{:keys [nome stack apelido]}]
  (str/join ";" [nome stack apelido]))

(defn create-pessoa [body-params]
        ;; data (merge body-params
        ;;             {:id id
        ;;              :stack (parse-stack body-params),
        ;;              :nascimento (parse-nascimento body-params)
        ;;              :search (parse-search-term body-params)})]
    ;; (query {:insert-into [:pessoas] :values [data]})
  (query {:select 1})
  1)

(defn pessoa-by-search-term [_]
  (-> {:select [:id :apelido :nome :nascimento :stack]
       :from :pessoas
       :where [:= :id 1]}
      ;;  :where [:ilike :search (str "%" term "%")]
      (query)))


(defn pessoa-by-id [id]
  (->> {:select [:id :apelido :nome :nascimento :stack]
        :limit 1
        :from :pessoas
        :where [:= :id id]}
       (query)))

;; Handlers

(defn created [{:keys [body-params]}]
  (try
    (let [id (create-pessoa body-params)
          location (str "/pessoas/" id)]
      (resp/created location))
    (catch Exception _ (resp/status 422))))


(defn search-id [{:keys [path-params]}]
  (if-let [id (Integer/parseInt (:id path-params))]
    (-> id
        (pessoa-by-id)
        (resp/response))
    (resp/status 404)))

(defn search-term [{:keys [query-params]}]
  (if-let [term (query-params "t")]
    (-> term
        (pessoa-by-search-term)
        (resp/response))
    (resp/status 400)))

(defn count-users [_]
  (-> {:select [[:%count.*]]
       :from :pessoas}
      (one)
      (:count)
      (str)
      (resp/response)))

;; Router

(def router-config
  {:exception pretty/exception
   :data {:coercion reitit.coercion.spec/coercion
          :muuntaja m/instance
          :middleware [;; Put :query-params in the request, otherwise is just :query-string
                       parameters/parameters-middleware
                       ;; Put :body-params in the request, otherwise is just :body
                       muuntaja/format-negotiate-middleware
                       ;; Not required for the example since we don't return bodies
                       muuntaja/format-response-middleware
                       ;; Required to parse body into :body-params
                       muuntaja/format-request-middleware
                       (exception/create-exception-middleware
                        {::exception/default (partial exception/wrap-log-to-console
                                                      exception/default-handler)})]}})

(def app (ring/ring-handler
          (ring/router [["/pessoas" {:post created
                                     :get search-term}]
                        ["/pessoas/:id" {:get search-id}]
                        ["/contagem-pessoas" {:get count-users}]]
                       router-config)))

(def server-port (Integer/parseInt (or (System/getenv "SERVER_PORT") "8080")))

(defn start []
  (println (str "Jetty is starting in " server-port "..."))
  (jetty/run-jetty #'app {:port server-port, :join? false})
  (println (str "Jetty is running on " server-port "...")))

(defn -main []
  (try (migrate)
       (catch Exception e nil))
  (start)
  ;; (bulk-insert)
  )