(ns thi.ng.simplecl.test.debug
  (:require
   [thi.ng.simplecl.core :as cl]
   [thi.ng.simplecl.utils :as clu]
   [thi.ng.simplecl.ops :as ops]
   [thi.ng.structgen.core :as sg]
   [thi.ng.structgen.parser :as sp]))

(def debug-kernel
  "#define OFFSETOF(type, field) ((unsigned long) &(((type*) 0)->field))

typedef struct {
    float4   a;    // 0
    int      b[8]; // 16
    char     c[8]; // 48
    float3   d;    // 64
} Foo;

kernel void Debug(__global Foo* in,
                  __global Foo* out,
                  const unsigned int n,
                  const float deltaSq) {
	unsigned int id = get_global_id(0);
	if (id < n) {
		global Foo *p = &out[id];
		p->a = (float4)(OFFSETOF(Foo, b),
		                OFFSETOF(Foo, c),
		                OFFSETOF(Foo, d),
		                (sizeof *p));
	}
}")

(def state (cl/init-state :device :cpu :program (clu/str->stream debug-kernel)))

(sg/reset-registry!)
(sg/register! (sp/parse-specs (slurp (clu/str->stream debug-kernel))))

(def bar (sg/make-struct :Bar [:foo :Foo 4]))

(def pbuf (sg/encode bar {}))
(def qbuf (sg/encode bar {}))

(cl/with-state state

  (println "build log:" (cl/build-log))

  (let [pclbuf (cl/as-clbuffer pbuf)
        qclbuf (cl/as-clbuffer qbuf :writeonly)
        n      (-> bar sg/struct-spec :foo sg/length)
        _      (println :n n :sizeof (sg/sizeof bar))
        pipe   (ops/compile-pipeline
                :steps [{:name  "Debug"
                         :in    pclbuf
                         :out   qclbuf
                         :n     n
                         :write [:in :out]
                         :read  [:out]
                         :args  [[n :int] [0.5 :float]]}])]
    
    (println (sg/decode bar (ops/execute-pipeline pipe :verbose true)))))
