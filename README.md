# simplecl

Clojure wrapper & highlevel processing pipeline ops for JOCL/OpenCL

Simplecl intends to enable a somewhat declarative interop approach between Clojure and OpenCL compute kernels. It provides thin wrappers for basic OpenCL data structures (contexts, devices, buffers, programs, kernels), as well as an highlevel API to define & configure multi-kernel & multi-program workflows and transforming data between Clojure's data structures and their native OpenCL representation as byte buffers.

Simplecl is still a young project, but has been sucessfully used in production in several projects and is already (fairly) well documented.

## Leiningen coordinates

    :::clojure
    [com.postspectacular/simplecl "0.1.5"]

## Usage & examples

Please see the extensive doc strings, Marginalia docs and the 2D Verlet physics demo for a complete & visual example use case:

### Verlet cloth sim

Running the command below will run a basic [2d cloth simulation](https://bitbucket.org/postspectacular/simplecl/src/tip/test/simplecl/verlet_test.clj) with 40,000 particles & 80,000 springs and generate an image sequence of 120 PNGs in the `/export` sub-directory.

    :::bash
    lein with-profile test run -m simplecl.verlet-test

A video of three different variations is here: [verlet-cloth-sim.mp4](http://media.postspectacular.com/2012/simplecl/20121208-gridx-hd720.mp4)

This demo is also showcasing usage & integration of the [structgen](http://hg.postspectacular.com/structgen) partner library to ease the task of aligning nested data structures in an OpenCL compatible way.

### 3D Strange attractor

A sneak peak of an upcoming example kernel pipeline to:

1. compute a 2d strange attractor
2. apply a 2D distortion filter to all points
3. map the result points onto the surface of a sphere and
4. apply a 3D distortion filter to all points
5. do a standard 3d > 2d camera-screen projection

A short video fragment too: [attractor.mp4](http://media.postspectacular.com/2012/simplecl/20121205-attractor-grad-hd720.mp4) (10M points/frame, ~780-840ms/frame)

More examples & documentation will be added ASAP.

## License

Copyright © 2012 Karsten Schmidt / PostSpectacular Ltd.

Distributed under the Eclipse Public License, the same as Clojure.
