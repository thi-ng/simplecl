(defproject com.postspectacular/simplecl "0.1.4"
  :description "Clojure wrapper & highlevel processing pipeline ops for JOCL/OpenCL."
  :url "http://hg.postspectacular.com/simplecl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.jogamp/gluegen-rt "2.0.0" :classifier "rc10"]
                 [com.jogamp/jocl "2.0.0" :classifier "rc10"]]
  :profiles {:test {:dependencies [[com.postspectacular/structgen "0.1.0"]]
                    :jvm-opts ["-Xms512m" "-Xmx2g"]}}
)
