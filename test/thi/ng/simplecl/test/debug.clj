(ns thi.ng.simplecl.test.debug
  (:require
   [thi.ng.simplecl.core :as cl]
   [thi.ng.simplecl.structs :as cs]
   [thi.ng.simplecl.utils :as cu]
   [thi.ng.structgen.core :as sg]
   [thi.ng.structgen.parser :as sp]))

(def ^:dynamic *verbose* false)

(def progname "kernels/debug.cl")

(def s (cl/init-state :device :cpu :program (cu/resource-stream progname)))

(reset-registry!)
(register! (sp/parse-specs (slurp (cu/resource-stream progname))))

;;(def foo (sg/lookup :Foo))
(def foo (sg/make-struct :Bar [:foo :Foo 4]))

(def pbuf (sg/encode foo {}))
(def qbuf (sg/encode foo {}))

(cl/with-state s

  (println "build log:" (cl/build-log))

  (let [pclbuf (cl/as-clbuffer pbuf)
        qclbuf (cl/as-clbuffer qbuf :writeonly)
        pipe   (cl/compile-pipeline
                :steps [{:name  "Debug"
                         :in    pclbuf
                         :out   qclbuf
                         :n     (sg/length (:foo (sg/struct-spec foo)))
                         :write [:in :out]
                         :read  [:out]
                         :args  [[(sg/length (:foo (sg/struct-spec foo))) :int] [0.5 :float]]}])]
    
    (sg/decode foo (cl/execute-pipeline pipe :verbose true))))
