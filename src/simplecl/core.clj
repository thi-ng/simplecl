(ns simplecl.core
  "Clojure wrappers around OpenCL & JOCL."
  ^{:author "Karsten Schmidt"}
  (:import
   [com.jogamp.opencl
    CLResource CLBuffer CLMemory$Mem
    CLCommandQueue CLContext CLKernel
    CLDevice CLDevice$Type
    CLProgram CLProgram$Status
    CLPlatform]
   [com.jogamp.opencl.util Filter]
   [com.jogamp.common.nio Buffers]
   [java.nio Buffer ByteBuffer DoubleBuffer FloatBuffer IntBuffer])
  (:require
   [simplecl.utils :as clu]
   [clojure.java.io :as io]))

;;(set! *warn-on-reflection* true)

(def usage-types
  "A collection of buffer usage types required for `make-buffer`."
  {:readonly CLMemory$Mem/READ_ONLY
   :readwrite CLMemory$Mem/READ_WRITE
   :writeonly CLMemory$Mem/WRITE_ONLY
   :allocate CLMemory$Mem/ALLOCATE_BUFFER
   :copy CLMemory$Mem/COPY_BUFFER
   :use CLMemory$Mem/USE_BUFFER})

(def device-types
  "Common OpenCL device types."
  {:all CLDevice$Type/ALL
   :cpu CLDevice$Type/CPU
   :gpu CLDevice$Type/GPU
   :accelerator CLDevice$Type/ACCELERATOR})

(def build-opts
  "Common OpenCL compiler options"
  {:fast-math "-cl-fast-relaxed-math"
   :enable-mad "-cl-mad-enable"
   :no-signed-zeros "-cl-no-signed-zeros"})

(def build-states
  "Possible OpenCL program build states. Also see `build-status` fn."
  {CLProgram$Status/BUILD_SUCCESS :success
   CLProgram$Status/BUILD_NONE :none
   CLProgram$Status/BUILD_IN_PROGRESS :in-progress
   CLProgram$Status/BUILD_ERROR :error})

;;; Useful pre-defined filters for selecting CL platforms. A number of those
;;; can be passed to `select-platform`. Each filter is implemented as HOF,
;;; takes at least one argument for querying a platform and returns a
;;; com.jogamp.opencl.util.Filter proxy.

(defn platform-has-extension?
  [^String x]
  (proxy [Filter] []
    (accept [^CLPlatform p]
      (.isExtensionAvailable p x))))

(defn platform-has-min-version?
  [major minor]
  (proxy [Filter] []
    (accept [^CLPlatform p]
      (.isAtLeast p major minor))))

(defn platform-vendor-matches?
   [re]
   (proxy [Filter] []
     (accept [^CLPlatform p]
       (not (nil? (re-find re (.getVendor p)))))))

;; # CL state handling

(def ^:dynamic *platform* nil)
(def ^:dynamic *context* nil)
(def ^:dynamic *device* nil)
(def ^:dynamic *program* nil)
(def ^:dynamic *queue* nil)
(def ^:dynamic *max-worksize* 256)

(defmacro with-platform
  [^CLPlatform p & body]
  `(binding [^CLPlatform *platform** ~p] (do ~@body)))

(defmacro with-context
  [^CLContext ctx & body]
  `(binding [^CLContext *context* ~ctx] (do ~@body)))

(defmacro with-device
  [^CLDevice dev & body]
  `(binding [^CLDevice *device* ~dev] (do ~@body)))

(defmacro with-program
  [^CLProgram p & body]
  `(binding [^CLProgram *program* ~p] (do ~@body)))

(defmacro with-queue
  [^CLCommandQueue q & body]
  `(binding [^CLCommandQueue *queue* ~q] (do ~@body)))

(defmacro with-state
  [state & body]
  `(let [s# ~state]
     (binding [^CLContext *context* (or (:ctx s#) *context*)
               ^CLDevice *device* (or (:device s#) *device*)
               ^CLProgram *program* (or (:program s#) *program*)
               ^CLCommandQueue *queue* (or (:queue s#) *queue*)]
       (do ~@body))))

;; # CL inquiries & utilities

(defn available-platforms
  "Returns an array of available CL platforms."
  [] (into [] (CLPlatform/listCLPlatforms)))

(defn ^CLPlatform select-platform
  "Returns the platform matching the given filter criteria
  (see `platform-xxx` filter fns). Without arguments, selects
  the platform with the latest OpenCL version."
  ([] (CLPlatform/getDefault))
  ([f & more] (CLPlatform/getDefault (into-array (cons f more)))))

(defn platform-extensions
  "Returns a set of OpenCL extension names"
  [^CLPlatform p] (into #{} (.getExtensions p)))

(defn available-devices
  "Returns a vector of devices associated with the given context or platform
  (defaults to current `*context*`). A device type keyword can be specified
  to filter results optionally."
  ([ctx-or-platform] (available-devices ctx-or-platform :all))
  ([ctx-or-platform dev-type]
     (cond
      (isa? (type ctx-or-platform) CLContext)
      (vec (filter #(= (.getType ^CLDevice %) (device-types dev-type))
                   (.getDevices ^CLContext ctx-or-platform)))
      (isa? (type ctx-or-platform) CLPlatform)
      (into [] (.listCLDevices ^CLPlatform ctx-or-platform
                               (into-array [(device-types dev-type)])))
      :default
      (throw (IllegalArgumentException. "Argument is not a CLContext or CLPlatform")))))

(defn ^CLDevice max-device
  "Returns the maximum FLOPS device for the given context or platform
  (default current `*context*`). An additional device type can be given
  optionally (see `device-types`)."
  ([] (.getMaxFlopsDevice ^CLContext *context*))
  ([ctx-or-platform] (.getMaxFlopsDevice ctx-or-platform))
  ([ctx-or-platform dev-type]
     (cond
      (isa? (type ctx-or-platform) CLContext)
      (.getMaxFlopsDevice ctx-or-platform
                          (get device-types dev-type CLDevice$Type/DEFAULT))
      (isa? (type ctx-or-platform) CLPlatform)
      (.getMaxFlopsDevice ctx-or-platform
                          (into-array (get device-types dev-type CLDevice$Type/DEFAULT)))
      :default
      (throw (IllegalArgumentException. "Argument is not a CLContext or CLPlatform")))))

(defn max-workgroup-size
  "Returns the maximum local workgroup size for the given `kernel` and `device`.
  If the `device` is omitted, the current `*device*` is used by default."
  ([^CLKernel kernel] (max-workgroup-size ^CLKernel kernel *device*))
  ([^CLKernel kernel ^CLDevice device] (.getWorkGroupSize kernel device)))

(defn ^Buffer nio-buffer
  "Returns the underlying NIO direct buffer for the given CLBuffer."
  [^CLBuffer b] (.getBuffer b))

(defn ^Buffer slice
  "Produces a slice of the first `len` values of the given NIO buffer.
  A `start` index can be given optionally."
  ([b len] (slice b 0 len))
  ([b start len]
     (let [b (if (isa? (type b) CLBuffer)
               (nio-buffer b) b)]
       (.position b start)
       (.limit b (+ start len))
       (let [nb (.slice b)]
         (.position b start)
         (.limit b (.capacity b))
         nb))))

(defmacro buffer-seq*
  [type suffix]
  (let [name (symbol (str "buffer-seq-" (name suffix)))]
    `(defn ^:private ~name
       [^{:tag ~type} b#]
       (lazy-seq
        (when (pos? (.remaining b#))
          (cons (.get b#) (~name b#)))))))

(buffer-seq* ByteBuffer :byte)
(buffer-seq* DoubleBuffer :double)
(buffer-seq* FloatBuffer :float)
(buffer-seq* IntBuffer :int)

(defn ^:private buffer-seq-generic
  [^Buffer b]
  (lazy-seq
   (when (pos? (.remaining b))
     (cons (.get b) (buffer-seq-generic b)))))

(defn buffer-seq
  "Produces a lazy-seq of the remaining values in the given NIO buffer."
  [b]
  (let [t (type b)
        [t b] (if (isa? t CLBuffer)
                [(type (nio-buffer b)) (nio-buffer b)]
                [t b])]
    (cond
     (isa? t ByteBuffer) (buffer-seq-byte b)
     (isa? t DoubleBuffer) (buffer-seq-double b)
     (isa? t FloatBuffer) (buffer-seq-float b)
     (isa? t IntBuffer) (buffer-seq-int b)
     :default (buffer-seq-generic b))))

(defn release
  "Releases any resources of the given JOCL items. Called on a CLContext it will
  also release any programs, queues and buffers. Returns nil."
  [& items]
  (dorun (map #(.release ^CLResource %) items)))

(defn rewind
  "Rewinds the given buffers (CLBuffers or NIO buffers).
  Throws IllegalArgumentException for any other type.
  Returns last buffer arg."
  [& buffers]
  (doseq [b buffers]
    (let [t (type b)]
      (cond
       (isa? t Buffer) (.rewind ^Buffer b)
       (isa? t CLBuffer) (.rewind (nio-buffer b))
       :default (throw (IllegalArgumentException.
                        (str "can't rewind a " t))))))
  (last buffers))

(defn build-log
  "Returns the build log string of `program` for the given `device`.
  Program and device default to current `*program*` & `*device*` values."
  ([] (build-log *program* *device*))
  ([^CLProgram program] (build-log program *device*))
  ([^CLProgram program ^CLDevice device]
     (.getBuildLog program device)))

(defn build-status
  "Returns the build status of `program` for the given `device`.
  Program and device default to current `*program*` & `*device*` values.
  See `build-states` for possible result values."
  ([] (build-status *program* *device*))
  ([^CLProgram program] (build-status program *device*))
  ([^CLProgram program ^CLDevice device]
     (get build-states (.getBuildStatus program *device*))))

(defn build-ok?
  ([] (build-ok? *program* *device*))
  ([^CLProgram program] (build-ok? program *device*))
  ([^CLProgram program ^CLDevice device]
     (= :success (build-status program device))))

(defn get-source
  "Returns the source code of `program` (if omitted defaults to `*program*`)."
  ([] (get-source *program*))
  ([^CLProgram prog] (.getSource prog)))

(defn device-driver-version
  ([] (device-driver-version *device*))
  ([^CLDevice device] (.getDriverVersion device)))

(defn release-on-shutdown
  "Adds a JVM shutdown hook to release all resources of the given context.
  If none given, uses the default `*context*`."
  ([] (release-on-shutdown *context*))
  ([^CLContext ctx]
     (when ctx
       (.addShutdownHook
        (Runtime/getRuntime)
        (Thread. (fn [] (prn "releasing CL context...") (release ctx)))))))

;; # CL data type factories

(defn ^CLContext make-context
  "Without arg, selects the platform with the latest OpenCL version and
  creates a context on all available devices. Otherwise the argument must be
  a CLPlatform or seq of CLDevices. If the plaform is given, the context will
  be created on all devices."
  ([] (CLContext/create))
  ([platform-or-devices]
     (cond
      (isa? (type platform-or-devices) CLPlatform)
        (CLContext/create ^CLPlatform platform-or-devices)
      (sequential? platform-or-devices)
        (CLContext/create (into-array CLDevice platform-or-devices))
      :default (throw (IllegalArgumentException. "Argument must be a CLPlatform or seq of CLDevice")))))

(defn ^CLProgram make-program
  "Creates an input stream for `src` and compiles it into a CLProgram using the
  current `*context*`. The program is built for all devices associated with the context.
  `opts` are either keywords matching presets defined in `build-opts` or
  actual OpenCL build option strings."
  ([src & opts]
     (with-open [^java.io.InputStream is (io/input-stream src)]
       (let [opts-array (clu/args->array string? build-opts opts)]
         (if (pos? (count opts-array))
           (.build (.createProgram ^CLContext *context* is) opts-array)
           (.build (.createProgram ^CLContext *context* is)))))))

(defn ^CLCommandQueue make-commandqueue
  "Creates a new command queue on the given `device` or current `*device*`,
  if omitted."
  ([] (make-commandqueue *device*))
  ([^CLDevice device] (.createCommandQueue device)))

(defn ^CLKernel make-kernel
  "Creates a new instance of the `name`d kernel using the given `prog` or
  current `*program*`, if omitted. The kernel *must* be configured before it
  can be used/queued. Also see `configure-kernel`, `init-kernel` and
  `execute-pipeline` fns."
  ([name] (make-kernel *program* name))
  ([^CLProgram prog name] (.createCLKernel prog name)))

(defmulti ^CLBuffer make-buffer
  "Produces a new CLBuffer instance of the given data `type`, `size`
  (number of buffer elements) and `usage`, a number of CL memory usage flags
  (see `usage-types` for further details). Usage defaults to `:readwrite`.
  Implemented as multimethod for `:double`, `:float`, and `:int`."
  (fn [type size & usage] type))

(defmacro ^:private make-buffer*
  [type f]
  `(defmethod ^{:tag CLBuffer :private true} make-buffer ~type
     [_# size# & usage#]
     (~f ^CLContext *context* (int size#)
         (clu/args->array usage-types (or usage# [:readwrite])))))

(make-buffer* :byte .createByteBuffer)
(make-buffer* :double .createDoubleBuffer)
(make-buffer* :float .createFloatBuffer)
(make-buffer* :int .createIntBuffer)

;; # OpenCL initialization & low-level operations

(defn init-state
  "Creates a map of `CLContext`, `CLDevice`, `CLCommandQueue` instances
  and optional `CLProgram` for later use by `with-state` macro.

  Arguments and their default values:

      :platform - existing CLPlatform or result of select-platform w/o args
      :context  - existing CLContext or result of make-context w/ platform
      :device   - existing CLDevice, device type keyword or result of
                  calling max-flops device for context
      :queue    - existing CLCommandQueue or result of calling
                  make-commandqueue for device
      :program  - optional, no default, if given must be one of:
                  1) an input stream to the program's source code
                  2) a vector of input stream and build option keywords
                     (see build-options for possible values)"
  [& {:keys [platform context device queue program]}]
  (let [platform (or platform (select-platform))
        ctx (or context (make-context platform))]
    (with-context ctx
      (let [device (cond (nil? device) (max-device)
                         (keyword? device) (max-device ctx device)
                         :default device)
            queue (or queue (make-commandqueue device))
            program (when program
                      (if (vector? program)
                        (apply make-program program)
                        (make-program program)))]
        {:ctx ctx :device device :queue queue :program program}))))

(defn ^CLCommandQueue enqueue
  "Submits the given buffers and kernels to the current command `*queue*` for
  execution. Buffers can be transferred asynchronously or synchronously.
  Each queued item is a vector of `[item type blocking? or args]`:

      item - CLBuffer or CLKernel instance (kernels must be pre-configured)
      type - one of :read, :write or :1d
      blocking? - only used for buffers, true for blocking transfer (default false)

  Type `:1d` is used to enqueue a 1D kernel (no other kernel types are currently
  supported) and requires the following additional arguments:

      :local - local workgroup size (e.g. as returned by max-workgroup-size)
      :global - global workgroup size (must be multiple of :local)

  The following example submits `buf-in` and `buf-out` asynchronously, then
  executes `my-kernel` and finally synchronously reads back the output buffer:

      (enqueue
        [buf-in :write]
        [buf-out :write]
        [my-kernel :1d :global 1024 :local 128]
        [buf-out :read true])

  Also see `execute-pipeline` for a higher level usage of this function."
  [& items]
  (doseq [[item type & args] items]
    (cond
     (= type :read)
     (.putReadBuffer ^CLCommandQueue *queue* item (true? (first args)))
     (= type :write)
     (.putWriteBuffer ^CLCommandQueue *queue* item (true? (first args)))
     (= type :1d)
     (let [{:keys [global local offset]
            :or {offset 0}} (apply hash-map args)]
       (.put1DRangeKernel ^CLCommandQueue *queue* item offset global local))
     :default
     (throw (IllegalArgumentException. (str "invalid type: " type))))))

(defn ^CLKernel configure-kernel
  "Configures working buffers and other arguments for the given kernel.

      buffers - a sequence of CLBuffer instances matching the kernel args
      args - a number of argument description vectors of [value :type]
             e.g. [23.0 :float] [42 :int]"
  [^CLKernel k buffers & args]
  (.putArgs k (into-array buffers))
  (doseq [[a type] args]
    (cond
     (= :int type) (.putArg k (int a))
     (= :float type) (.putArg k (float a))
     (= :double type) (.putArg k (double a))
     :default (prn "invalid arg type" type)))
  (.rewind k))

;; # Buffer operations

(defmulti ^CLBuffer fill-buffer
  "Fills all remaining elements in CLBuffer `b` with repeated calls to `f`,
  a function taking a single argument, the current buffer position.
  Rewinds and returns `b`. Acts directly on the underlying NIO buffer
  and implemented as multimethod with type hints for performance and
  to cast each result of `f` to the correct type required by `b`.
  Implemented for byte, double, float and int buffers."
  (fn [^CLBuffer b f] (class (.getBuffer b))))

(defmacro fill-buffer*
  [type cast]
  `(defmethod ^{:tag CLBuffer :private true} fill-buffer ~type
     [^CLBuffer b# f#]
     (let [^{:tag ~type} nb# (.getBuffer b#)]
       (loop [pos# (.position nb#) remaining# (.remaining nb#)]
         (when (pos? remaining#)
           (.put nb# (~cast (f# pos#)))
           (recur (inc pos#) (dec remaining#))))
       (.rewind nb#))
     b#))

(fill-buffer* ByteBuffer byte)
(fill-buffer* DoubleBuffer double)
(fill-buffer* FloatBuffer float)
(fill-buffer* IntBuffer int)

(defmulti into-buffer
  "Fills the remaining items (or less) of CLBuffer `b` with items
  from the given sequence or NIO buffer. Returns `b`. Acts directly
  on the underlying NIO buffer and implemented as multimethod with
  type hints for performance and to cast each item of the sequence
  to the correct type required by `b`. Implemented for byte, double,
  float and int buffers.

  Note: Does **NOT** rewind buffer to allow filling buffer from
  multiple sources. You MUST call `rewind` before enqueueing the
  buffer for processing."
  (fn [^CLBuffer b seq] (class (.getBuffer b))))

(defmacro into-buffer*
  [type cast]
  `(defmethod ^{:tag CLBuffer :private true} into-buffer ~type
     [^CLBuffer b# s#]
     (let [^{:tag ~type} nb# (.getBuffer b#)]
       (if (instance? Buffer s#)
         (.put nb# s#)
         (loop[s# (take (.remaining nb#) s#)]
           (when (seq s#)
             (.put nb# (~cast (first s#)))
             (recur (rest s#))))))
     b#))

(into-buffer* ByteBuffer byte)
(into-buffer* DoubleBuffer double)
(into-buffer* FloatBuffer float)
(into-buffer* IntBuffer int)

(defmulti ^CLBuffer as-clbuffer
  "Wraps a Clojure seq or NIO buffer `b` into a CLBuffer with the
  given `usage` flags. Usage defaults to `:readwrite`.
  Implemented as multimethod for byte, double, float and int buffers.

  If wrapping a seq the first arg must be a keyword to indicate the
  buffer type (see `make-buffer`). All elements of the seq are then
  copied into the buffer using `into-buffer`. Rewinds buffer before
  returning it.

  Example: `(as-clbuffer :float [1 2 3 4])`"
  (fn [b & usage] (if (keyword? b) :seq (class b))))

(defmethod ^{:tag CLBuffer :private true} as-clbuffer :seq
  [type s & usage]
  (-> (apply make-buffer type (count s) usage)
      (into-buffer s)
      (rewind)))

(defmacro as-clbuffer*
  [type view size]
  `(defmethod ^{:tag CLBuffer :private true} as-clbuffer ~type
     [^{:tag ~type} b# & usage#]
     (let [b#
           (if (.isDirect b#) b#
               (let [^ByteBuffer dest# (Buffers/newDirectByteBuffer (* ~size (.remaining b#)))]
                 (-> dest# (~view) (.put b#))
                 (rewind b# dest#)))]
       (.createBuffer ^CLContext *context* b#
                      (clu/args->array usage-types (or usage# [:readwrite]))))))

(as-clbuffer* ByteBuffer identity Buffers/SIZEOF_BYTE)
(as-clbuffer* FloatBuffer .asFloatBuffer Buffers/SIZEOF_FLOAT)
(as-clbuffer* DoubleBuffer .asDoubleBuffer Buffers/SIZEOF_DOUBLE)
(as-clbuffer* IntBuffer .asIntBuffer Buffers/SIZEOF_INT)
