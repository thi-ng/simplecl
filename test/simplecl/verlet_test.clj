(ns simplecl.verlet-test
  "2D Verlet physics cloth simulation example."
  ^{:author "Karsten Schmidt"}
  (:import
    [java.awt Color Graphics2D RenderingHints]
    [java.awt.image BufferedImage]
    [javax.imageio ImageIO])
  (:require
    [simplecl.core :as cl]
    [simplecl.utils :as clu]
    [simplecl.ops :as ops]
    [structgen.core :as gen]
    [structgen.parser :as p]
    [clojure.java.io :as io])
  (:use
    [clojure.test]))

(def cl-program
  "Location of the OpenCL program"
  "kernels/physics.cl")

;; parse typedefs in OpenCL program and register all found struct types
(gen/reset-registry!)
(gen/register! (p/parse-specs (slurp (clu/resource-stream cl-program))))

(defn ensure-odd
  "Returns `x` if odd or `x+1` if `x` is even."
  [x] (if (even? x) (inc x) x))

(defn grid-particles
  "Returns a lazy-seq of uniformly arranged positions within the rect
  defined by x,y,w,h and the given number of cols & rows."
  [x y w h cols rows]
  (for [yy (range y (+ y h) (/ h rows))
        xx (range x (+ x w) (/ w cols))]
    {:pos [xx yy] :prev [xx yy] :mass 1.0}))

(defn grid-springs
  "Returns a lazy-seq of spring definitions connecting grid points
  in the following way:

      a -- b -- c ...
      |    |    |
      d -- e -- f ...
      |    |    |
      .    .    .

  Each spring has two end points (a & b) defined as grid indices,
  as well as a rest length and strength (0.0 ... 1.0)."
  [cols rows rlen strength]
  (map
    (fn[[a b]] {:a a :b b :restLength rlen :strength strength})
    (concat
      (for [yy (range 0 (* cols rows) cols) xx (range 1 cols)]
        [(+ yy xx -1) (+ yy xx)])
      (for [xx (range cols) yy (range cols (* cols rows) cols)]
        [(+ yy xx (- cols)) (+ yy xx)]))))

(defn make-grid
  "Returns a map of particles & connection springs for the given specs."
  [& {:keys [x y w h cols rows rest-len strength]}]
  {:particles (vec (grid-particles x y w h cols rows))
   :springs (grid-springs cols rows rest-len strength)
   :rest-len rest-len
   :cols cols
   :rows rows})

(defn grid-index
  "Computes grid index for the given `x` & `y` grid position and `stride` (grid width)."
  [x y stride] (int (+ (* y stride) x)))

(defn lock-particles
  "Takes a `grid`, a seq of grid `coordinates` and updates the `:isLocked`
  property of those grid points. Returns updated grid."
  [{:keys [cols rows] :as grid} coords locked?]
  (reduce
    (fn [grid [x y]]
      (assoc-in grid
        [:particles (grid-index x y cols) :isLocked]
        (if locked? 1 0)))
    grid coords))

(defn move-and-lock-particles
  "Takes a `grid` and a seq of `[x y ox oy]` vectors, then updates these
  particles by locking and moving them by `[ox oy]`. Returns updated grid.
  `x` & `y` are grid positions, `ox` & `oy` are world space offsets."
  [{:keys [cols rows] :as grid} & particles]
  (reduce
    (fn [grid [x y ox oy]]
      (update-in grid [:particles (grid-index x y cols)]
        (fn[{[px py] :pos :as p}]
          (assoc p :pos [(+ px ox) (+ py oy)] :isLocked 1))))
    grid particles))

(defn make-pipeline
  "Takes a state map of proconfigured OpenCL data structures and
  generates an OpenCL processing pipeline to execute a single timestep
  of the Verlet physics simulation.

  The pipeline consists of the following phases:

  ParticleUpdate - compute & apply forces to all particles
  SpringUpdate - compute relaxation for all springs, update particle positions
  ConstrainParticles - apply circle constraints/obstacles to particles

  The SpringUpdate phase is applied multiple times (defined by :iter param in
  state map) and uses the simplecl.ops/flipflop operation.

  The ConstrainParticles phase includes a synchronous read operation of the
  particle buffer, ensuring the OpenCL computation is complete before
  returning to Clojure."
  [{:keys [p-buf q-buf s-buf c-buf bounds gravity drag nump nums numc iter]}]
  (ops/compile-pipeline :steps
    (concat
     [{:name "ParticleUpdate" :in [p-buf bounds gravity] :out q-buf
       :n nump :write [:in :out] :args [[nump :int] [drag :float]]}]
     [{:write s-buf}]
     (ops/flipflop iter q-buf p-buf
       {:name "SpringUpdate" :in [s-buf bounds] :n nums :args [[nums :int]]})
     [{:write c-buf}]
     [{:name "ConstrainParticles" :in [q-buf c-buf] :out p-buf
       :n nump :read [:out] :args [[nump :int] [numc :int]]}])))

(defn init-physics
  "Initializes and returns a map of all OpenCL data structures required
  for the Verlet physics simulation. The buffers for particles, springs and
  circle constraints are generated in the format defined/required by the
  C structs in the OpenCL program and ensure correct memory alignment for the
  individual struct properties."
  [& {:keys [program grid circles bounds gravity drag iter]}]
  (let [cl-state (cl/init-state :device :cpu :program (clu/resource-stream program))]
    (cl/with-state cl-state
      (let [{:keys [particles springs]} grid
            nump (count particles)
            nums (count springs)
            numc (count circles)
            iter (ensure-odd iter)
            p-struct (gen/make-struct :Particles [:particles :Particle2 nump])
            s-struct (gen/make-struct :Springs [:springs :Spring nums])
            c-struct (gen/make-struct :Circles [:circles :Circle numc])
            state (merge
                    {:cl-state cl-state
                     :p-struct p-struct :s-struct s-struct :c-struct c-struct
                     :drag drag :nump nump :nums nums :numc numc :iter iter
                     :grid grid :circles circles}
                    (ops/init-buffers 1 1
                      :p-buf {:wrap (gen/encode p-struct {:particles particles})}
                      :q-buf {:wrap (gen/encode p-struct {})}
                      :s-buf {:wrap (gen/encode s-struct {:springs springs}) :usage :readonly}
                      :c-buf {:wrap (gen/encode c-struct {:circles circles}) :usage :readonly}
                      :bounds {:wrap bounds :type :float :usage :readonly}
                      :gravity {:wrap gravity :type :float :usage :readonly}))]
        (assoc state :pipeline (make-pipeline state))))))

(defn update-pipeline
  "Releases the current OpenCL particle & spring buffers and generates new ones for
  the current particles/spring, then builds an updated OpenCL processing pipeline.
  Returns updated physics state map."
  [{:keys [cl-state grid p-struct s-struct p-buf s-buf] :as state}]
  (cl/with-state cl-state
    (cl/release p-buf s-buf)
    (let [{:keys [particles springs]} grid
          state (merge state
                  (ops/init-buffers 1 1
                    :p-buf {:wrap (gen/encode p-struct {:particles particles})}
                    :s-buf {:wrap (gen/encode s-struct {:springs springs}) :usage :readonly})
                  {:nump (count particles) :nums (count springs)})]
      (assoc state :pipeline (make-pipeline state)))))

(defn physics-time-step
  "Executes `iter` iterations of the current OpenCL processing pipeline."
  [iter pipeline verbose?]
  (dotimes [i iter]
    (when verbose? (prn i))
    (ops/execute-pipeline pipeline :verbose false :release false)
    (.flush cl/*queue*)))

(defn ^BufferedImage make-image
  "Creates a new ARGB BufferedImage of the given size."
  [width height]
  (BufferedImage. width height BufferedImage/TYPE_INT_ARGB))

(defn save-image
  ([^BufferedImage img ^String path]
    (save-image img "PNG" path))
  ([^BufferedImage img ^String fmt ^String path]
    (prn "saving image:" path)
    (with-open [out (io/output-stream path)]
      (ImageIO/write img fmt out))))

(defn ^Color as-color
  "Converts the scalar value `x` into a java.awt.Color instance
  using HSB colorspace mapping."
  [x alpha]
  (-> x
    (Color/HSBtoRGB 1.0 1.0)
    (bit-and 0xffffff)
    (bit-or (bit-shift-left alpha 24))
    (unchecked-int)
    (Color. true)))

(defn clear-image
  [^Graphics2D gfx width height]
  (doto gfx
    (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setPaint (Color. 255 255 255))
    (.fillRect 0 0 width height)))

(defn render
  "Renders the current state of the physics simulation to an image and
  exports it to the given file path."
  [^Graphics2D gfx {:keys [p-struct p-buf grid]}]
  (let [particles (vec (:particles (gen/decode p-struct (cl/nio-buffer p-buf))))
        rlen (:rest-len grid)]
    (doseq [{sa :a sb :b} (:springs grid)]
     (let [[ax ay] (:pos (particles sa))
           [bx by] (:pos (particles sb))
           dx (- bx ax)
           dy (- by ay)
           l (/ (Math/sqrt (+ (* dx dx) (* dy dy))) rlen)]
       (.setPaint gfx (as-color l 0x80))
       (.drawLine gfx (int ax) (int ay) (int bx) (int by))))))

(def physics-state
  "Initial state map & data structures for Verlet physics simulation."
  (let [cols 400 rows 100
        r3 (int (* rows 0.3333))
        r23 (int (* rows 0.6666))
        rmax (dec rows)
        rest-len 5.0]
    (init-physics
      :program cl-program
      :grid (-> (make-grid
                  :x 360 :y 100 :w 1200 :h (* rows rest-len 0.5)
                  :cols cols :rows rows
                  :rest-len rest-len :strength 0.95)
                (lock-particles (map (fn[x] [x 0]) (range 4 cols 10)) true)
                (move-and-lock-particles
                    [0 r3 -300 0] [(dec cols) r3 300 0]
                    [0 r23 -250 80] [(dec cols) r23 250 80]
                    [0 rmax -200 200] [(dec cols) rmax 200 200]
                    [50 rmax -100 300] [350 rmax 100 300]
                    [100 rmax -40 400] [300 rmax 50 400]
                    [200 rmax 0 450]))
      :circles [{:pos [820 660] :radius 100} {:pos [1100 660] :radius 100}]
      :bounds [0 0 1919 1079]
      :gravity [0 1.25]
      :drag 0.02
      :iter 41)))

(defn run-sim
  [state from to step width height]
  (prn "particles: " (count (get-in state [:grid :particles])))
  (prn "springs:   " (count (get-in state [:grid :springs])))
  (cl/with-state (:cl-state state)
    (prn "build log:" (cl/build-log))
    (physics-time-step from (:pipeline state) true)
    (let [img (make-image width height)
          gfx (.createGraphics img)]
      (loop [iter (range from to step) state state]
        (when-let [i (first iter)]
          (clear-image gfx width height)
          (render gfx state)
          (save-image img (format "export/gridx-%d.png" i))
          (let [rows (get-in state [:grid :rows])
                r3 (int (* rows 0.3333))
                r23 (int (* rows 0.6666))
                rmax (dec rows)
                unlock (condp = i
                         200 [[200 rmax]]
                         230 [[100 rmax] [300 rmax]]
                         260 [[50 rmax] [350 rmax]]
                         275 [[0 rmax] [399 rmax]]
                         290 [[0 r23] [399 r23]]
                         300 [[0 r3] [399 r3]]
                         nil)
                state (if (= i 305) (update-pipeline (assoc state :numc 0)) state)
                state (if (seq unlock)
                        (let [{:keys [p-struct p-buf grid]} state
                              particles (:particles (gen/decode p-struct (cl/nio-buffer p-buf)))
                              grid (assoc grid :particles particles)
                              grid (lock-particles grid unlock false)]
                          (update-pipeline (assoc state :grid grid)))
                        state)]
            (time (physics-time-step step (:pipeline state) false))
            (recur (rest iter) state)))))))

(defn -main [& args] (run-sim physics-state 5 600 5 1920 1080))
