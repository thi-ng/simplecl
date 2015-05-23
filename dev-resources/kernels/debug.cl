/**
 * OpenCL test kernel to analyze memory alignment of structs
 *
 * @author Karsten Schmidt
 *
 * (c) 2012 PostSpectacular Ltd.
 * Licensed under the Eclipse Public License, Version 1.0
 */

#define OFFSETOF(type, field) ((unsigned long) &(((type *) 0)->field))

typedef struct {
    float4   a;    // 0
    int      b[8]; // 16
    char     c[8]; // 24
    float3   d;    // 32
} Foo;

kernel void Debug(global Foo* in, global Foo* out, const unsigned int n, const float deltaSq) {
	unsigned int id = get_global_id(0);
	if (id < n) {
		global Foo *p = &out[id];
		p->a = (float4)(OFFSETOF(Foo, b),
		                OFFSETOF(Foo, c),
		                OFFSETOF(Foo, d),
		                (sizeof *p));
	}
}
