# thi.ng/simplecl

Clojure wrapper & highlevel processing pipeline ops for JOCL/OpenCL

SimpleCL intends to enable a somewhat declarative interop approach
between Clojure and OpenCL compute kernels. It provides thin wrappers
for basic OpenCL data structures (contexts, devices, buffers,
programs, kernels), as well as an highlevel API to define & configure
multi-kernel & multi-program workflows and transforming data between
Clojure's data structures and their native OpenCL representation as
byte buffers.

SimpleCL is still a young project (although it's was originally
created in 2012, it has been dormant for a while), yet has been
sucessfully used in production in several projects and is fairly well
documented.

## Leiningen coordinates

```clojure
[thi.ng/simplecl "0.2.0"]
```

## Usage & examples

Please see the extensive doc strings and the 2D Verlet physics demo
for a complete & visual example use case:

### Verlet cloth sim

Running the command below will run a basic
[2d cloth simulation](test/thi/ng/simplecl/test/verlet.clj) with 40,000
particles & 80,000 springs and generate an image sequence of 120 PNGs
in the `/export` sub-directory.

```shell
lein with-profile test run -m thi.ng.simplecl.test.verlet
```

The image sequence can then be converted into an MP4 using this `ffmpeg` command:

```shell
ffmpeg -r 30 -f image2 -start_number 5 -i "export/verlet-%04d.png" \
       -vcodec libx264 -preset slow -crf 23 -pix_fmt yuv420p \
       -f mp4 -threads 0 verlet.mp4 -y
```

A video of three different variations is here:
[verlet-cloth-sim.mp4](http://media.thi.ng/2012/simplecl/20121208-gridx-hd720.mp4)

This demo is also showcasing usage & integration of the
[structgen](http://thi.ng/structgen) partner library to ease the task
of aligning nested data structures in an OpenCL compatible way (mapping
to OpenCL structs).

### 3D Strange attractor

A sneak peak of an upcoming example kernel pipeline to:

1. compute a 2d strange attractor
2. apply a 2D distortion filter to all points
3. map the result points onto the surface of a sphere and
4. apply a 3D distortion filter to all points
5. do a standard 3d > 2d camera-screen projection

A short video fragment too:
[attractor.mp4](http://media.thi.ng/2012/simplecl/20121205-attractor-grad-hd720.mp4)
(10M points/frame, ~780-840ms/frame)

More examples & documentation will be added ASAP.

## License

Copyright © 2012 - 2015 Karsten Schmidt

Distributed under the Apache Software License 2.0
