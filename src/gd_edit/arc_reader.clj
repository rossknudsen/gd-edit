(ns gd-edit.arc-reader
  (:require [gd-edit.structure :as s]
            [gd-edit.utils :as u]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [taoensso.timbre :as t]
            [clojure.string :as str])
  (:import  [java.nio ByteBuffer]
            [java.nio.file Path Paths Files FileSystems StandardOpenOption]
            [java.nio.channels FileChannel]
            [net.jpountz.lz4 LZ4Factory])
  (:gen-class))

(def arc-header
  (s/ordered-map
   :magic :int32
   :version :int32
   :file-entries :int32
   :data-records :int32
   :record-table-size :int32
   :string-table-size :int32
   :record-table-offset :int32))

(def arc-record-header
  (s/ordered-map
   :entry-type :int32
   :offset :int32
   :compressed-size :int32
   :decompressed-size :int32
   :decompressed-hash :int32  ;; Adler32 hash of the decompressed file bytes
   :filetime :int64
   :file-parts :int32
   :first-part-index :int32
   :string-entry-length :int32
   :string-entry-offset :int32))

(def arc-file-part-header
  (s/ordered-map
   :offset :int32
   :compressed-size :int32
   :decompressed-size :int32))

(def tex-header
  (s/ordered-map
   :magic          (s/string :ascii
                             :length 3)
   :version      :byte
   :fps          :int32
   :frame-length :int32))


(def dds-preamble
  (s/ordered-map
   :magic          (s/string :ascii
                             :length 3)
   :variant      :byte))

(def dds-header
  (s/ordered-map
   :size         :int32
   :flags        :int32
   :height       :int32
   :width        :int32
   :linear-size  :int32
   :depth        :int32
   :mipmap-count :int32))


(defn- load-header
  [^ByteBuffer bb]

  (.position bb 0)

  (let [header (s/read-struct arc-header bb)]
    (when (or (not= (:magic header) 0x435241) (not= (:version header) 0x3))
      (throw  (Throwable. (str "I don't understand this ARC format!"))))

    header))


(defn read-bytes
 "Retrieve the next 'count' bytes from the byte buffer and return it in a new byte array"

 [^ByteBuffer bb count]
 (let [buffer (byte-array count)]
   (.get bb buffer 0 count)
   buffer))

(defn- load-record-filename
  [^ByteBuffer bb header record-header]

  (.position bb (+ (:record-table-offset header)
                   (:record-table-size header)
                   (:string-entry-offset record-header)))

  (String. (read-bytes bb (:string-entry-length record-header)) "US-ASCII"))


(defn write-to-stream
  "Write the entire bytes array into the stream"
  [^java.io.OutputStream stream bytes]
  (.write stream bytes 0 (count bytes)))


(defn- load-record-file-part
  [^ByteBuffer bb header record-header i]

  ;; Magic number "12" is the size of the arc-file-part on disk
  (.position bb (-> (:first-part-index record-header)
                    (+ i)
                    (* 12)
                    (+ (:record-table-offset header))))

  ;; Read the file part header
  (let [part-header (s/read-struct arc-file-part-header bb)

        ;; compressed-size (:compressed-size part-header)
        ;; decompressed-size (:decompressed-size part-header)
        {:keys [compressed-size decompressed-size]} part-header

        ;; Goto where the file part is
        _ (.position bb (:offset part-header))

        ;; Read the contents
        compressed-bytes (read-bytes bb compressed-size);
        ]

    ;; If this file part doesn't need decompression, we're done...
    (if (= compressed-size decompressed-size)
      compressed-bytes

      ;; Decompress the contents...
      (let [decompressed-bytes (byte-array decompressed-size)]

        ;; Grab a decompressor
        (-> (.fastDecompressor (LZ4Factory/fastestInstance))

            ;; Decompress the data
            (.decompress compressed-bytes 0  decompressed-bytes 0 decompressed-size))

        decompressed-bytes))))


(defn- load-record
  [^ByteBuffer bb header record-header]

  (.position bb (:offset record-header))

  (let [{:keys [compressed-size decompressed-size]} record-header]

    ;; If this is a single part file and no decompression is needed, read and return the contents
    (if (and (= (:entry-type record-header) 1) (= compressed-size decompressed-size))
      (read-bytes bb compressed-size)

      ;; Otherwise, we need to read out the individual parts and decompress them
      (let [output-stream (java.io.ByteArrayOutputStream.)]
        (loop [i 0
               limit (:file-parts record-header)]

          ;; Terminate or read one more file part
          (if (>= i limit)
            nil

            (do
              (write-to-stream output-stream (load-record-file-part bb header record-header i))
              (recur (inc i) limit))))

        ;; We've read and decompressed all the file parts now
        ;; Return the contents as a byte array
        (.toByteArray output-stream)
        ))))


(defn- load-record-headers
  [^ByteBuffer bb header]

  (.position bb (+ (:record-table-offset header)
                   (:record-table-size header)
                   (:string-table-size header)))

  (loop [i 0
         limit (:file-entries header)
         accum []]

    (if (>= i limit)
      accum

      (recur (inc i)
             limit
             (conj accum (s/read-struct arc-record-header bb))))))


(defn- localization-file->hashmap
  [bytes]
  (let [lines (line-seq (io/reader bytes))]

    (reduce
     (fn[accum line]
       ;; In the localization files, a = sign is used to separate the key and the value
       ;; Can we find a "=" sign?
       (let [split-index (string/index-of line "=")]

         ;; If we can't find a = sign, stop processing this line
         (if (= nil split-index)
           accum

           ;; We did find a = sign, extract the key and value then add them to the map
           (assoc accum
                  (string/trim (subs line 0 split-index))
                  (string/trim (subs line (inc split-index)))))
       ))
     {}
     lines)))


(defn load-localization-table
  [filepath]

  (t/debug "Entering load-localization-table")
  (u/log-exp filepath)

  (let [bb (u/mmap filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
        header (load-header bb)
        record-headers (load-record-headers bb header)]

    (->> record-headers
         (map (fn [record-header]
                (localization-file->hashmap
                 (load-record bb header record-header))))
         (reduce into {}))))

(defn load-arc-file
  [filepath]

  (let [bb (u/mmap filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
        header (load-header bb)
        record-headers (load-record-headers bb header)]

    (->> record-headers
         (reduce (fn [accum record-header]
                   (let [record {:recordname (load-record-filename bb header record-header)
                                 :contents (load-record bb header record-header)}]
                     (if (empty? (:recordname record))
                       accum
                       (conj accum record))))
                 []))))


(defn load-arc-tex-file
  [filepath load-fn]

  (let [bb (u/mmap filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
        header (load-header bb)

        record-headers (->>  (load-record-headers bb header)
                             (map (fn [record-header]
                                    {:recordname (load-record-filename bb header record-header)
                                     :record-header record-header}))
                             (filter #(str/ends-with? (:recordname %) ".tex"))
                             )
        ]

    (->> record-headers
         (map (fn [record-header]
                   (let [record-bb (as-> (load-record bb header (:record-header record-header)) $
                                     (ByteBuffer/wrap $)
                                     (.order $ java.nio.ByteOrder/LITTLE_ENDIAN))]

                     (assoc (load-fn record-bb)
                            :recordname (:recordname record-header))))))))


(defn read-tex-header
  [bb]

  (s/read-struct tex-header bb)
  (s/read-struct dds-preamble bb)
  (s/read-struct dds-header bb))

(defn texture-dimensions
  [dds-header]

  (select-keys dds-header [:width :height]))

(defn make-load-tex-fn
  [tex-arc-filepath load-fn]

  (let [bb (u/mmap tex-arc-filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
        header (load-header bb)
        record-headers (->>  (load-record-headers bb header)
                             (map (fn [record-header]
                                    {:recordname (load-record-filename bb header record-header)
                                     :record-header record-header})))]

    (fn [tex-name]

      (if-let [target (->> record-headers
                           (filter #(= (:recordname %) tex-name))
                           (first))]

        (let [target-bb (as-> (load-record bb header (:record-header target)) $
                          (ByteBuffer/wrap $)
                          (.order $ java.nio.ByteOrder/LITTLE_ENDIAN))]

          (load-fn target-bb))))))

(defn unpack-arc-file
  [filepath outpath]

  (let [bb (u/mmap filepath)
        _ (.order bb java.nio.ByteOrder/LITTLE_ENDIAN)
        header (load-header bb)
        record-headers (load-record-headers bb header)]

    ;; For every record in the file
    (doseq [record-header record-headers]

      ;; If we can retrieve a valid recordname/filename
      (let [recordname (load-record-filename bb header record-header)]
        (when-not (empty? recordname)

          ;; Grab the file contents...
          (let [contents (String. (load-record bb header record-header))
                record-path (io/file outpath recordname)]

            ;; Write the contents to disk
            (.mkdirs (.getParentFile record-path))
            (spit record-path contents)))))))

;; ARC format
;;
;; File Header
;; Record table
;;  Location = file-header :record-table-offset field
;;  Total size = file-header :record-table-size
;; String table

#_(time (load-localization-table "/Users/Odie/Dropbox/Public/GrimDawn/resources/text_en.arc"))
#_(def l (load-localization-table "/Users/Odie/Dropbox/Public/GrimDawn2/resources/text_en.arc"))
#_(def f (u/mmap "/Users/Odie/Dropbox/Public/GrimDawn/resources/text_en.arc"))
#_(.order f java.nio.ByteOrder/LITTLE_ENDIAN)
#_(def h (load-header f))
#_(time (def dt (load-record-headers f h)))
#_(time (def r (load-record f h (nth dt 0))))

#_(def l (load-arc-file "/Users/Odie/Dropbox/Public/GrimDawn2/database/templates.arc"))
#_(unpack-arc-file "/Users/Odie/Dropbox/Public/GrimDawn2/database/templates.arc" (io/file (u/working-directory) "templates"))

#_(def t (make-load-tex-fn "/Users/Odie/Dropbox/Public/GrimDawn2/resources/Items.arc" (comp texture-dimensions read-tex-header)))
#_(t "gearweapons/hammers1h/bitmaps/f002_blunt.tex")

(comment

  (require gd-edit.qst-reader)
  (unpack-arc-file "/Volumes/Untitled/Program Files (x86)/Steam/steamapps/common/Grim Dawn/resources/scripts.arc" "/tmp/scripts/")

  )
