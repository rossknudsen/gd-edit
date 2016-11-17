(ns gd-edit.command-handlers
  (:require [gd-edit.db-query :as query]
            [gd-edit.globals :as g]
            [gd-edit.utils :as u]
            [gd-edit.game-dirs :as dirs]
            [gd-edit.arc-reader :as arc-reader]
            [gd-edit.arz-reader :as arz-reader]
            [gd-edit.gdc-reader :as gdc]
            [jansi-clj.core :refer :all]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [gd-edit.globals :as globals]
            [gd-edit.utils :as utils])
  (:import  [java.io StringWriter]))

(defn paginate-next
  [page {:keys [pagination-size] :as query-state}]

  ;; How many results do we have?
  (let [size (count (:result query-state))

        ;; How many items have we shown?
        shown-so-far (* (inc (or page 0)) pagination-size)]

    ;; If we haven't shown everything yet...
    (if (< shown-so-far size)
      ;; Show the next page
      (inc page)

      ;; If we've already shown everything...
      ;; Go back to the beginning
      0
      )))

(defn paginate-next!
  [query-state-atom]
  (swap! query-state-atom update :page (fn [oldval] (paginate-next oldval @query-state-atom))))


(defn get-paginated-result
  [result {:keys [page pagination-size] :as query-state}]

  (->> result
       (drop (* page pagination-size))
       (take pagination-size)))


(defn set-new-query-result!
  [query-state-atom new-result query-string]

  (reset! g/query-state (assoc @g/query-state
                               :query-string query-string
                               :result new-result
                               :page 0)))


(defn print-result-records
  [results]
  (doseq [kv-map results]
    (println (:recordname kv-map))
    (doseq [[key value] (->> kv-map
                             seq
                             (filter #(not (keyword? (first %1))))
                             (sort-by first))]

      (println (format "\t%s: %s" key (yellow value))))

    (println)))


(defn print-paginated-result
  [query-state]

  (let [{:keys [result page pagination-size]} query-state
        paginated-result (get-paginated-result result query-state)
        start-entry (* page pagination-size)
        end-entry (+ start-entry (min (count paginated-result) pagination-size))]

    (print-result-records paginated-result)
    (println)
    (println (format "%d-%d / %d %s" start-entry end-entry (count result) (yellow "matched records")))))


(defn query-show-handler
  [[input tokens]]

  (paginate-next! g/query-state)
  (print-paginated-result @g/query-state))


(defn run-query
  [input]

  (let [predicates (try (query/query-string->query-predicates input)
                        (catch Throwable e (println (str "Query syntax error: " (.getMessage e)))))]

    (when (not (nil? predicates))

      ;; Run a query against the db using generated predicates
      (set-new-query-result! g/query-state
                             (query/query @g/db predicates)
                             input)

      (print-paginated-result @g/query-state))))

(defn query-comand-handler
  [[input tokens]]

  (cond
    (empty? input)
    (println
     "usage: q <target> <op> <value>
       <target> can be \"recordname\", \"value\", or \"key\"")

    (not (realized? globals/db))
    (println "db not yet loaded")

    :else
    (run-query input)))

(defn string-first-n-path-components
  "Given a path, return a path with the first n path components"
  [n path]

  (clojure.string/join "/" (take n (clojure.string/split path #"/"))))

(defn db-partial-record-path-matches
  [db path]

  (let [paths (->> db
                   (map :recordname))]

    ;; Generate a set of paths such that...
    (reduce (fn [accum item]

              ;; If the items first n components are a partial match to the user specified path
              (if (every? (fn [item-pair]
                            (u/ci-match (second item-pair) (first item-pair)))

                          (partition 2 (interleave (clojure.string/split path #"/")
                                                   (clojure.string/split item #"/"))))

                ;; Take the string of the first n+1 components and put it in the set
                (conj accum item)
                accum
                ))

            []
            paths)))

(defn db-show-handler
  [[input tokens]]

  (let [path (first tokens)]
    (if (nil? path)
      (println "Please provide some path to attempt matching")

      (let [sorted-matches

            ;; Grab a list of items from the db that partially matches the specified path
            (->> (db-partial-record-path-matches @gd-edit.globals/db path)

                 ;; Consolidate the search so we only have n+1 path components
                 (reduce (fn [accum item]
                           (conj accum
                                  (string-first-n-path-components (inc (count (clojure.string/split path #"/"))) item)))
                         #{})

                 ;; Sort the matches
                 (sort))

            ;; It's possible for the user to target a single record using "db show".
            ;; It that happens, we should just display the contents of that record instead of a
            ;; list of matched paths
            ;; Try to fetch the full recordname of the match now
            sole-match-recordname
            (if (= 1 (count sorted-matches))
              (first (db-partial-record-path-matches @gd-edit.globals/db path))
              nil)]

        ;; If we have a single record match
        (if sole-match-recordname

          ;; Locate the record by name
          (->> (filter (fn [item]
                         (= (:recordname item) sole-match-recordname))
                       @gd-edit.globals/db)

               ;; Print the record
               (print-result-records))


          ;; If we have multiple record matches...
          ;; Just print the names
          (do
            (doseq [item sorted-matches]
              (println item))
            (println)
            (println (count sorted-matches) " matches")))))))

(defn- last-path-component
  [path]

  (last (clojure.string/split path #"[/\\]")))

(declare load-character-file
         write-character-file
         character-selection-screen!
         character-manipulation-screen!)

(defn- character-manipulation-screen
  []

  {:display-fn
   (fn []
     (println "Character: " (:character-name @globals/character)))

   :choice-map [["r" "reload" (fn [] (load-character-file @globals/last-character-load-path))]
                ["w" "write" (fn[] (write-character-file @globals/character))]]})

(defn- character-selection-screen
  []

  ;; grab a list save directories where a "player.gdc" file exists
  (let [save-dirs (->> (dirs/get-save-dir)
                       (io/file)
                       (.listFiles)
                       (filter #(.isDirectory %1))
                       (filter #(.exists (io/file %1 "player.gdc"))))]

    {:display-fn
     (fn []
       (println "Please choose a character to load:"))

     ;; generate the menu choices
     ;; reduce over save-dirs with each item being [index save-dir-item]
     :choice-map (reduce
                  (fn [accum [idx dir]]
                    (let [display-idx (inc idx)]

                      (conj accum
                            [(str display-idx)                    ; command string
                             (last-path-component (.getPath dir)) ; menu display string
                             (fn []                               ; function to run when selected
                               (let [savepath (.getPath (io/file dir "player.gdc"))]
                                 (load-character-file savepath)
                                 (character-manipulation-screen!))
                               )])))
                  []
                  (map-indexed vector save-dirs))
     }))

(defn character-selection-screen! [] (reset! gd-edit.globals/menu (character-selection-screen)))
(defn character-manipulation-screen! [] (reset! gd-edit.globals/menu (character-manipulation-screen)))

(defn choose-character-handler
  [[input tokens]]

  character-selection-screen!)

(defn- character-loaded?
  []
  (and (not (nil? @gd-edit.globals/character))
       (not (empty? @gd-edit.globals/character))))

(defn- keyword->str
  [k]
  (subs (str k) 1))

(defn- without-meta-fields
  [kv-pair]

  (not (.startsWith (str (first kv-pair)) ":meta-")))

(defn print-character
  [character-map & {:keys [skip-item-count]
                    :or {skip-item-count false}}]

  ;; We want to be able to show a nice, easy to read list...
  ;; To do this, we first need to figure out how long the keyname is
  ;; Once we determine this, we can pad the keynames.
  (let [character (->> character-map
                       (filter without-meta-fields)
                       (sort-by first))

        max-key-length (reduce
                        (fn [max-length key-str]
                          (if (> (count key-str) max-length)
                            (count key-str)
                            max-length))
                        0

                        ;; Map the keys to a more readable string format
                        (->> character
                             (keys)
                             (map keyword->str))
                        )]

    (doseq [[key value] character]
      (println

       ;; Print the key name
       (format (format "%%%ds :" (+ max-key-length 2))
               (keyword->str key))

       ;; Print the value
       (cond
         (coll? value)
         (format "collection of %d items" (count value))

         (and (string? value) (empty? value))
         "\"\""

         :else
         (yellow value))))

    (when-not skip-item-count
      (newline)
      (println (format (format "%%%dd" (+ max-key-length 2)) (count character)) "fields"))))

(def print-map print-character)

(defn str-without-dash
  [str]
  (string/replace str "-" ""))

(defn- partially-match-key
  "Given a map and a partial key, return a list of pairs that partially matches the key"
  [m search-target]

  (let [haystack (filter without-meta-fields m)

        ;; Locate all partial matches
        partial-matches (filter (fn [[key value]]
                                  (let [k-str (keyword->str key)]
                                    (or (u/ci-match k-str search-target)
                                        (u/ci-match (str-without-dash k-str) search-target))))
                                haystack)

        ;; Are any of the partial matches actual exact matches?
        ;; If we don't look for
        exact-match (filter (fn [[key value]]
                              (let [k-str (keyword->str key)]
                                (or (and (u/ci-match k-str search-target)
                                         (= (count k-str) (count search-target)))
                                    (and (u/ci-match (str-without-dash k-str) search-target)
                                         (= (count (str-without-dash k-str)) (count search-target))))))
                            partial-matches)]

    ;; If we can find an exact match, use that.
    ;; Otherwise, return the partial matches
    (if (= (count exact-match) 1)
      exact-match
      partial-matches)))

(defn- coerce-to-int
  [input]

  (cond
    (number? input)
    (int input)

    (string? input)
    (Integer/parseInt input)

    :else
    (throw (Throwable. "Can't deal with input"))))

(defn- nav-into
  [coll key]

  (cond
    ;; If we're looking at a sequential collection, try to interpret key as an index
    (sequential? coll)
    (get coll (coerce-to-int key))

    (associative? coll)
    (get coll key)

    :else
    (throw (Throwable. "Can't deal with coll with type: " (type coll)))))

(defn walk-structure
  [m ks-all]

  (letfn [;; We want to return a slightly more complex set of data to the caller.
          ;; This helper function helps us construct the return map so we don't have
          ;; to sprinkle this everywhere.
          (return-result [result end-ks actual-path & others]
            (merge {;; Report the result the caller said to report
                    :status result

                    ;; Calculate the longest path traversed
                    :longest-path (take (- (count ks-all) (count end-ks)) ks-all)
                    :actual-path actual-path
                    }

                   (first others))
            )]
    ;; For each item in the specified ks
    ;; Try to iterate a level deeper using the next key
    (loop [cursor m
           ks ks-all
           actual-path []
           ]

      ;; Which key are we trying to navigate to?
      (let [k (first ks)]
        (cond
          ;; Did we try navigating into a location that doesn't exist?
          (nil? cursor)
          (return-result :not-found ks actual-path)

          ;; Did we exhaust all the keys?
          ;; If so, we're done navigating into the hierarchy.
          ;; The cursor should be pointing at the item the user wants
          (nil? k)
          (return-result :found ks actual-path {:found-item cursor})

          ;; If we have a sequential collection, just try to navigate into
          ;; the collection with the key
          (sequential? cursor)
          (recur (nav-into cursor k) (rest ks) (conj actual-path (coerce-to-int k)))

          ;; If we're looking at an associative collection, then we want to
          ;; perform partial matching on the current key
          (associative? cursor)
          (let [matches (partially-match-key cursor k)]
            (cond
              ;; If we can't get a match at all, we cannot navigate to the key
              (= (count matches) 0)
              (return-result :not-found (rest ks) actual-path)

              ;; If we have more than one match, tell the caller we cannot
              ;; resolve this ambiguity.
              (> (count matches) 1)
              (return-result :too-many-matches (rest ks) actual-path {:ambiguous-matches matches})

              :else
              (let [[matched-key matched-value] (first matches)]
                (recur matched-value (rest ks) (conj actual-path matched-key))))))))))


(defn print-details
  [data]

  (assert (associative? data))
  (assert (= (count data) 1))

  (let [[key value] (first data)]
    (println (format "%s:" (keyword->str key)))

    (cond
      (or (number? value) (string? value))
      (do
        (print "        ")
        (println value))

      (sequential? value)
      (do
        (u/doseq-indexed i [item value]
                         (println i ":")
                         (cond
                           (sequential? item)
                           (println
                            (format "collection of %d items" (count item)))

                           (associative? item)
                           (do
                             (print-map item :skip-item-count true)
                             (if-not (= item (last value))
                               (newline)))

                           :else
                           (throw (Throwable. "Don't know how to print details for a sequence of this type yet!")))
                         )))))


(defn print-indent
  [indent-level]

  (dotimes [i indent-level]
    (print "    ")))

(defn print-object
  [obj]

  (let [t (type obj)]
    (cond
      (or (number? obj) (string? obj))
      (do
        (println obj))

      (sequential? obj)
      (u/doseq-indexed i [item obj]
                       (print (format
                               (format "%%%dd: " (-> (count obj)
                                                     (Math/log10)
                                                     (Math/ceil)
                                                     (int)))
                               i))
                       (let [item-type (type item)]
                         (cond
                           (= u/byte-array-type item-type)
                           (println "byte array of size" (count item))

                           (associative? item)
                           (do
                             (newline)
                             (print-map item :skip-item-count true)
                             (if-not (= i (dec (count obj)))
                               (newline))
                             )

                           :else
                           (println item))))

      (associative? obj)
      (print-map obj)

      :else
      (throw (Throwable. "Unhandled case"))
      )))

(defn print-ambiguous-walk-result
  [result]

  (println (format "Cannot traverse path because \"%s\""
                   (clojure.string/join "/" (:longest-path result)))
           "matches more than one item:")

  (doseq [ambiguous-item (->> (:ambiguous-matches result)
                              (keys)
                              (map keyword->str))]
    (print-indent 1)
    (println ambiguous-item)))

(defn show-handler
  [[input tokens]]

  (cond
    ;; If the character hasn't been loaded...
    ;; Move to the character selection screen first
    (not (character-loaded?))
    (character-selection-screen!)

    ;; The character is loaded
    ;; If there aren't any filter conditions, just display all the character fields
    (= (count tokens) 0)
    (print-character @globals/character)

    :else
    (do
      ;; Split a path into components.
      ;; We're going to use these as keys to navigate into the character sheet
      (let [path-keys (string/split (first tokens) #"/")
            base-obj (if-not (> (count path-keys) 1)
                        @globals/character

                        ;; If the user supplied a long path, we should try to walk the
                        ;; data structure first and arrive at a base object
                        (let [result (walk-structure @globals/character (butlast path-keys))
                              {:keys [status found-item]} result]

                          (cond
                            (= status :not-found)
                            (println "No matches found")

                            (= status :too-many-matches)
                            (print-ambiguous-walk-result result)

                            :else
                            (:found-item result)))
              )]

        ;; Now that we've found a base object to navigate into...
        (when-not (nil? base-obj)
          ;; Walk into it one more level...
          (let [result (walk-structure base-obj [(last path-keys)])
                {:keys [status]} result

                matched-obj (cond
                              (= status :not-found)
                              (println "No matches found")

                              (= status :too-many-matches)
                              (:ambiguous-matches result)

                              (= status :found)
                              (:found-item result)

                              :else
                              (throw (Throwable. "Unhandled case")))
                ]

            ;; We've successfully navigated to an object
            ;; This might be a piece of data in the character sheet or a list of partially matched result
            ;; for some structure represented as a map
            (print-object matched-obj)))))))


(defn coerce-to-type
  "Given an value as a string, return the value after coercing it to the correct type."
  [val-str type]

  (cond
    ;; If the caller asked to convert the value to a string,
    ;; we don't have to do anything because the value should already be a string
    (= java.lang.String type)
    val-str

    (= java.lang.Float type)
    (Float/parseFloat val-str)

    (= java.lang.Integer type)
    (Integer/parseInt val-str)))


(defn set-handler
  [[input tokens]]

  (cond
    ;; If the character hasn't been loaded...
    ;; Move to the character selection screen first
    (not (character-loaded?))
    (character-selection-screen!)

    (not= (count tokens) 2)
    (println "Usage: show <path> <new-value>")

    :else
    (do
      ;; Split a path into components.
      ;; We're going to use these as keys to navigate into the character sheet
      (let [path-keys (string/split (first tokens) #"/")
            walk-result (walk-structure @globals/character path-keys)
            {:keys [status]} walk-result
            ]

        (cond
          (= status :not-found)
          (println "No matches found")

          (= status :too-many-matches)
          (print-ambiguous-walk-result walk-result)

          (= status :found)
          (do
            ;; We found the value the user wanted to update
            (let [value (:found-item walk-result)
                  ;; What is the type of the value? We'll have to coerce the supplied new value
                  ;; to the same type first.
                  newval (coerce-to-type (second tokens) (type value))]

              ;; Set the new value into the character sheet
              (reset! globals/character
                      (update-in @globals/character (:actual-path walk-result) (fn [oldval] newval)))
              nil
              ))

          :else
          (throw (Throwable. "Unhandled case")))
        ))))

(defn- get-savepath
  [character-name]
  (io/file (dirs/get-save-dir) (format "_%s" character-name) "player.gdc"))

(defn- get-new-backup-path
  [character-name]

  ;; Grab all sibling files of the character save path
  (let [sibling-files (-> (get-savepath character-name)
                          (.getParentFile)
                          (.listFiles))

        save-backups (->> sibling-files
                          (filter #(.isFile %1))
                          (filter #(not (nil? (re-matches #"player\.gdc\..*$" (.getName %1))))))]

    (format "%s.bak%d" (get-savepath character-name) (count save-backups))))

(defn- load-character-file
  [savepath]

  (reset! globals/character
          (gdc/load-character-file savepath))
  (reset! globals/last-character-load-path
          savepath))

(defn- write-character-file
  [character]

  ;; Figure out where we're going to write to
  (let [character-name (:character-name character)
        savepath (get-savepath character-name)

        ;; In case the user renamed the character, the save path may not exist at all
        ;; Make sure all parent directories are created
        _ (io/make-parents savepath)

        backup-path (io/file (get-new-backup-path character-name))]

    ;; Backup the current save file
    (when (.exists savepath)
      (.renameTo savepath backup-path))

    ;; Write the new character file
    (gd-edit.gdc-reader/write-character-file character (.getCanonicalPath savepath))))

(defn write-handler
  [[input tokens]]

  (if (not (character-loaded?))
    (println "Don't have a character loaded yet!")

    (write-character-file @globals/character)))


(defn load-db
  []

  (let [[localization-load-time localization-table]
        (utils/timed
         (arc-reader/load-localization-table (dirs/get-localization-filepath)))

        [db-load-time db]
        (utils/timed
         (arz-reader/load-game-db (dirs/get-db-filepath)
                                  localization-table))]

    ;; (println (count localization-table)
    ;;          "localization strings loaded in"
    ;;          (format "%.3f" (utils/nanotime->secs localization-load-time))
    ;;          "seconds")

    ;; (println (count db)
    ;;          "records loaded in"
    ;;          (format "%.3f" (utils/nanotime->secs db-load-time))
    ;;          "seconds")

    ;; (println)
    ;; (println "Ready to rock!")
    ;; (println)

    db
  ))

#_(write-handler [nil nil])

#_(choose-character-handler [nil nil])
#_(show-handler [nil nil])
#_(show-handler [nil ["stash"]])
#_(show-handler [nil ["stash-items"]])
#_(show-handler [nil ["stash/50"]])
#_(show-handler [nil ["stash-items/50"]])
#_(show-handler [nil ["stash-items/50/basename"]])
#_(show-handler [nil ["skillsets"]])

#_(set-handler [nil ["skillpoints" "12"]])
#_(set-handler [nil ["stashitems/50/basename" "123"]])
