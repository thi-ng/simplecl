(defproject thi.ng/simplecl "0.2.0"
  :description "Clojure wrapper & highlevel processing pipeline ops for JOCL/OpenCL."
  :url          "http://thi.ng/structgen"
  :license      {:name "Apache Software License 2.0"
                 :url "http://www.apache.org/licenses/LICENSE-2.0"
                 :distribution :repo}
  :scm          {:name "git"
                 :url "git@github.com:thi-ng/simplecl.git"}

  :dependencies [[org.clojure/clojure "1.7.0-beta3"]
                 [org.jogamp.gluegen/gluegen-rt-main "2.3.1"]
                 [org.jogamp.gluegen/gluegen-rt "2.3.1" :native-prefix ""]
                 [org.jogamp.jocl/jocl-main "2.3.1"]
                 [org.jogamp.jocl/jocl "2.3.1" :native-prefix ""]]

  :profiles     {:dev {:dependencies [[thi.ng/structgen "0.2.1"]
                                      [criterium "0.4.3"]]
                       :resource-paths ["dev-resources"]
                       :jvm-opts ^:replace ["-Xms512m" "-Xmx2g"]
                       :global-vars {*warn-on-reflection* true}
                       :aliases {"cleantest" ["do" "clean," "test"]}}}

  :pom-addition [:developers [:developer
                              [:name "Karsten Schmidt"]
                              [:url "http://postspectacular.com"]
                              [:timezone "0"]]])
