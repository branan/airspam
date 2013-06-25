(ns airspam.core
  (:require (clj-http [client :as http]
                      [conn-mgr :as pool])
            (clojure [set :as set])
            (cassiel.zeroconf [client :as zc])
            (clojure.java [io :as io]))
  (:use clojure.pprint)
  (:gen-class))

(defn create-connection
  []
  (pool/make-reusable-conn-manager
   {:timeout 86400
    :threads 1
    :default-per-route 1}))

(def displays (atom {}))

(defn keys-set
  [map]
  (into #{} (keys map)))

(defn uuid
  []
  (str (java.util.UUID/randomUUID)))

(defn create-display
  [name data img]
  (let [conn (create-connection)
        disp (assoc data :conn conn)
        url (format "http://%s:%d/photo" (:server data) (:port data))]
    (pprint name)
    (pprint data)
    (http/put url
              {:body img
               :header {"X-Apple-AssetKey" (uuid)
                        "X-Apple-Transition" "None"
                        "Connection" "Keep-Alive"
                        "Content-Length" (count img)
                        "User-Agent" "MediaControl/1.0"
                        "X-Apple-Session-ID" (uuid)}
               :connection-manager conn})
    (swap! displays assoc name disp)))

(defn remove-display
  [name]
  (pool/shutdown-manager (:conn (get name @displays)))
  (swap! displays dissoc name))

(defn on-zc-update
  "Prints new ZC stuff when it gets updated"
  [img old new]
  (let [old-keys (keys-set old)
        new-keys (keys-set new)
        added (set/difference new-keys old-keys)
        removed (set/difference old-keys new-keys)]
    (dorun
     (try
       (map remove-display removed)
       (catch Exception e
         (pprint e))))
    (dorun
     (try
       (map #(create-display % (get new %) img) added)
       (catch Exception e
         (pprint e))))
    (pprint new-keys)))

(defn hang
  "Hang the process"
  [srv]
  (zc/examine srv)
  (recur srv))

(defn load-bin
  [name]
  (with-open [input (new java.io.FileInputStream name)
              output (new java.io.ByteArrayOutputStream)]
    (io/copy input output)
    (.toByteArray output)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (when (= 1 (count args))
    (let [img (load-bin (first args))
          srv (zc/listen "_airplay._tcp.local."
                         :watch #(try (on-zc-update img %1 %2)
                                      (catch Exception e
                                        (pprint e))))]
      (hang srv))))
