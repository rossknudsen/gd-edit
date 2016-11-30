(def project 'gd-edit)
(def version "0.1.0-SNAPSHOT")

(set-env! :resource-paths #{"resources" "src"}
          :source-paths   #{"test"}
          :dependencies   '[[org.clojure/clojure "RELEASE"]
                            [net.jpountz.lz4/lz4 "1.3.0"]
                            [org.jline/jline "3.0.1"]
                            [org.fusesource.jansi/jansi "1.14"]
                            [jansi-clj "0.1.0"]
                            [adzerk/boot-jar2bin "1.1.0" :scope "test"]
                            [adzerk/boot-test "RELEASE" :scope "test"]
                            [mbuczko/boot-build-info "0.1.1" :scope "test"]
                            [cheshire "5.6.3"]])

(require '[adzerk.boot-jar2bin :refer :all]
         '[mbuczko.boot-build-info :refer :all])

(task-options!
 aot {:namespace   #{'gd-edit.core}}
 pom {:project     project
      :version     version
      :description "FIXME: write description"
      :url         "http://example/FIXME"
      :scm         {:url "https://github.com/yourname/gd-edit"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 jar {:main        'gd-edit.core
      :file        (str "gd-edit-" version "-standalone.jar")}
 bin {:jvm-opt #{"-Xms128m" "-Xmx512m"}}
 exe {:jvm-opt #{"-Xms128m" "-Xmx512m"}
      :name      project
      :main      'gd_edit.core
      :version   "0.1.0"
      :desc      "GrimDawn save game editor"
      :copyright "2016"}
 build-info {:build {:app-name (str project)}}
 )

(deftask cider "CIDER profile"
  []
  (require 'boot.repl)
  (swap! @(resolve 'boot.repl/*default-dependencies*)
         concat '[[org.clojure/tools.nrepl "0.2.12"]
                  [cider/cider-nrepl "0.14.0"]
                  [refactor-nrepl "2.2.0"]])
  (swap! @(resolve 'boot.repl/*default-middleware*)
         concat '[cider.nrepl/cider-middleware
                  refactor-nrepl.middleware/wrap-refactor])
  identity)

(deftask build
  "Build the project locally as a JAR."
  [d dir PATH #{str} "the set of directories to write to (target)."]
  (let [dir (if (seq dir) dir #{"target"})]
    (comp (build-info) (aot) (pom) (uber) (jar) (target :dir dir) (exe :output-dir "target") (bin :output-dir "target"))))

(deftask run
  "Run the project."
  [a args ARG [str] "the arguments for the application."]
  (require '[gd-edit.core :as app])
  (apply (resolve 'app/-main) args))

(deftask dev
  []
  (comp (cider) (launch-nrepl) (run)))

(require '[adzerk.boot-test :refer [test]])
