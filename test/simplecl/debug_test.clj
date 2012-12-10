(ns simplecl.debug-test
  (:use
    [simplecl core structs [utils :only [resource-stream]]]
    [structgen core parser]))

(def ^:dynamic *verbose* false)

(def progname "kernels/debug.cl")

(def s (init-state :device :cpu :program (resource-stream progname)))

(reset-registry!)
(register! (parse-specs (slurp (resource-stream progname))))

(def foo (lookup :Foo))
(def foo* (make-struct :Bar [:foo :Foo 4]))

(def pbuf (encode foo* {}))
(def qbuf (encode foo* {}))

(with-state s

  (println "build log:" (build-log))
  
  (def pclbuf (as-clbuffer pbuf))

  (def qclbuf (as-clbuffer qbuf :writeonly))

  (def pipe
    (compile-pipeline
      :steps [{:name "Debug"
               :in pclbuf
               :out qclbuf
               :n (length (:foo (struct-spec foo*)))
               :write [:in :out]
               :read [:out]
               :args [[(length (:foo (struct-spec foo*))) :int] [0.5 :float]]}]))
  
  (decode foo* (execute-pipeline pipe :verbose true)))
