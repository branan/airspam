(ns airspam.core
  (:require (clj-http [client :as http]
                      [conn-mgr :as pool])
            (clojure [set :as set])
            (cassiel.zeroconf [client :as zc])
            (clojure.java [io :as io]))
  (:use clojure.pprint
        tableflisp.core)
  (:gen-class))

(defn create-connection
  "Total hack. We use a connection pool with one connection for each
  AirPlay display so that we can guarantee the connection can be kept
  alive as long as we want"
  []
  (pool/make-reusable-conn-manager
   {:timeout 86400
    :threads 1
    :default-per-route 1}))

(def displays (atom {}))

(defn keys-set
  "Get the keys from a map as a set"
  [map]
  (into #{} (keys map)))

(defn uuid
  "Generate a random UUID"
  []
  (str (java.util.UUID/randomUUID)))

(defn create-display
  "Add a new AirPlay display to our list and push an image up to it"
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
  "Terminate a connection to an AirPlay device and remove it from the
   list of known devices"
  [name]
  (pool/shutdown-manager (:conn (get name @displays)))
  (swap! displays dissoc name))

(defn on-zc-update
  "ZeroConf update callback. Adds/removes AirPlay devices from the
   list of known devices as necessary"
  [img old new]
  (let [old-keys (keys-set old)
        new-keys (keys-set new)
        added (set/difference new-keys old-keys)
        removed (set/difference old-keys new-keys)]
    (dorun (map remove-display removed))
    (dorun (map #(create-display % (get new %) img) added))))

(defn hang
  "Loop forever so the process doesn't terminate. Obviously this isn't
  the best way to do this. So sue me."
  []
  (recur))

(defn load-bin
  "Load a binary file into a byte array"
  [name]
  (with-open [input (new java.io.FileInputStream name)
              output (new java.io.ByteArrayOutputStream)]
    (io/copy input output)
    (.toByteArray output)))

(defn -main
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (when-not (= 1 (count args))
    (<╯°□°>╯︵┻━┻))
  (let [img (load-bin (first args))]
    (zc/listen "_airplay._tcp.local."
               :watch #(try (on-zc-update img %1 %2)
                            (catch Exception e
                              (pprint e))))
      (hang)))
