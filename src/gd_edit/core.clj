(ns gd-edit.core
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [gd-edit
             [arc-reader :as arc-reader]
             [arz-reader :as arz-reader]
             [command-handlers :as handlers]
             [game-dirs :as dirs]
             [globals :as globals]
             [jline :as jl]
             [utils :as utils]
             [self-update :as su]]
            [jansi-clj.core :refer :all]
            [gd-edit.utils :as u]
            [clojure.core.async :as async :refer [thread >!!]]
            [progress.file :as progress]
            [clojure.string :as str])
  (:import java.nio.ByteBuffer
           java.nio.channels.FileChannel
           java.lang.ProcessBuilder
           [java.nio.file Files FileSystems Path Paths StandardOpenOption]
           [java.time Instant ZonedDateTime ZoneId]
           java.util.Date))

(defn- strip-quotes
  "Strip quotes from a string"
  [value]
  (clojure.string/replace value #"^\"|\"$" ""))

(defn- tokenize-input
  [input]

  [input
   (->> (into [] (re-seq #"\"[^\"]+\"|\S+" input))
        (map strip-quotes))])

(defn- repl-read
  []

  ;; Read a line
  (tokenize-input (jl/readline (green "> "))))


(defn split-at-space
  [str]
  (clojure.string/split str #"\s+"))

(def command-map
  {
   ["exit"]  (fn [input] (System/exit 0))
   ["q"]     (fn [input] (handlers/query-comand-handler input))
   ["qshow"] (fn [input] (handlers/query-show-handler input))
   ["qn"]    (fn [input] (handlers/query-show-handler input))
   ["db"]    (fn [input] (handlers/db-show-handler input))
   ["db" "show"] (fn [input] (handlers/db-show-handler input))
   ["show"]  (fn [input] (handlers/show-handler input))
   ["set"]   (fn [input] (handlers/set-handler input))
   ["load"]  (fn [input] (handlers/choose-character-handler input))
   ["write"] (fn [input] (handlers/write-handler input))
   ["class"] (fn [input] (handlers/class-handler input))
   ["class" "add"] (fn [input] (handlers/class-add-handler input))
   ["class" "remove"] (fn [input] (handlers/class-remove-handler input))
   ["gamedir"] (fn [input] (handlers/gamedir-handler input))
   ["gamedir" "clear"] (fn [input] (handlers/gamedir-clear-handler input))
   ["savedir"] (fn [input] (handlers/savedir-handler input))
   ["savedir" "clear"] (fn [input] (handlers/savedir-clear-handler input))
   ["update"] (fn [input] (handlers/update-handler input))
   ["help"] (fn [input] (handlers/help-handler input))
   })

(defn- find-command
  "Try to find the \"longest\" command match"
  [tokens command-map]
  ;; We're trying to find the most specific match in the command map.
  ;; This means we can put command handlers like "q" and "q show"
  ;; directly in the command map and figure out which one should be
  ;; called
  (reduce
   (fn [accum item]
     ;; accum will be the longest match we found so far
     ;; Check if we can match against a command if we put one more token
     ;; into the command
     (let [command (conj accum item)]

       ;; Is the new command in the command-map?
       (if (command-map command)
         ;; If so, we've found a slightly longer match
         command

         ;; If not, we can't find a longer match and we're
         ;; done with the reduce
         (reduced accum)
         )))
   []
   tokens))

(defn- find-menu-command
  "Returns a menu item that matches the given target string, or nil"
  [target-str menu]

  (let [matched-menu-items (filter
                            (fn [[cmd-str]]
                              (= cmd-str target-str))

                            (:choice-map menu))]

    ;; If we can find a match, return the first match
    ;; This means if some menu items have the same command string,
    ;; only the first one will ever be picked
    (if-not (empty? matched-menu-items)
      (first matched-menu-items)
      nil)))

(defn- repl-print-menu
  "Print the given menu.

  A menu is a hashmap that has the following fields:

  :display-fn => called without parameters when available
  :choice-map => vector of [command-str display-string choice-function]

  Together, these fields are used to help the repl dynamically present
  information and alter the command map in a stateful manner. "
  [menu]

  (let [{:keys [display-fn choice-map]} menu]
    ;; Run the display function if available
    (if-not (nil? display-fn)
      (display-fn))

    ;; Display the choice-map
    (if-not (empty? choice-map)
      (do
        (doseq [[cmd-str disp-str] choice-map]
          (println (format "%s) %s" cmd-str disp-str)))
        (println)))))

(defn- repl-eval
  [[input tokens :as input-vec] command-map]

  ;; Try to find the "longest" command match
  ;; Basically, we're trying to find the most specific match.
  ;; This means we can put command handlers like "q" and "q show"
  ;; directly in the command map and figure out which one should be
  ;; called
  (let [menu-cmd (find-menu-command (first tokens) @globals/menu)
        menu-handler (if-not (nil? menu-cmd)
                       (nth menu-cmd 2)
                       nil)

        command (find-command tokens command-map)
        _ (newline)
        command-handler (command-map command)]

    (cond
      ;; if the entered command matches a menu item, run the handler function
      (not (nil? menu-handler))
      (menu-handler)

      ;; Otherwise, if the tokens can match something in the global command map,
      ;; run that.
      ;;
      ;; Remove the tokens that represent the command itself
      ;; They shouldn't be passed to the command handlers
      (not (nil? command-handler))
      (let [param-tokens (drop (count command) tokens)
            command-input-string (string/join " " param-tokens)]
        (command-handler [command-input-string param-tokens]))

      :else
      (println "Don't know how to handle this command"))))

(defn- repl-print-notification
  [chan]

  ;; Try to pull a message out of the notification channel
  (when-let [msg (async/poll! chan)]

    ;; If a message can be retrieved, display it now.
    (println msg)))

(defn- repl-iter
  "Runs one repl iteration. Useful when the program is run from the repl"
  []

  (repl-print-menu @globals/menu)
  (repl-print-notification globals/notification-chan)
  (repl-eval (repl-read) command-map)
  (println))

(defn- repl
  []

  (while true
    (try
      (repl-iter)
      (catch Exception e
        (do
          (println "caught exception: " (.getMessage e))
          (clojure.stacktrace/print-stack-trace e)
          (newline))))))

(declare startup-sanity-checks)

(defn- initialize
  []

  ;; Try to load the settings file if it exists
  (handlers/load-settings-file)

  ;; Run sanity checks and print any error messages
  (startup-sanity-checks)

  ;; Try to load the game db
  (handlers/load-db-in-background)

  ;; Setup the first screen in the program
  (handlers/character-selection-screen!)

  ;; Remove any left over restart scripts from last time we ran "update"
  (su/cleanup-restart-script)
  )

(defn- print-build-info
  []

  (if-let [info-file (io/resource "build.edn")]
    (let [build-info (edn/read-string (slurp info-file))]
      (println (bold (black (format "%s %s [build %s]" (build-info :app-name) (build-info :version)(build-info :sha))))))))

(defn- check-save-dir-found?!
  [verbose]

  (let [save-file-dirs (dirs/get-all-save-file-dirs)]
    (if (empty? save-file-dirs)
      (do
        (println (red "No save files can be located"))
        (println "The following locations were checked:")
        (doseq [loc (dirs/get-save-dir-search-list)]
          (println (str "    " loc)))
        false ;; return false to indicate that we failed the test
        )

      (do
        (when verbose
          (let [actual-dirs (reduce (fn [result item]
                                      (conj result (.getParent (io/file item))))
                                    #{}
                                    save-file-dirs)]
            (println "save directories:")
            ;;(println actual-dirs)
            (doseq [loc actual-dirs]
              (println (str "    " loc)))
          ))
        true))))

(defn- check-game-dir-found?!
  [verbose]

  (if-not (dirs/get-game-dir)
    (do
      (println (red "Game directory cannot be located"))
      (println "The following locations were checked:")
      (doseq [dir (dirs/get-game-dir-search-list)]
              (println (str "    " dir)))
      (newline)
      (println "Some editor functions such as db queries and changing items and equipment won't work properly.")
      false)

    (do
      (when verbose
        (let [actual-dirs (reduce (fn [result item]
                                    (conj result (.getParent (io/file item))))
                                  #{}
                                  [(-> (dirs/get-db-filepath)
                                       (.getParent))])]
          (println "game directory:")
          (doseq [loc actual-dirs]
            (println (str "    " loc)))
          ))
      true)))

(defn- startup-sanity-checks
  []

  (let [passes-required-checks (check-save-dir-found?! true)]
    (newline)
    (check-game-dir-found?! true)
    (newline)
    passes-required-checks))

(defn- should-check-for-update?
  [last-check-time]

  (let [;; When did we check for a verion the last time?
        last-check-time (if (nil? last-check-time)
                          (Instant/EPOCH)
                          (.toInstant last-check-time))

        ;; When is the next check due?
        next-check-time (-> (ZonedDateTime/ofInstant last-check-time (ZoneId/systemDefault))
                            (.plusDays 1)
                            (.toInstant))]

    ;; If we've gone past the next check time...
    (if (.isAfter (Instant/now) next-check-time)

      ;; Say we should check for an update...
      true
      false)))

(defn- notify-repl-if-latest-version-available
  "Check if the latest version is available.
  If so, send a notification to the repl.
  If not, do nothing."
  []

  ;; We want to throttle update checks to some sane interval
  ;; Check if we should actually check for an update
  (when (should-check-for-update? (:last-version-check @globals/settings))

    ;; Check if there is a new version available
    (let [[status build-info] (su/fetch-has-new-version?)]

      ;; Update the last check time in the settings file
      (swap! globals/settings assoc :last-version-check (Date.))
      (u/write-settings @globals/settings)

      ;; Notify the user there is a new version
      (if (= status :new-version-available)
        (>!! globals/notification-chan
             (green
              (str/join "\n"
                        ["New version available!"
                         "Run the \"update\" command to get it!"])))))))

(defn -main
  [& args]

  ;; Enable cross-platform ansi color handling
  (alter-var-root #'gd-edit.jline/use-jline (fn[oldval] true))
  (jansi-clj.core/install!)

  ;; (println (clojure-version))
  (print-build-info)
  (println)


  (do
    (initialize)

    (thread (notify-repl-if-latest-version-available))

    (repl)))

#_(initialize)
#_(time (do
          (reset! gd-edit.globals/character
                  (gd-edit.gdc-reader/load-character-file
                   (-> (dirs/get-save-dir-search-list)
                        (first)
                        (io/file "_Odie/player.gdc")
                        (.getPath)
                        )))
          nil))
