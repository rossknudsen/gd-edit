(ns gd-edit.commands.class
  (:require [gd-edit.db-utils :as dbu]
            [clojure.string :as str]
            [gd-edit.utils :as u]
            [jansi-clj.core :refer [red green yellow]]
            [gd-edit.globals :as globals]
            [gd-edit.printer :as printer]))

(defn- clean-display-string
  "Remove any strange formatting symbols from a display string"
  [s]

  (str/replace s #"\^." ""))

(defn- character-classes-with-index
  "Returns a set of db records that represents the classes the player has taken"
  [character]

  (->> (map-indexed vector (character :skills))
       (filter (fn [[idx skill]] (= "Skill_Mastery" (-> (:skill-name skill)
                                                        (dbu/record-by-name)
                                                        (get "Class")))))))

(defn- character-classes
  "Returns a set of db records that represents the classes the player has taken"
  [character]

  (map second (character-classes-with-index character)))

(defn- db-class-ui-records
  []

  (->> (dbu/record-by-name "records/ui/skills/skills_mastertable.dbr")
       ;; Grab all the fields named "skillCtrlPaneXXX"
       (filter (fn [[k v]] (str/starts-with? (str k) "skillCtrlPane")))

       ;; sort by the number behind the "skillCtrlPane" string
       (sort-by (fn [[k v]]
                  (Integer/parseInt (subs k (count "skillCtrlPane")))))

       ;; Follow the link to the classtable
       (map (fn [[k v]] (dbu/record-by-name v)))
       (filter #(not (empty? %)))))

(defn- db-class-ui-record->class-name
  [class-ui-record]
  (clean-display-string (class-ui-record "skillTabTitle")))

(defn- db-class-ui-record->class-record
  [class-ui-record]

  (-> (class-ui-record "tabSkillButtons")
      (first)

      ;; Look up the records and grab the "skillName" field
      ;; This shoud give us the _classtraining db records
      (dbu/record-by-name)
      (get "skillName")
      (dbu/record-by-name)))

(defn- db-class-records
  []

  (->> (db-class-ui-records)
       (map db-class-ui-record->class-record)))

(defn- class-display-name-map
  "Returns a mapping from class record recordname => class name"
  []
  (->> (db-class-ui-records)
       (map (fn [ui-record]
              [(:recordname (db-class-ui-record->class-record ui-record))
               (db-class-ui-record->class-name ui-record)]))
       (into {})))

(defn- print-character-classes
  [character db]

  ;; Find all character "skills" that represents a class mastery

  (let [;; Generate a mapping from class record recordname => class name
        class-display-names (class-display-name-map)

        classes (->> (character-classes-with-index character)
                     (map (fn [[idx record]]
                            {:idx idx
                             :skill record
                             :skill-display-name (class-display-names (:skill-name record))
                             })))]

    ;; Print the display names
    (println "classes:")
    (if (empty? classes)
      (do
        (u/print-indent 1)
        (println (yellow "None")))

      (doseq [klass classes]
        (u/print-indent 1)
        (println (yellow (:skill-display-name klass)))
                 (format "(skills/%d)" (:idx klass)))
                 )))

(defn class-handler
  "Show the class of the loaded character"
  [[input tokens]]

  (print-character-classes @globals/character (dbu/db)))

(defn class-list-handler
  "Show the class of the loaded character"
  [[input tokens]]

  (println "Known classes:")
  (doseq [classname (->> (db-class-ui-records)
                         (map db-class-ui-record->class-name))]
    (u/print-indent 1)
    (println classname)))

(defn class-remove-by-name
  [character class-name]

  (let [skill-name-to-remove ((class-display-name-map) class-name)

        skill-to-remove (first (filter
                                (fn [skill]
                                  (= skill-name-to-remove (:skill-name skill)))
                                (@globals/character :skills)))]

    (if (nil? skill-to-remove)
      character
      (merge character
             {:skills (into (empty (:skills character))
                            (remove (fn [skill] (= skill skill-to-remove)) (:skills character)))
              :skill-points (+ (:skill-points character) (:level skill-to-remove))}))))


(defn class-remove-handler
  "Remove a class to the currently loaded character by partial name"
  [[input tokens]]

  (if (empty? tokens)
    (println "Please provide the partial name of the class to remove from the character")
    ;; Get the db record that represents the class mastery
    (let [class-to-remove (first tokens)

          matched-class (->> (db-class-ui-records)
                             (map db-class-ui-record->class-name)
                             (filter #(u/ci-match % class-to-remove))
                             (first))]

      (if (empty? matched-class)
        (println (format "\"%s\" doesn't match any of the known classes" class-to-remove))

        ;; Update the character
        (let [modified-character (class-remove-by-name @globals/character matched-class)]

          ;; Inform the user what happened
          (println "Removing class:" matched-class)
          (print-character-classes modified-character (dbu/db))

          (println)
          (println "Updating the following fields:")
          (printer/print-map-difference (clojure.data/diff @globals/character modified-character))

          ;; Actually update the loaded character
          (swap! globals/character (constantly modified-character)))))))


(defn class-add
  [character klass]

  (merge character
         ;; Adding a mastery requires investing at least 1 skill point in it
         ;; Otherwise, the game will just ignore the choice
         {:skills (conj (:skills character)
                        {:devotion-level 0
                         :devotion-experience 0
                         :skill-active false
                         :autocast-skill-name ""
                         :skill-transition false
                         :skill-name (:recordname klass),
                         :level 1
                         :sublevel 0
                         :autocast-controller-name ""
                         :enabled true})

          ;; Deduct a skill point if possible
          :skill-points (max (dec (:skill-points character)) 0)}))


(defn class-add-handler
  "Add a class to the currently loaded character by partial name"
  [[input tokens]]

  (if (empty? tokens)
    (println "Please provide the partial name of the class to remove from the character")
    ;; Get the db record that represents the class mastery
    (let [klass (->> (db-class-records)
                     (filter #(u/ci-match (%1 "skillDisplayName") (first tokens)))
                     (first))]

      (if (nil? klass)
        (println (format "\"%s\" doesn't match any of the known classes" (first tokens)))

        (let [perform-op (if-not (zero? (:skill-points @globals/character))
                           true
                           (do
                             (println (str/join "\n" ["You need at least 1 skill point to add new mastery."
                                                      "Adding a new mastery now will automatically add 1 skill point to your character."]))
                             (printer/prompt-yes-no "Really add class?")))]
          (when perform-op
            (let [modified-character (class-add @globals/character klass)]

              ;; Inform the user what happened
              (println "Adding class:" (klass "skillDisplayName"))
              (print-character-classes modified-character (dbu/db))

              (println)
              (println "Updating the following fields:")
              (printer/print-map-difference (clojure.data/diff @globals/character modified-character))

              ;; Actually update the loaded character
              (swap! globals/character (constantly modified-character)))))))))
