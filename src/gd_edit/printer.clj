(ns gd-edit.printer
  "Deals with printing output of structured data"
  (:require [gd-edit
             [db-utils :as dbu]
             [utils :as u]
             [globals :as globals]]
            [clojure.string :as str]
            [jansi-clj.core :refer :all]))


(declare show-item)

(defn print-primitive
  "Prints numbers, strings, booleans, and byte arrays"
  ([obj]
   (print-primitive obj 0))

  ([obj indent-level]
   (cond
     (or (number? obj) (string? obj) (boolean? obj))
     (do
       (u/print-indent indent-level)
       (println (yellow obj)))

     (u/byte-array? obj)
     (do
       (u/print-indent indent-level)
       (println (format "byte array[%d]" (count obj))
                (map #(format (yellow "%02X") %1) obj))))))

(defn print-map
  [character-map & {:keys [skip-item-count]
                    :or {skip-item-count false}}]

  (let [character (->> character-map
                       (filter dbu/without-meta-fields)
                       (sort-by #(str (first %))))

        max-key-length (reduce
                        (fn [max-length key-str]
                          (if (> (count key-str) max-length)
                            (count key-str)
                            max-length))
                        0

                        ;; Map the keys to a more readable string format
                        (->> character
                             (keys)
                             (map u/keyword->str))
                        )]

    (doseq [[key value] character]
      (println

       ;; Print the key name
       (format (format "%%%ds :" (+ max-key-length 2))
               (u/keyword->str key))

       ;; Print the value
       (cond
         (coll? value)
         (format "collection of %d items" (count value))

         (u/byte-array? value)
         (format "byte array[%d]" (count value))

         (and (string? value) (empty? value))
         "\"\""

         :else
         (yellow value))))

    (when-not skip-item-count
      (newline)
      (println (format (format "%%%dd" (+ max-key-length 2)) (count character)) "fields"))))


(defn print-sequence
  [obj path]

  (if (empty? obj)
    (do
      (u/print-indent 1)
      (println (yellow "Empty")))
    (u/doseq-indexed i [item obj]
                     ;; Print the index of the object
                     (print (format
                             (format "%%%dd: " (-> (count obj)
                                                   (Math/log10)
                                                   (Math/ceil)
                                                   (max 1)
                                                   (int)))
                             i))

                     ;; Print some representation of the object
                     (let [item-type (type item)]
                       (cond
                         (dbu/is-primitive? item)
                         (print-primitive item)

                         (sequential? item)
                         (println (format "collection of %d items" (count item)))

                         (associative? item)
                         (do
                           ;; If a display name can be fetched...
                           (when-let [display-name (dbu/get-name item (conj path i))]
                             ;; Print annotation on the same line as the index
                             (println (yellow display-name)))

                           ;; Close the index + display-name line
                           (newline)

                           (print-map item :skip-item-count true)
                           (if-not (= i (dec (count obj)))
                             (newline)))

                         :else
                         (println item))))))


(defn- print-object-name
  [s]

  (when-not (empty? s)
    (println (yellow s))
    (println)))

(defn print-object
  "Given some kind of thing in the character sheet, try to print it."
  [obj path]

  (cond
    (dbu/get-type obj)
    (do
      (print-object-name (dbu/get-name obj path))
      (print-map obj))

    (dbu/is-primitive? obj)
    (print-primitive obj 1)

    (sequential? obj)
    (print-sequence obj path)

    :else
    (print-map obj)))


(defn print-result-records
  [results]
  {:pre [(sequential? results)]}

  (doseq [kv-map results]
    (println (:recordname kv-map))
    (doseq [[key value] (->> kv-map
                             seq
                             (filter #(not (keyword? (first %1))))
                             (sort-by first))]

      (println (format "\t%s: %s" key (yellow value))))

    (newline)))


(defn print-map-difference
  [[only-in-a only-in-b _]]

  (if (empty? only-in-b)
    (println "Nothing has been changed")

    (let [max-key-length (->> (keys only-in-b)
                              (map (comp count u/keyword->str))
                              (apply max))]

      (doseq [[key value] (sort only-in-a)]
        (println

         ;; Print the key name
         (format (format "%%%ds :" (+ max-key-length 2))
                 (u/keyword->str key))

         ;; Print the value
         (cond
           (coll? value)
           (format "collection with %d items changed" (->> value
                                                         (filter some?)
                                                         (count)))

           :else
           (do
             ;; (println (format "%s => %s" (yellow (type value)) (yellow (type (only-in-b key)))))
             (format "%s => %s" (yellow value) (yellow (only-in-b key)))))))

      (newline)
      (println (format (format "%%%dd" (+ max-key-length 2)) (count only-in-b)) "fields changed"))))


(defn show-item
  "Print all related records for an item"
  [item]

  (cond

    (not (dbu/is-item? item))
    (println "Sorry, this doesn't look like an item")

    (empty? (:basename item))
    (do
      (println "This isn't a valid item (no basename)")
      (print-map item))

    :else
    (let [related-records (dbu/related-db-records item @globals/db-and-index)
          name (dbu/item-name item @globals/db-and-index)]
      (when (not (nil? name))
        (println (yellow name))
        (newline))
      (print-map item :skip-item-count true)
      (newline)
      (print-result-records related-records))))


(defn displayable-path
  "Given a path into a data structure (such as paths returned by collect-walk*), and turn it
  into a string suitable for display."
  [path]
  (->> path
       (map #(cond
               (keyword? %) (name %)
               :else %))
       (str/join "/")))
