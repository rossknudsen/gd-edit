(ns gd-edit.app-util
  (:require [gd-edit.globals :as globals]
            [gd-edit.utils :as u]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.io.arc :as arc-reader]
            [gd-edit.io.arz :as arz-reader]
            [gd-edit.io.map :as map-reader]
            [gd-edit.io.gdc :as gdc]
            [gd-edit.quest :as quest]
            [gd-edit.watcher :as watcher]
            [clojure.java.io :as io]
            [jansi-clj.core :refer [red]]))

(defn character-loaded?
  []
  (and (not (nil? @globals/character))
       (not (empty? @globals/character))))

(defn get-process-list
  []

  (when (u/running-windows?)
    (let [plist-csv (:out (sh "tasklist.exe" "/fo" "csv" "/nh"))
          plist (for [row (str/split-lines plist-csv)]
                  (->> row
                       (re-seq #"\"(.*?)\"")
                       (map second)
                       (zipmap [:image-name :pid :session-name :session-num :mem-usage])))]
      plist)))

(defn find-running-process
  [plist process-name]

  (filter #(= (:image-name %) process-name) plist))

(defn is-grim-dawn-running?
  []

  (let [plist (get-process-list)]
    ;; If the process list cannot be retrieved for some reason...
    (cond
      (nil? plist)
      false ;; Assume the program isn't running

      ;; If we can't find any items in the process list named grim dawn...
      (empty? (find-running-process plist "Grim Dawn.exe"))
      false ;; The program isn't runing...

      :else
      true)))


(defn load-localization-files
  []
  (->> (dirs/get-file-and-overrides dirs/localization-file) ;; grab all localization files we want to load
       (map arc-reader/load-localization-table) ;; load each file
       (apply merge)))

(defn build-db-index
  [db]

  (->> db
       (map (fn [record] [(:recordname record) record]))
       (into {})))

(defn load-db
  [localization-table]

  (log/debug "Entering load-db")
  (->> (dirs/get-file-and-overrides dirs/database-file) ;; Grab all db files we want to load
       (map #(arz-reader/load-game-db % localization-table)) ;; load all the db files
       (map build-db-index) ;; merge all the loaded db records
       (apply merge)
       (vals)))

(defn build-db-and-index
  []
  {:db @globals/db
   :index @globals/db-index})

(defn load-shrines-and-gates
  []
  (try
    (->> (dirs/get-file-and-overrides dirs/level-file)
         (map map-reader/load-shrines-and-rift-gates)
         (apply merge)
         vals
         flatten
         distinct)
    ;; If we encounter problems loading shrines and gates, just return an empty list.
    ;;
    ;; This function is meant to load the srhines and gates in a `future`. If we ever
    ;; run into problems parsing the level file, due to game updates, any exception
    ;; would be captured by the future and will be rethrown everytime the value is accessed.
    ;; This is definitely not what we want.
    ;; Returning an empty list will at least stop the captured exception from cropping up
    ;; in random places when the future is accessed.
    (catch Throwable e
      (log/error "Ran into problems loading shrines and gate names...")
      (log/error e)
      (println (red "Cannot load shrine and gate names..."))
      (list))))

(defn build-shrines-and-gates-index
  [shrines-and-gates]
  (u/hashmap-with-keys #(seq (:uid %)) shrines-and-gates))

(defn load-db-in-background
  []
  (intern 'gd-edit.globals 'localization-table (future (u/log-exceptions (load-localization-files))))
  (intern 'gd-edit.globals 'db (future (u/log-exceptions (load-db @globals/localization-table))))
  (intern 'gd-edit.globals 'db-index (future (build-db-index @globals/db)))
  (intern 'gd-edit.globals 'db-and-index (future (build-db-and-index)))
  (intern 'gd-edit.globals 'shrines-and-gates (future (load-shrines-and-gates)))
  (intern 'gd-edit.globals 'shrines-and-gates-index (future (build-shrines-and-gates-index @globals/shrines-and-gates))))


(defn load-settings-file
  "Load settings file into globals/settings"
  []

  (reset! globals/settings (or (u/load-settings) {})))

(defn setting-savedir-clear!
  []
  (swap! globals/settings dissoc :save-dir))

(defn setting-savedir-set!
  [save-dir]
  (swap! globals/settings update :save-dir #(identity %2) save-dir))

(defn setting-gamedir-clear!
  []

  (log/debug "Clearing gamedir")
  (swap! globals/settings dissoc :game-dir))

(defn setting-gamedir-set!
  [game-dir]

  (log/debug "Setting gamedir")
  (u/log-exp game-dir)

  ;; Verify that this looks like a game directory
  (if-not (dirs/looks-like-game-dir game-dir)
    ;; If this isn't a valid game directory, print an error msg and exit.
    (u/print-line (format "\"%s\" does not look like a game directory" game-dir))

    (do
      ;; If this *is* a valid game directory, set it into a global variable.
      (swap! globals/settings update :game-dir #(identity %2) game-dir)

      ;; Reload game db using the new game directory.
      (load-db-in-background))))

(defn load-character-file
  [savepath]

  (reset! globals/character
          (gdc/load-character-file savepath))
  (reset! globals/last-loaded-character @globals/character)

  (when (not= (watcher/loaded-stash-filepath)
              (dirs/get-transfer-stash @globals/character))
    (watcher/tf-watcher-stop!))

  (if-not (watcher/tf-watcher-started?)
    (watcher/load-and-watch-transfer-stash!)
    (watcher/attach-transfer-stash-to-character!))

  (future (when-let [quest-progress (quest/load-annotated-quest-progress savepath)]
            (swap! globals/character assoc :quest quest-progress))))

(defn locate-character-files
  [character-name]
  (->> (dirs/get-all-save-file-dirs)
       (filter #(= (last (u/filepath->components (str %))) (str "_" character-name)))
       (map #(io/file % "player.gdc"))))

(defn character-name-from-dir
  [dir]
  (let [char-name (.getName dir)]
    (if (= \_ (first char-name))
      (subs char-name 1)
      char-name)))

(defn character-list
  []
  (map (fn
         [dir]
         {:character-name (character-name-from-dir dir)
          :gdc-path (.getPath (io/file dir "player.gdc"))
          :dir dir})
       (dirs/get-all-save-file-dirs)))
