(ns thi.ng.simplecl.test.hello
  ^{:author "Karsten Schmidt"}
  (:require
   [thi.ng.simplecl.core :as cl]
   [thi.ng.simplecl.utils :as clu]
   [thi.ng.simplecl.ops :as ops]))

(def hello-kernel
  "__kernel void HelloCL(__global const float* a,
                         __global const float* b,
                         __global float* c,
                         const uint n) {
    uint id = get_global_id(0);
    if (id < n) {
      c[id] = a[id] * b[id];
    }
}")

(defn verify
  [results num]
  (println
   "verified:"
   (= results (map #(float (* % %2)) (range num) (reverse (range num))))))

(defn hello-cl
  [& {:keys [num device] :or {num 1024}}]
  (cl/with-state
    (cl/init-state :device device :program (clu/str->stream hello-kernel))
    (println "using device:" (cl/device-name))
    (if-not (cl/build-ok?)
      (println "build log:\n----------\n" (cl/build-log))
      (let [num  (* 1024 num)
            data (range num)
            a    (cl/as-clbuffer :float data :readonly)
            b    (cl/as-clbuffer :float (reverse data) :readonly)
            c    (cl/make-buffer :float num :writeonly)]
        (-> (ops/compile-pipeline
             :steps [{:name "HelloCL"
                      :in [a b] :out c
                      :write [:in :out] :read [:out]
                      :args [[num :int]]
                      :n num}])
            (ops/execute-pipeline :verbose :true)
            (cl/buffer-seq)
            (verify num))))))

(defn -main
  [& [device]]
  ;;(hello-cl :num 1024 :device :cpu)
  ;;(hello-cl :num 1024 :device :gpu)
  (hello-cl :num 1024 :device (keyword device)))
