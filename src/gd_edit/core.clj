(ns gd-edit.core
  (:gen-class)
  (:require [clojure.core.async :as async :refer [>!! thread]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [gd-edit.commands
             [choose-character :as commands.choose-character]
             [class :as commands.class]
             [db :as commands.db]
             [diag :as commands.diag]
             [find :as commands.find]
             [gamedir :as commands.gamedir]
             [help :as commands.help]
             [item :as commands.item]
             [level :as commands.level]
             [log :as commands.log]
             [mod :as commands.mod]
             [query :as commands.query]
             [respec :as commands.respec]
             [savedir :as commands.savedir]
             [set :as commands.set]
             [show :as commands.show]
             [update :as commands.update]
             [write :as commands.write]
             [batch :as commands.batch]
             [remove :as commands.remove]
             [delete :as commands.delete]
             [shrine :as commands.shrine]
             [create-character :as commands.create-character]
             ]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.globals :as globals]
            [gd-edit.jline :as jl]
            [gd-edit.self-update :as su]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [red green yellow bold black]]
            [taoensso.timbre :as t]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [gd-edit.app-util :as au])
  (:import [java.time Instant ZonedDateTime ZoneId]
           java.util.Date))

(def ^:dynamic *commands* (atom (list)))

(defn- strip-quotes
  "Strip quotes from a string"
  [value]
  (str/replace value #"^\"|\"$" ""))

(defn- tokenize-input
  [input]

  (if (nil? input)
    [nil nil]
    [input
     (->> (into [] (re-seq #"\"[^\"]+\"|\S+" input))
          (map strip-quotes))]))

(defn repl-read
  []
  ;; Read a line

  (tokenize-input
   (cond
     ;; Grab a command from the *commands* list if available
     (not-empty @*commands*)
     (do
       (u/print- (green "> "))
       (let [line (first @*commands*)]
         (u/print-line line)
         (swap! *commands* rest)
         line))

     ;; otherwise, try to get a command from the commandline
     :else
     (jl/readline (green "> ")))))

(defn split-at-space
  [str]
  (str/split str #"\s+"))

(def command-map
  {["exit"]  (fn [_] (System/exit 0))
   ["q"]     (fn [input] (commands.query/query-command-handler input))
   ["qshow"] (fn [input] (commands.query/query-show-handler input))
   ["qn"]    (fn [input] (commands.query/query-show-handler input))
   ["db"]    (fn [input] (commands.db/db-show-handler input))
   ["db" "show"] (fn [input] (commands.db/db-show-handler input))
   ["show"]  (fn [input] (commands.show/show-handler input))
   ["ls"]    (fn [input] (commands.show/show-handler input))
   ["set"]   (fn [input] (commands.set/set-handler input))
   ["remove"] (fn [input] (commands.remove/remove-handler input))
   ["rm"] (fn [input] (commands.remove/remove-handler input))
   ["delete"] (fn [input] (commands.delete/delete-handler input))
   ["find"]  (fn [input] (commands.find/find-handler input))
   ["load"]  (fn [input] (commands.choose-character/choose-character-handler input))
   ["write"] (fn [input] (commands.write/write-handler input))
   ["write" "stash"] (fn [input] (commands.write/write-stash-handler input))
   ["ws"] (fn [input] (commands.write/write-stash-handler input))
   ["write" "character-list"] (fn [input] (commands.write/write-character-list input))
   ["class"] (fn [input] (commands.class/class-handler input))
   ["class" "list"] (fn [input] (commands.class/class-list-handler input))
   ["class" "add"]  (fn [input] (commands.class/class-add-handler input))
   ["class" "remove"]  (fn [input] (commands.class/class-remove-handler input))
   ["gamedir"] (fn [input] (commands.gamedir/gamedir-handler input))
   ["gamedir" "clear"] (fn [input] (commands.gamedir/gamedir-clear-handler input))
   ["savedir"] (fn [input] (commands.savedir/savedir-handler input))
   ["savedir" "clear"] (fn [input] (commands.savedir/savedir-clear-handler input))
   ["mod"]     (fn [input] (commands.mod/mod-handler input))
   ["mod" "pick"]  (fn [input] (commands.mod/mod-pick-handler input))
   ["mod" "clear"] (fn [input] (commands.mod/mod-clear-handler input))
   ["level"]   (fn [input] (commands.level/level-handler input))
   ["respec"]  (fn [input] (commands.respec/respec-handler input))
   ["log"]     (fn [input] (commands.log/log-handler input))
   ["update"]  (fn [input] (commands.update/update-handler input))
   ["help"]    (fn [input] (commands.help/help-handler input))
   ["diag"]    (fn [input] (commands.diag/diag-handler input))
   ["batch"]    (fn [input] (commands.batch/batch-handler input))
   ["batch" "item"]    (fn [input] (commands.item/batch-item-handler input))
   ["batch" "character"]    (fn [input] (commands.batch/batch-character-handler input))
   ["swap-variant"] (fn [input] (commands.item/swap-variant-handler input))
   ["shrine" "list"] (fn [input] (commands.shrine/shrine-list-handler input))
   ["gate" "list"] (fn [input] (commands.shrine/gate-list-handler input))
   ["make-char"] (fn [input] (commands.create-character/create-character-handler input))})

(defn- find-command
  "Try to find the \"longest\" command match"
  [tokens command-map]
  ;; We're trying to find the most specific match in the command map.
  ;; This means we can put command handlers like "q" and "q show"
  ;; directly in the command map and figure out which one should be
  ;; called
  (reduce
   (fn [accum tok-count]
     ;; accum will be the longest match we found so far
     ;; Check if we can match against a command if we put one more token
     ;; into the command
     (let [command (take tok-count tokens)]
       ;; Is the new command in the command-map?
       (if (command-map command)
         ;; If so, we've found a slightly longer match
         command

         accum
         )))
   []
   (range (max (count tokens) 3))))

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
          (u/print-line (format "%s) %s" cmd-str disp-str)))
        (u/print-line)))))

(defn repl-eval
  [[input tokens] command-map]

  (t/debug "Evaluating command from user:")
  (u/log-exp input)
  (u/log-exp tokens)

  ;; Input is nil when the user hits ctrl-d
  ;; In this case, exit the program
  (when (nil? input)
    (System/exit 0))

  (when-not (empty? (str/trim input))
    ;; Try to find the "longest" command match
    ;; Basically, we're trying to find the most specific match.
    ;; This means we can put command handlers like "q" and "q show"
    ;; directly in the command map and figure out which one should be
    ;; called
    (let [menu-cmd (find-menu-command (first tokens) (last @globals/menu-stack))
          menu-handler (if-not (nil? menu-cmd)
                         (nth menu-cmd 2)
                         nil)

          command (find-command tokens command-map)
          _ (u/newline-)
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
              command-input-string (str/trim (subs input (->> command
                                                                 (str/join " ")
                                                                 (count))))]
          (command-handler [command-input-string param-tokens]))

        :else
        (u/print-line "Don't know how to handle this command")))))

(defn- repl-print-notification
  [chan]

  ;; Try to pull a message out of the notification channel
  (when-let [msg (async/poll! chan)]

    ;; If a message can be retrieved, display it now.
    (u/print-line msg)))

(defn repl-iter
  "Runs one repl iteration. Useful when the program is run from the repl"
  []

  (repl-print-menu (last @globals/menu-stack))
  (repl-print-notification globals/notification-chan)
  (let [c (repl-read)]
    (repl-eval c command-map)
    )

  ;; (repl-eval (repl-read) command-map)
  (u/print-line))

(defn handle-repl-exceptions
  [e]
  (let [data (ex-data e)]
    (cond
      (= (:cause data) :db-not-loaded)
      (do
        (u/print-line (red "Oops!"))
        (u/print-line "This command requires some game data to function correctly.")
        (u/print-line "Please help the editor find the game installation directory using the 'gamedir' command.")
        (u/print-line "See 'help gamedir' for more info.")
        (u/newline-))

      ;; The exception has no additional data attached...
      ;; Print out the stacktrace so it can be diagnosed by *somebody*
      :else
      (do
        (u/print-line "Caught exception:" (.getMessage e))
        (print-stack-trace e)
        (u/newline-)

        (t/info e)))))

(defn repl
  []

  (while true
    (try

      ;; Run a repl iteration
      (repl-iter)

      ;; Handle potential errors
      (catch Exception e
        (handle-repl-exceptions e)))))

(defn batch-repl
  [commands]

  (with-bindings {#'*commands* (atom commands)}
    (while (not-empty @*commands*)
        (try

          ;; Run a repl iteration
          (repl-iter)

          ;; Handle potential errors
          (catch Exception e
            (handle-repl-exceptions e))))))

(declare startup-sanity-checks print-build-info log-build-info)

(defn- get-build-info-str
  []

  (if-let [info-file (io/resource "build.edn")]
    (let [build-info (edn/read-string (slurp info-file))]
      (format "%s %s [build %s]" (build-info :app-name) (build-info :version)(build-info :sha)))))

(defn- log-build-info
  []

  (if-let [build-info (get-build-info-str)]
    (t/info build-info)
    (t/info "No build info available")))

(defn- print-build-info
  []

  (if-let [build-info (get-build-info-str)]
    (u/print-line (yellow build-info))
    (u/print-line (red "No build info available"))))

(defn- check-save-dir-found?!
  [verbose]

  (let [save-file-dirs (dirs/get-all-save-file-dirs)]
    (if (empty? save-file-dirs)
      (do
        (u/print-line (red "No save files can be located"))
        (u/print-line "The following locations were checked:")
        (doseq [loc (dirs/get-save-dir-search-list)]
          (u/print-line (str "    " loc)))
        false ;; return false to indicate that we failed the test
        )

      (do
        (when verbose
          (let [actual-dirs (reduce (fn [result item]
                                      (conj result (.getParent (io/file item))))
                                    #{}
                                    save-file-dirs)]
            (u/print-line "save directories:")
            ;;(u/print-line actual-dirs)
            (doseq [loc actual-dirs]
              (u/print-line (str "    " loc)))
          ))
        true))))

(defn- check-game-dir-found?!
  [verbose]

  (if-not (dirs/get-game-dir)
    (do
      (u/print-line (red "Game directory cannot be located"))
      (u/print-line "The following locations were checked:")
      (doseq [dir (dirs/get-game-dir-search-list)]
              (u/print-line (str "    " dir)))
      (u/newline-)
      (u/print-line "Some editor functions such as db queries and changing items and equipment won't work properly.")
      false)

    (do
      (when verbose
        (let [actual-dirs (reduce (fn [result item]
                                    (conj result (.getParent (io/file item))))
                                  #{}
                                  [(-> (io/file (dirs/get-game-dir) dirs/database-file)
                                       (.getParent))])]
          (u/print-line "game directory:")
          (doseq [loc actual-dirs]
            (u/print-line (str "    " loc)))
          ))
      true)))

(defn- startup-sanity-checks
  []

  (let [passes-required-checks (check-save-dir-found?! true)]
    (u/newline-)
    (check-game-dir-found?! true)
    (u/newline-)
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

      ;; Notify the user there is a new version
      (when (= status :new-version-available)
        (>!! globals/notification-chan
             (green
              (str/join "\n"
                        ["New version available!"
                         "Run the \"update\" command to get it!"])))))))

(defn setup-log
  ([]
   (setup-log :info))

  ([log-level]

   (let [log-filename "gd-edit.log"]

     (io/delete-file log-filename :quiet)

     (t/merge-config!
      {:appenders {:println {:enabled? false}
                   :spit (appenders/spit-appender {:fname log-filename})}})

     (t/set-level! log-level))))

(defn jvm-prop-as-str
  [prop-name]

  (format "%s: %s" prop-name (System/getProperty prop-name)))


(defn get-system-info
  []

  (let [jvm-props ["java.vm.name" "java.runtime.version" "os.name" "os.version"]
        os-info (java.lang.management.ManagementFactory/getOperatingSystemMXBean)]

    (partition 2
               (concat
                (interleave jvm-props
                            (map #(System/getProperty %) jvm-props))

                (list
                 "Free System Memory"
                 (u/try-or
                  (u/human-readable-byte-count
                   (.getFreePhysicalMemorySize os-info))
                  "Not available")

                 "Committed System Memory"
                 (u/try-or
                  (u/human-readable-byte-count
                   (.getCommittedVirtualMemorySize os-info))
                  "Not available")

                 "Total System Memory"
                 (u/try-or
                  (u/human-readable-byte-count
                   (.getTotalPhysicalMemorySize os-info))
                  "Not available")

                 "Free Swap Memory"
                 (u/try-or
                  (u/human-readable-byte-count
                   (.getFreeSwapSpaceSize os-info))
                  "Not available")

                 "Total Swap Memory"
                 (u/try-or
                  (u/human-readable-byte-count
                   (.getTotalSwapSpaceSize os-info))
                  "Not available")

                 "Current file handle"
                 (u/try-or
                  (.getOpenFileDescriptorCount os-info)
                  "Not available")

                 "Max file handle count"
                 (u/try-or
                  (.getMaxFileDescriptorCount os-info)
                  "Not available"))))))

(defn log-environment
  "Outputs some basic information regarding the running environment"
  []

  (let [sys-info (get-system-info)
        max-key-length (reduce max 0 (map (comp count first) sys-info))]

    (doseq [pair sys-info]
      (t/debug
       (format (format "%%-%ds : %%s" max-key-length)

               (str (first pair))
               (str (second pair)))))))

(defn- init-jansi
  []

  ;; Enable cross-platform ansi color handling
  (jl/initialize)
  (jansi-clj.core/install!))

(defn initialize
  []

  (setup-log)

  ;; Try to load the settings file if it exists
  (au/load-settings-file)

  (t/set-level! (or (@globals/settings :log-level) :info))

  ;; Settings should autosave when it is changed
  (add-watch globals/settings ::settings-autosave
             (fn [key settings old-state new-state]
               (when (not= old-state new-state)
                 (u/write-settings @globals/settings))))

  (print-build-info)
  (u/print-line)
  (future (log-build-info))
  (future (log-environment))

  ;; Run sanity checks and print any error messages
  (startup-sanity-checks)

  ;; Try to load the game db
  (au/load-db-in-background)

  ;; Setup the first screen in the program
  ;; based on if a character has already been loaded
  (commands.choose-character/character-selection-screen!)

  ;; Remove any left over restart scripts from last time we ran "update"
  (su/cleanup-restart-script))

(defn print-runtime-info
  []

  (pprint (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))
  (u/newline-)
  (pprint (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader))))
  (u/newline-))

(defn exit-unless-64-bit-runtime
  []

  (when (not= (System/getProperty "sun.arch.data.model") "64")
    (u/print-line (red "Sorry,") "the editor requires 64 bit Java/JVM.")

    (u/print-line "Your currently running:")
    (u/print-indent 1)
    (u/print-line (System/getProperty "java.vm.name"))
    (u/print-indent 1)
    (u/print-line (System/getProperty "java.runtime.version"))
    (u/print-line)

    (u/print-line "Installed at:")
    (u/print-indent 1)
    (u/print-line (System/getProperty "java.home"))


    (u/print-line)
    (u/print-line "Please download the 64 bit version from here:")
    (u/print-indent 1)
    (u/print-line "https://java.com/en/download/manual.jsp")
    (System/exit 1)))


(defn start-editor
  []

  (exit-unless-64-bit-runtime)

  (initialize)
  (u/print-line "Need help? Check the docs!\n\thttps://odie.github.io/gd-edit-docs/faq/\n")

  (thread (notify-repl-if-latest-version-available))

  (repl))

(def cli-options
  [["-f" "--file SAVE_FILE_PATH" "Save file to load on start"
    :parse-fn #(str/trim %)
    :validate [#(.exists (io/as-file %))
               "The specified save file doesn't exist."]]
   ["-b" "--batch BATCH_FILE_PATH" "Batch file to run"
    :parse-fn #(str/trim %)
    :validate [#(.exists (io/as-file %))
               "The specified batch file doesn't exist."]]
   ["-h" "--help" "Show this help text"]])

(defn -main
  [& args]


  (u/log-exceptions

   (init-jansi)

   (let [{:keys [arguments options summary]} (parse-opts args cli-options)]
     ;; Handle all the commandline params
     (cond
       (:help options) (do
                         (u/print-line "The valid options are:")
                         (u/print-line summary)
                         (System/exit 0))

       (:file options) (do
                         (au/load-character-file (:file options))
                         (start-editor))

       (not-empty arguments) (when (str/ends-with? (first arguments) ".json")
                               (initialize)
                               (commands.create-character/create-character-from-file (first arguments))
                               (System/exit 0))

       ;; If the user asked to start the editor in batch mode...
       ;; Open the file as a stream and replace stdin with it
       (:batch options)
       (with-open [is (io/input-stream (:batch options))]
         (jl/set-input is)
         (u/print-line (red "Starting in batch mode!"))
         (start-editor))

       ;; Otherwise, start the editor in interactive mode
       :else
       (start-editor)))))



;;===============================================================================
