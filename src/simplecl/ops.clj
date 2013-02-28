(ns simplecl.ops
  "High level OpenCL processing operations & helpers."
  ^{:author "Karsten Schmidt"}
  (:import
    [java.nio Buffer])
  (:require
    [simplecl.core :as cl]
    [simplecl.utils :as clu]
    [clojure.pprint :refer [pprint]]))

(defn init-programs
  "Takes a number of named CL program specs, loads each using `resource-stream` and
  builds it in the current CL context. Returns a map of all successfully built programs.
  Prints out the build log for all errornous programs."
  [& {:as progs}]
  (reduce
    (fn [acc [k v]]
      (let [p (cl/make-program (clu/resource-stream v))]
        (if (cl/build-ok? p)
          (assoc acc k p)
          (do (prn k (cl/build-log p)) acc))))
    {} progs))

(defn init-buffers
  "Takes a buffer size `n`, a `group-size` and a number of named buffer specs.
  Instantiates a CL buffer for each spec with each having the following keys:

      :usage - buffer usage (default :readwrite)
      :type - buffer type (defaults to :float unless :wrap is present)
       :size - absolute size or
      :factor - relative size as in (* factor (int (/ n group-size)) group-size)
      :fill - optional fn to fill buffer elements with (see fill-buffer fn)
      :data - optional data seq to fill buffer with
      :wrap - optional, existing NIO buffer or Clojure seq to fully wrap using as-clbuffer fn

  Returns a map with same keys and CLBuffer instances as values."
  [n group-size & {:as specs}]
  (let [n (* (int (/ n group-size)) group-size)]
    (reduce
        (fn [acc [k {:keys [usage size factor type fill data wrap] :or {usage :readwrite}}]]
          (if wrap
            (if type
              (assoc acc k (cl/as-clbuffer type wrap usage))
              (assoc acc k (cl/as-clbuffer wrap usage)))
            (let [buf (cl/make-buffer (or type :float) (if size size (* factor n)) usage)]
              (assoc acc k
                (cond
                  fill (cl/fill-buffer buf fill)
                  data (do (cl/into-buffer buf data) (cl/rewind buf))
                  :default buf)))))
        {} specs)))

(defn init-kernel
  "Primarily used by `execute-pipeline`, creates and configures a kernel
  with the given input & output buffers and other kernel arguments.
  Produces an output buffer for its result if no output is pre-existing.
  Returns a map of:

      :kernel - CLKernel instance
      :in / :out - vectors of input/output buffers
      :local / :global - computed workgroup sizes

  Arguments and their default values:

      :name - kernel name
      :program - CLProgram instance (default *program*)
      :in - vector of existing input CLBuffers or buffer specs
      :out - vector of existing output CLBuffers or buffer specs
      :args - vector of kernel args (see configure-kernel)
      :n - number of work items
      :max-work - max local workgroup size (default *max-worksize*)

  Buffer specs are maps with these keys:

      :type - type of output buffer (default :float)
      :size - number of elements in output buffer (default size of first :in)
      :usage - memory usage of output buffer (default :readwrite)
      :fill - optional function to fill the buffer with using fill-buffer
              (only used for input buffers)"
  [& opts]
  (let [{:keys [name in n max-work args out program]}
        (if (map? (first opts)) (first opts) (apply hash-map opts))
        k (if program (cl/make-kernel program name) (cl/make-kernel name))
        ls (min (cl/max-workgroup-size k) (or max-work cl/*max-worksize*))
        gs (clu/ceil-multiple-of ls n)
        in (vec (map (fn [b] (if (map? b)
                               (let [{:keys [type size usage fill]
                                      :or {usage [:readwrite]}} b
                                     buf (apply cl/make-buffer type size
                                           (if (sequential? usage) usage [usage]))]
                                 (if fill (cl/fill-buffer buf fill) buf))
                               b))
                     (if (vector? in) in [in])))
        out (when out
              (vec (map (fn [b]
                          (if (map? b)
                            (let [usage (or (:usage b) [:readwrite])]
                              (apply cl/make-buffer
                                (or (:type b) :float)
                                (or (:size b) (.capacity (cl/nio-buffer (first in))))
                                (if (sequential? usage) usage [usage])))
                            b))
                        (if (vector? out) out [out]))))]
    (apply cl/configure-kernel k (concat in out) args)
    {:kernel k :in in :out out :local ls :global gs}))

(defn compile-pipeline
  "Takes a sequence of declarative CL processing/kernel or buffer steps, weaves and
  compiles them for later execution on a command queue using `enqueue` or
  `execute-pipeline` fns. Returns a map of queued items, set of all buffers
  and the final output buffer.
  Each step assumes either a buffer read/write op or a kernel definition with a
  single input and optional output buffer. For kernels each step also defines
  arguments and an output buffer. The last output buffer of the most recent kernel
  step acts as the default input, but can be overwritten. The first step requires
  an explicit `:in` definition. By default each step will also associate itself with
  the current `*program*`, but can refer to another program via an id and a given
  map of program ids and their CLProgram instances.

  Kernel steps are defined as maps with the same keys as required for `init-kernel`,
  however with some keys injected/manipulated automatically. Steps can also
  refer to previous steps in order to re-use outputs of a step which is not its
  direct predecessor.

      :id - id for this processing step (defaults to value of step's kernel :name)
      :in - vector of input buffer items, each one of:
            1) id of a previous step to re-use its last output buffer as input
            2) vector of [id type index] to refer to specific buffer of a previous
               step, where type is either :in/:out
            3) CLBuffer instance or buffer definition map (required for first step)
      :program - (optional) id of a program defined in the :programs map

  Each step can also optionally trigger the async writing or blocking reading
  of its associated buffers. This is done via the `:read` and `:write` vectors
  with `:in` or `:out` as their values. E.g. `:write [:in :out]` triggers the
  async writing of that step's input and output buffers *prior* to queuing the
  kernel. `:read [:out]` *must* be present for the last processing step and
  triggers the reading of its output (reads are always synchronous and are
  queued *after* kernel execution).

  To queue the reading or writing of additional buffers, define a step without
  a `:name` key and supply one or more CLBuffer instances for `:read` or `:write`:

      {:write [a b] :read c}

  Buffers queued in this way *cannot* be referenced later and are merely injected
  into the compiled pipeline."
  [& {:keys [steps programs] :or {programs {}}}]
  (let [q-append (fn [q kd coll mode & args]
                   (if coll
                     (reduce
                       (fn [q id]
                         (reduce (fn [q buf] (conj q (vec (concat [buf mode] args)))) q (id kd)))
                       q coll)
                     q))
        q-append-buf (fn [q coll mode & args]
                           (reduce (fn [q buf] (conj q (vec (concat [buf mode] args)))) q coll))
        kdefs (reduce
                (fn [{:keys [q k ki]} {:keys [id name in read write program] :as step}]
                  (if name
                    (let [id (or id name)
                          {:keys [kernel local global in out] :as kd}
                          (init-kernel
                            (assoc step
                               :in (vec (map (fn [b]
                                           (cond
                                             (keyword? b) (last (get-in ki [in :out]))
                                             (vector? b) (get-in ki b)
                                             (nil? b) (last (:out (last k)))
                                             :default b))
                                        (if (vector? in) in [in])))
                               :program (if program (program programs))))
                          q (q-append q kd write :write)
                          q (conj q [kernel :1d :global global :local local])
                          q (q-append q kd read :read true)]
                      {:q q :k (conj k kd) :ki (assoc ki id kd)})
                    (let [q (if read
                              (q-append-buf q
                                (if (sequential? read) read [read]) :read true) q)
                          q (if write
                              (q-append-buf q
                                (if (sequential? write) write [write]) :write) q)]
                      {:q q :k k :ki ki})))
                {:q [] :k [] :ki {}} steps)]
    {:queue (:q kdefs)
     :buffers (into #{} (mapcat (fn [k] (concat (:in k) (:out k))) (vals (:ki kdefs))))
     :final-out (-> kdefs :k last :out last)}))

(defn ^Buffer execute-pipeline
  "Calls `enqueue` with the given items of buffer & kernel steps and
  returns a slice of the first `:final-size` elements of the final output buffer
  (if one is given, else returns nil). Optionally releases all buffers.

      :queue - seq of processing steps as returned by compile-pipeline
      :buffers - seq of buffers to be released (only if release flag is truthy)
      :final-size - number of result elements (default: size of final out buffer)
      :verbose - pprint compiled queue and execution time (default false)
      :release - automatic release of all buffers (default true)"
  [{:keys [queue buffers final-out final-size]} &
   {:keys [final-size final-type verbose release] :or {release true}}]
  (if verbose
    (do (pprint queue) (time (apply cl/enqueue queue)))
    (apply cl/enqueue queue))
  (if final-out
    (let [^Buffer nb (cl/nio-buffer final-out)
          nb (condp = final-type
               :int (.asIntBuffer nb)
               :float (.asFloatBuffer nb)
               :double (.asDoubleBuffer nb)
               nb)
          final-size (or final-size (.capacity nb))
          ^Buffer result (cl/slice nb final-size)]
      (when release (apply cl/release buffers))
      result)
    (when release (apply cl/release buffers))))

(defn flipflop
  "Helper function for use with `compile-pipeline` to support kernels which
  require multiple iterations. Returns lazy-seq of `iter` repetitions of the
  given pipeline step description with the following transformation applied:
  All even repetitions have the given `in` buffer injected as the first input
  buffer (before any already existing inputs) and using `out` as output buffer.
  All odd repetitions use the opposite behavior with `out` injected as first
  input and using `in` as output buffer for this step. This allows for the
  repeated application of a kernel without having to copy intermediate results.

  Example:

      (flipflop 3 a b {:name \"Foo\" :in c :n 128 :args [[100 :int]]})

      => ({:name \"Foo\" :in [a c] :out b :n 128 :args [[100 :int]]}
          {:name \"Foo\" :in [b c] :out a :n 128 :args [[100 :int]]}
          {:name \"Foo\" :in [a c] :out b :n 128 :args [[100 :int]]})"
  [iter in out step]
  (map
    (fn[[in out]]
      (let [is (:in step)
            inseq (vec (cons in (if (sequential? is) is [is])))]
        (assoc step :in inseq :out out)))
    (take iter (cycle [[in out] [out in]]))))
