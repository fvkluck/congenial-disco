(ns contactbook.main
  (:require [clojure.string :as string]
            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :refer [not-found resources]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as response]
            [hiccup.core :refer [html]]
            [hiccup.form :refer [form-to submit-button label text-field hidden-field]]
            [com.stuartsierra.component :as component]))

(def ^:private my-db
  {:dbtype "sqlite" :dbname "contactbook_db"})

(def ds (jdbc/get-datasource my-db))

(defn populate! [db]
  (jdbc/execute-one! (:datasource db) ["create table contact (
                         id  integer primary key autoincrement,
                         first_name  varchar(32),
                         last_name   varchar(32),
                         street      varchar(256),
                         number      varchar(12),
                         zip_code    varchar(10),
                         city        varchar(128)
                         )"]))

(defrecord Database [db-config]
  component/Lifecycle

  (start [this]
    (println ";; Starting database")
    (let [ds (jdbc/get-datasource db-config)]
      (assoc this :datasource ds)))

  (stop [this]
    (println ";; Stopping database")
    (assoc this :datasouce nil)))

(defn new-database []
  (map->Database {:db-config my-db}))

(defn save-contact [db params]
  (let [contact (select-keys params [:id :first_name :last_name :street :number :zip_code :city])]
    (if-let [id (get contact :id)]
      (sql/update! (:datasource db) :contact contact {:id id})
      (sql/insert! (:datasource db) :contact contact))))

(defn get-contact [db id]
  (jdbc/execute-one! (:datasource db) ["select * from contact where id = ?" id]))

(defn get-all-contacts [db]
  (jdbc/execute! (:datasource db) ["select * from contact"]) )

(defn remove-contact [db id]
  (sql/delete! (:datasource db) :contact {:id id}))

(comment
  "start system first"
  (save-contact (:db system) {:contact/first_name "My first name"
                              :contact/last_name "Some last name"
                              :contact/street "Some street"
                              :contact/number 123
                              :contact/zip_code "1234 AB"
                              :contact/city "Gotham"})
  (save-contact (:db system) {:first_name "My first name"
                              :last_name "Some last name"
                              :street "Some street"
                              :number 123
                              :zip_code "1234 AB"
                              :city "Gotham"})
  (get-all-contacts (:db system))
  (get-contact (:db system) 1))

(comment
  "Controller part")

(defn show-all-contacts [application]
  (let [contacts (get-all-contacts (:database application))]
    (html [:head
           [:link {:href "/bulma/css/bulma.css" :rel "stylesheet" :type "text/css"}]
           [:body
           [:div 
               [:table {:class "table is-striped is-bordered is-hoverable"}
                [:thead 
                 [:tr (for [d ["First name" "Last name" "Adress" "Zip Code" "City" "Remove"]]
                        [:td d])]]
                 [:tbody
                  (for [{:keys [:contact/id
                                :contact/first_name
                                :contact/last_name
                                :contact/street
                                :contact/number
                                :contact/zip_code
                                :contact/city]} contacts]
                    [:tr
                     [:td [:a {:href (str "/contact/" id)} first_name]]
                     [:td last_name]
                     [:td (str street " " number)]
                     [:td zip_code]
                     [:td city]
                     [:td [:a {:href (str "/contact/delete/" id) :data-method "post"} [:button {:class "button"} "Remove"]]]])]]]]])))

(defn show-contact-form
  ([application] (show-contact-form application nil))
  ([application id]
   (let [contact (if id
                   (get-contact (:database application) id))]
     (html [:body
            [:h1 "new contact form"]
            (form-to [:post "/contact/new"]
                     (when contact
                       (hidden-field "id" id))
                     (for [[id text] [["first_name" "First name:"]
                                      ["last_name" "Last name:"]
                                      ["street" "Street:"]
                                      ["number" "Number:"]
                                      ["zip_code" "Zip code:"]
                                      ["city" "City:"]]]
                       [:p
                        (label id text)
                        (text-field id (if-let [value (get contact (keyword "contact" id))] value))])
                     (submit-button (if contact "Update" "Create")))]))))

(defn create-contact-handler [application params]
  (let [database (:database application)]
    (do
      (save-contact database params)
      (response/redirect "/contact/list"))))

(defn delete-contact-handler [application id]
  (do (remove-contact (:database application) id)
      (response/redirect "/contact/list")))

(defn contact-routes [application]
  (compojure/routes
    (GET "/contact/list" [] (show-all-contacts application))
    (GET "/contact/new" [] (show-contact-form application))
    (POST "/contact/new" {params :params} (create-contact-handler application params))
    (GET "/contact/:id" [id] (show-contact-form application id))
    (GET "/contact/delete/:id" [id] (delete-contact-handler application id))
    (resources "/")
    (not-found "not found")))

(defrecord Application [database]
  component/Lifecycle

  (start [this]
    (println ";;starting server")
    (assoc this :server
           (run-jetty (wrap-defaults (contact-routes this)
                                     (-> site-defaults
                                         (assoc-in [:security :anti-forgery] false)))
                      {:port 8888 :join? false})))

  (stop [this]
    (println ";;stopping server")
    (let [server (:server this)]
      (.stop server)
      (assoc this :server nil))))

(defn new-application []
  ( map->Application {}))

(defn new-system
  ([] (new-system {}))
 ([config]
  (component/system-map
    :db (new-database)
    :app (component/using (new-application)
                          {:database :db}))))

(comment
  (def system (new-system))
  (alter-var-root #'system component/start)
  (alter-var-root #'system component/stop)
  (do
    (alter-var-root #'system component/stop)
    (alter-var-root #'system component/start)))

