(ns web-dev-dist-sys.server
  "Server for the talk Web Development Is Distributed Systems Programming."
  (:require
   [clojure.string     :as str]
   [ring.middleware.defaults :as ring-defaults]
   [compojure.core     :as comp :refer [routes GET POST]]
   [compojure.route    :as route]
   [hiccup.core        :as hiccup]
   [clojure.core.async :as async  :refer [<! <!! >! >!! put! chan go go-loop close!]]
   [taoensso.encore    :as encore]
   [taoensso.timbre    :as timbre]
   [taoensso.timbre.appenders.core :as appenders]
   [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
   [taoensso.sente     :as sente]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
   [org.httpkit.server :as http]
   [com.stuartsierra.component :as component]
   [figwheel-sidecar.repl-api :as fig]))


;; Set the global logging behavior for timbre
(timbre/set-config! {:level :info
                     :appenders {:rotor (rotor/rotor-appender {:max-size (* 1024 1024)
                                                               :backlog 10
                                                               :path "./web-dev-dist-sys.log"})}})

(defn get-and-count-max-index
  "Counts the number of slides in the folder and decs to 0-index."
  []
  (let [slides (->> (clojure.java.io/file "./resources/public/slides/")
                    file-seq
                    (map #(.getName %))
                    (filter #(re-find #"^web" %)))]
    (dec (count slides))))

(def db (atom {:index 0
               :max (get-and-count-max-index)}))

(defn user-id-fn
  "Each client provides a UUID on connect. We get it from the request and call it the uid on our end."
  [ring-req]
  (:client-id ring-req))

(defn landing-pg-handler [ring-req]
  (hiccup/html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:link {:href "css/style.css"
            :rel "stylesheet"
            :type "text/css"}]]
   [:body
    [:div#app]
    [:script {:src "js/compiled/web_dev_dist_sys.js"}]]))

(defn ring-routes [ring-ajax-get-or-ws-handshake]
  (routes
   (GET  "/"      ring-req (landing-pg-handler            ring-req))
   (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
   (route/resources "/") ; Static files, notably public/main.js (our cljs target)
   (route/not-found "<h1>Route not found, 404 :C</h1>")))

;; Event handlers

(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging."
  [{:as ev-msg :keys [event ring-req client-id]}]
  (let [session (:session ring-req)]
    (timbre/info {:uid client-id :event event})
    (-event-msg-handler ev-msg)))

(defmethod -event-msg-handler :cli/prev [_]
  (swap! db (fn [state] (update-in state [:index] dec))))

(defmethod -event-msg-handler :cli/next [_]
  (swap! db (fn [state] (update-in state [:index] inc))))

(defmethod -event-msg-handler :chsk/ws-ping [_] (comment "Noop"))

; Default/fallback case (no other matching handler)
(defmethod -event-msg-handler :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defn sync-client
  [{:keys [send-fn connected-uids]} term]
  (timbre/info {:event :sync-clients
                :message "Sending sync to all clients."})
  (let [db @db
        uids @connected-uids]
    (when uids
      (doseq [uid (:any uids)]
        (timbre/debug {:uid uid
                       :db db
                       :event :sync-client
                       :message (str "Syncing: " db " to UID: " uid)})
        (send-fn uid [:srv/sync (assoc db
                                       :term term)])))))

(defn push-client
  [{:keys [send-fn connected-uids]} _ _ _ new-state]
  (timbre/info {:event :push-clients
                :message "Pushing state to all clients."})
  (let [uids @connected-uids]
    (doseq [uid (:any uids)]
      (timbre/debug {:uid uid
                     :new-state new-state
                     :event :push-client
                     :message (str "Pushing: " new-state " to UID: " uid)})
      (send-fn uid [:srv/push new-state]))))

(defrecord ChskServer [ch-recv
                       send-fn
                       ajax-post-fn
                       ajax-get-or-ws-handshake-fn
                       connected-uids
                       stop-fn]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'ChskServer
                  :state :started
                  :message "Starting channel-socket server."})
    (let [server (sente/make-channel-socket-server! sente-web-server-adapter
                                                    {:packer :edn
                                                     :user-id-fn user-id-fn
                                                     :handshake-data-fn (fn [_] @db)})
          router (sente/start-server-chsk-router! (:ch-recv server) event-msg-handler)]
      (assoc this
             :ch-recv (:ch-recv server)
             :send-fn (:send-fn server)
             :ajax-post-fn (:ajax-post-fn server)
             :ajax-get-or-ws-handshake-fn (:ajax-get-or-ws-handshake-fn server)
             :connected-uids (:connected-uids server)
             :stop-fn router)))

  (stop [this]
    (if stop-fn
      (do
        (timbre/info {:component 'ChskServer
                      :state :stopped
                      :message "Stoppping WS server."})
        (stop-fn)
        (assoc this
               :ch-recv nil
               :send-fn nil
               :ajax-post-fn nil
               :ajax-get-or-ws-handshake-fn nil
               :connected-uids nil))
      this)))

(defn new-chsk-server [] (map->ChskServer {}))

(defrecord HttpServer [port chsk-server stop-fn]
  component/Lifecycle
  (start [this]
    (if-not stop-fn
      (let [_ (timbre/info {:component 'HttpServer
                             :state :started
                             :message "Starting presentation server."})
            chsk-handshake (:ajax-get-or-ws-handshake-fn chsk-server)
            ring-handler (ring-defaults/wrap-defaults (ring-routes chsk-handshake)
                                                      ring-defaults/site-defaults)

            server-map (let [stop-fn (http/run-server ring-handler {:port port})]
                         {:port    (:local-port (meta stop-fn))
                          :stop-fn (fn [] (stop-fn :timeout 100))})

            uri (format "http://localhost:%s/" port)]
        (timbre/info "Web server is running at `%s`" uri)

        (assoc this :stop-fn (:stop-fn server-map)))
      this))

  (stop [this]
    (if stop-fn
      (do
        (timbre/info {:component 'HttpServer
                       :state :stopped
                       :message "Stopping HTTP server."})
        (stop-fn))
      this)))

(defn new-http-server [port]
  (map->HttpServer {:port port}))

(defrecord Heartbeat [chsk-server interval stop-fn]
  component/Lifecycle
  (start [this]
    (if-not stop-fn
      (let [_ (timbre/info {:component 'HeartBeat
                            :state :started
                            :message "Starting heartbeat broadcasts."})
            ch-ctrl (chan)]

        (go-loop [term 0]
          (let [ch-timeout (async/timeout interval)
                [_ port] (async/alts! [ch-timeout ch-ctrl])
                stop? (= port ch-ctrl)]

            (when-not stop?
              (sync-client chsk-server term)

              (recur (inc term)))))

        (assoc this :stop-fn (fn [] (close! ch-ctrl))))
      this))

  (stop [this]
    (if stop-fn
      (do
        (timbre/info {:component 'HeartBeat
                      :state :stopped
                      :message "Stopping heartbeats."})
        (stop-fn))
      this)))

(defn new-heartbeat []
  (map->Heartbeat {:interval 1000}))

(defrecord Watcher [chsk-server active]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'Watcher
                  :state :started
                  :message "Adding watcher for db updates"})
    (add-watch db :index (partial push-client chsk-server))
    (assoc this :active [:index]))

  (stop [this]
    (timbre/info {:component 'Watcher
                  :state :stopped
                  :message "Removing db watcher"})
    (remove-watch db :index)
    (assoc this :active [])))

(defn new-watcher []
  (map->Watcher {}))

(defn new-system
  [config]
  (let [{:keys [port]} config]
    (component/start-system
     {:chsk-server (new-chsk-server)
      :http-server (component/using
                    (new-http-server port)
                    [:chsk-server])

      :watcher (component/using
                (new-watcher)
                [:chsk-server])

      :heartbeat (component/using
                  (new-heartbeat)
                  [:chsk-server])})))

(def system-state nil)

(defn init []
  (alter-var-root
   #'system-state
   (constantly
    (new-system {:port 10002}))))

(defn start []
  (alter-var-root
   #'system-state
   component/start))

(defn stop []
  (alter-var-root
   #'system-state
   component/stop-system))

(defn cider-stop! []
  (stop))

(defn cider-start! []
  (init)
  (start))

(defn -main "For `lein run`, etc." [] (component/start (new-system {:port 10002})))

;; Server Consistency states:
;; Inconsistent. Clients are not coordintated
;; Weak consistency: simple heartbeat and watcher keeps the slides as up to date as possible.
;;;; You can update the local state optimistically, or you can wait for a response slowly. (which may not come!)
;; Strong consistency: We confirm every movement and the clients operate in lockstep.
