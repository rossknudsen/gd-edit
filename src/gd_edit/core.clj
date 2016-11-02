(ns gd-edit.core
  (:require [clojure.java.io :as io]
            )
  (:import  [java.nio ByteBuffer]
            [java.nio.file Path Paths Files FileSystems StandardOpenOption]
            [java.nio.channels FileChannel]
            [net.jpountz.lz4 LZ4Factory]
            )
  (:gen-class))


(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (println "Hello, World!"))

(defn spec-type
  [spec & rest]
  (:struct/type (meta spec)))

(defmulti read-spec spec-type)

(def primitives-specs
  ;; name => primitives spec
  {:byte   [:byte    1 (fn[^ByteBuffer bb] (.get bb))      ]
   :int16  [:int16   2 (fn[^ByteBuffer bb] (.getShort bb)) ]
   :int32  [:int     4 (fn[^ByteBuffer bb] (.getInt bb))   ]
   :float  [:float   4 (fn[^ByteBuffer bb] (.getFloat bb)) ]
   :double [:double  4 (fn[^ByteBuffer bb] (.getDouble bb))]
   })

(defn sizeof
  "Given a spec, return the byte size it represents"
  [spec]

  ;; Look through all the items in the given spec
  (reduce
   (fn [accum item]
     ;; Does a matching entry exist for the item in the primitives array?
     (let [primitives-spec (primitives-specs item)]
       (if primitives-spec
         ;; If so, add the size to the accumulator
         (+ accum (nth primitives-spec 1))

         ;; If not, we've come across a field name. Don't do anything
         accum
         )))

   ;; Start the sum at 0
   0

   ;; Ignore all odd fields, assuming they are fieldnames
   (take-nth 2 (rest spec))))

(defn ordered-map?
  [spec]
  (= (-> spec
         meta
         :struct/return-type)
     :map))

(defn strip-orderedmap-fields
  "Returns a spec with the keyname fields stripped if it looks like an orderedmap"
  [spec]

  ;; If it looks like an ordered map
  (if (ordered-map? spec)

    ;; strip all the odd number fields
    (take-nth 2 (rest spec))

    ;; Doesn't look like an ordered map...
    ;; Return the spec untouched
    spec))

(defn compile-meta
  [{:keys [struct/length-prefix] :as spec-meta}]

  ;; The only meta field that needs to be updated is the "length-prefix" field
  ;; If it's not available, we don't need to do anything to compile this
  (if (nil? length-prefix)
    (assoc spec-meta :compiled true)

    ;; There is no reason for a length prefix to be a composite type
    ;; So we won't handle that case
    (let [primitives-spec (primitives-specs length-prefix)]
      (if primitives-spec
        ;; Update the length-prefix field to the function to be called
        (assoc spec-meta
               :struct/length-prefix (nth primitives-spec 2)
               :compiled true)

        ;; If we're not looking at a primitive type, we're looking at a composite type...
        (throw (Throwable. (str "Cannot handle spec:" length-prefix)))))))

(declare compile-spec-)

(defn compile-spec
  [spec]

  (let [compiled-spec (compile-spec- spec)
        compiled-meta (compile-meta (meta spec))]
    (with-meta compiled-spec compiled-meta)))


(defn compile-spec-
  [spec]

  (let [type (spec-type spec)]
    (cond
      ;; String specs are dummy items
      ;; Just return the item itself
      (= type :string)
      spec

      ;; Sequence of specs?
      ;; Compile each one and return it
      ;; This should handle :variable-count also
      (sequential? spec)
      (reduce
       (fn [accum item]
         (conj accum (compile-spec item))
         )
       (empty spec) 
       spec)

      ;; Otherwise, this should just be a plain primitive
      :else
      (nth (primitives-specs spec) 2)
      )))


(defn read-struct
  "Spec can also be a single primitive spec. In that case, we'll just read
  a single item from the bytebuffer.

  Spec can be a simple sequence of type specs. In that case, a list values
  that corresponds to each item in the given spec.

  Spec can be a collect with some special meta tags. In those cases, special
  handling will be invoked via the 'read-spec' multimethod."
  [spec ^ByteBuffer bb]

  ;; Handle single primitive case
  ;; Handle sequence specs case
  ;; Handle complicated case

  (let [;; Get a list of specs to read without the fieldnames
        stripped-spec (strip-orderedmap-fields spec)

        ;; Read in data using the list of specs
        values (read-spec stripped-spec bb)]

    ;; We've read all the values
    ;; If the user didn't ask for a map back, then we're done
    (if (not (ordered-map? spec))
      values

      ;; The user did ask for a map back
      ;; Construct a map using the fieldnames in the spec as the key and the read value as the value
      (zipmap (take-nth 2 spec) values))
    ))


(defmethod read-spec :default
  [spec bb]

  (cond

    ;; If the spec has been compiled, we will get a function in the place of a
    ;; keyword indicating a primitive.
    ;; Call it with the byte buffer to read it
    (clojure.test/function? spec)
    (spec bb)

    ;; If we're looking at some sequence, assume this is a sequence of specs and
    ;; read through it all
    (seq? spec)
    (reduce
     (fn [accum spec-item]
       (conj accum (read-spec spec-item bb)))
     []
     spec)

    ;; Otherwise, we should be dealing with some kind of primitive...
    ;; Look up the primitive keyword in the primitives map
    :else
    (let [primitives-spec (primitives-specs spec)]

      ;; If the primitive is found...
      (if primitives-spec

        ;; Execute the read function now
        ((nth primitives-spec 2) bb)

        ;; Otherwise, give up
        (throw (Throwable. (str "Cannot handle spec:" spec)))))))


(defn ordered-map
  "Tags the given spec so read-structure will return a map instead of a vector.
  Returns the collection it was given."
  [& fields]
  (with-meta fields {:struct/return-type :map}))

(defn variable-count
  "Tags the given spec to say it will repeat a number of times.

  Exmaple:
  (variable-count :uint32 :count-prefix :uint16)

  This says we will read a :uint16 first to determine how many :uint32 to read
  from the stream."
  [spec & {:keys [length-prefix]
           :or {length-prefix :int32}}]

  ;; We're about to attach some meta info to the spec
  ;; First, wrap the spec in another collection...
  ;; We'll attach the information to information to that collection instead of the
  ;; original spec
  (let [spec-seq (list spec)]

    ;; Attach the meta info to the spec 
    ;; Any sequence can be have meta info attached
    (with-meta spec-seq
      {:struct/type :variable-count
       :struct/length-prefix length-prefix})))


(defmethod read-spec :variable-count
  [spec bb]

  (let [
        ;; Destructure fields in the attached meta info
        {length-prefix :struct/length-prefix} (meta spec)

        ;; Read out the count of the spec to read
        length (read-spec length-prefix bb)

        ;; Unwrap the spec so we can read it
        unwrapped-spec (first spec)
        ]

    ;; Read the spec "length" number of times and accumulate the result into a vector
    ;; We're abusing reduce here by producing a lazy sequence to control the number of
    ;; iterations we run.
    (reduce
     (fn [accum _]
       ;; Read another one out
       (conj accum (read-spec unwrapped-spec bb)))
     []
     (repeat length 1))))


(defn string
  "Returns a spec used to read a string of a specific type of encoding.
  Can supply :length-prefix to indicate how to read the length of the string.
  The default :length-prefix is a :int32."
  [enc & {:keys [length-prefix]
          :or {length-prefix :int32}}]

  ;; Tag a dummy sequence with the info passed into the function
  (with-meta '(:string)
    {:struct/type :string
     :struct/length-prefix length-prefix
     :struct/string-encoding enc}))


(defmethod read-spec :string
  [spec ^ByteBuffer bb]

  (let [
        valid-encodings {:ascii "US-ASCII"
                         :utf-8 "UTF-8"}

        ;; Destructure fields in the attached meta info
        {length-prefix :struct/length-prefix requested-encoding :struct/string-encoding} (meta spec)

        ;; Read out the length of the string
        length (read-spec length-prefix bb)

        ;; Create a temp buffer to hold the bytes before turning it into a java string
        buffer (byte-array length)
        ]

    ;; Read the string bytes into the buffer
    (.get bb buffer 0 length)

    (String. buffer (valid-encodings requested-encoding))))


(defn seq->bytes
  [seq]

  ;; Construct a byte array from the contents of...
  (byte-array

   ;; sequence of bytes reduced from individual items in the given sequence
   (reduce
    (fn [accum item]
      (conj accum (byte item))) [] seq)
   ))


(defn bytes->bytebuffer
  [bytes]

  (ByteBuffer/wrap bytes))


(defn seq->bytebuffer
  [seq]
  (-> seq
      seq->bytes
      bytes->bytebuffer))

(def arz-header
  (ordered-map 
   :unknown              :int16
   :version              :int16
   :record-table-start   :int32
   :record-table-size    :int32
   :record-table-entries :int32
   :string-table-start   :int32
   :string-table-size    :int32))


(def arz-string-table
  (variable-count
   (string :ascii)
   :length-prefix :int32))


(defn mmap
  [filepath]

  (with-open [db-file (java.io.RandomAccessFile. filepath "r")]
    (let [file-channel (.getChannel db-file)
          file-size (.size file-channel)]

          (.map file-channel java.nio.channels.FileChannel$MapMode/READ_ONLY 0 file-size))))


(defn- load-db-header
  [^ByteBuffer bb]
  
  ;; Jump to the start of the file header and start decoding
  (.position bb 0)
  (read-struct arz-header bb))


;; After some number of attempts to get gloss to load the string table, I gave up and just
;; hand-coded the string table reading.
;; (defn- load-db-string-table2
;;   [^ByteBuffer bb header]

;;   ;; Jump to the start of the string table and start decoding
;;   (.position bb (:string-table-start header))
;;   (read-struct (compile-spec arz-string-table) bb))


(defn- load-db-string-table
  [^ByteBuffer bb header]

  (.position bb (:string-table-start header))

  ;; How many strings do we have?
  (let [str-count (.getInt bb)]

    ;; Read out that many string
    (loop [i 0
           limit str-count
           string-table []]

      ;; Did we retrieve the targetted number of strings?
      ;; If so, just return the collected string table
      (if (>= i limit)
        string-table

        ;; Retrieve one more string
        (do
           (let [str-len (.getInt bb)
                 str-buffer (byte-array str-len)]

             ;; Read the specified number of bytes and convert it to a string
             (.get bb str-buffer 0 str-len)

             ;; Either continue on to the next loop or
             ;; terminate by returning the string table
             (recur (inc i) limit (conj string-table (String. str-buffer)))))))))

(def arz-record-header
  (ordered-map
   :filename          :int32
   :type              (string :ascii)
   :offset            :int32
   :compressed-size   :int32
   :decompressed-size :int32
   :unknown           :int32
   :unknown2          :int32
   ))

(defn- load-db-records-header-table
  [^ByteBuffer bb header string-table]

  ;; Move the buffer to the beginning of the header
  (.position bb (:record-table-start header))

  ;; Read all the headers
  (let [record-headers (reduce
                        (fn [accum _]
                          (conj accum
                                (read-struct arz-record-header bb)))
                        []
                        (range (:record-table-entries header)))]

    ;; Look up all the record file names in the string table
    (map (fn [item]
           (->> (item :filename)
                (nth string-table)
                (assoc item :filename)))
         record-headers)))

(defn hexify [s]
  (apply str
    (map #(format "%02x " (byte %)) s)))

(defn- load-db-record
  [^ByteBuffer bb header string-table record-header]

  (let [{:keys [compressed-size decompressed-size]} record-header
        compressed-data (byte-array (:compressed-size record-header))
        decompressed-data (byte-array (:decompressed-size record-header))
        decompressor (.fastDecompressor (LZ4Factory/fastestInstance))

        ;; Move to where the record is
        ;; Note: we're adding 24 to account for the file header
        _ (.position bb (+ (:offset record-header) 24))

        ;; Grab the compressed data
        _ (.get bb compressed-data 0 compressed-size)

        ;; Decompress the data
        _ (.decompress decompressor compressed-data 0 decompressed-data 0 decompressed-size)

        record-buffer (ByteBuffer/wrap (bytes decompressed-data))
        _ (.order record-buffer java.nio.ByteOrder/LITTLE_ENDIAN)
        ]

    ;; Try to read the entire record...
    (reduce
     (fn [record i]
       ;; Did we finish reading the entire record?
       (if (<= (.remaining record-buffer) 0)
         ;; If so, return the accumulated record
         (reduced record)

         ;; Otherwise, read one more entry...
         (let [type (.getShort record-buffer)

               data-count (.getShort record-buffer)
               fieldname (nth string-table (.getInt record-buffer))

               ;; Read out the indicate number of data items into a list
               val-vec (reduce
                        (fn [accum j]
                          (cond (= type 1)
                                (conj accum (.getFloat record-buffer))

                                (= type 2)
                                (conj accum (nth string-table (.getInt record-buffer)))

                                :else
                                (conj accum (.getInt record-buffer))
                                ))
                        []
                        (range data-count))
               ]

           (cond
             ;; Do we have more than one value?
             ;; Add it to the record
             (> 1 (count val-vec))
             (assoc record fieldname val-vec)

             ;; Otherwise, we only have a single value in the vector
             ;; If the value is not zero or empty string, add it to the record
             (and (not= (first val-vec) (int 0))
                  (not= (first val-vec) (float 0))
                  (not= (first val-vec) ""))
             (assoc record fieldname (first val-vec))

             ;; Otherwise, don't append any new fields to the record
             ;; Just return it as is
             :else
             record
             ))))
     {}          ;; Start reduce with an empty record
     (range)     ;; Have reduce loop forever. We'll check the exit condition in the lambda
     )))

(defn- load-db-records
  [^ByteBuffer bb header string-table]

  ;; Load up all the record headers
  (->> (load-db-records-header-table bb header string-table)

       ;; Try to read each record
       (map (fn [record-header]
              (load-db-record bb header string-table record-header)))

       (doall)
       ))

(defn load-game-db
  [filepath]

  ;; Open the database file
  (let [bb (mmap filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)

        ;; Read and parse the header
        header (load-db-header bb)
        string-table (load-db-string-table bb header)] 

    ;; Read in all the file records
    (load-db-records bb header string-table)))


#_(time  (load-game-db "/Users/Odie/Dropbox/Public/GrimDawn/database/database.arz"))


#_(def f (mmap "/Users/Odie/Dropbox/Public/GrimDawn/database/database.arz"))
#_(.order f java.nio.ByteOrder/LITTLE_ENDIAN)
#_(def h (load-db-header f))
#_(time (def st (load-db-string-table f h)))
#_(time (def dt (load-db-records-header-table f h st)))
#_(time (def rt (load-db-records f h st)))
