/**
 * OpenCL 2D verlet physics demo kernels
 *
 * v0.1.0 - initial skeleton implementation
 * v0.1.1 - added particle locking & circle constraints
 *
 * @author Karsten Schmidt
 *
 * (c) 2012 PostSpectacular Ltd.
 * Licensed under the Eclipse Public License, Version 1.0
 */

typedef struct {
    float2 pos;
    float2 prev;
    float2 force;
    float mass;
    char isLocked;
} Particle2;

typedef struct {
    int a;
    int b;
    float restLength;
    float strength;
} Spring;

typedef struct {
    float2 min;
    float2 max;
} Rect;

typedef struct {
    float2 pos;
    float  radius;
} Circle;

void applyForce(global Particle2 *p, const float2 gravity);
void scaleVelocity(global Particle2 *p, const float scl);
void constrainParticle(global Particle2 *p, global const Rect *bounds);
void applyCircleConstraint(global Particle2 *p, global const Circle *circle);

void applyForce(global Particle2 *p, const float2 gravity) {
    const float2 t = p->pos;
    p->pos += fma(p->force + gravity, (float2)(p->mass), p->pos - p->prev);
    p->prev = t;
    p->force = (float2)0;
}

void scaleVelocity(global Particle2 *p, const float scl) {
    p->prev = fma(p->pos - p->prev, (float2)(1.0f - scl), p->prev);
}

void constrainParticle(global Particle2 *p, global const Rect *bounds) {
    p->pos = clamp(p->pos, bounds->min, bounds->max);
}

void applyCircleConstraint(global Particle2 *p, global const Circle *circle) {
    const float2 delta = p->pos - circle->pos;
    if (length(delta) < circle->radius) {
        p->pos = circle->pos + normalize(delta) * circle->radius;
    }
}

kernel void ParticleUpdate(global Particle2* in,
                           global const Rect* bounds,
                           global const float2* gravity,
                           global Particle2* out,
                           const unsigned int n,
                           const float drag) {
	unsigned int id = get_global_id(0);
	if (id < n) {
		global Particle2 *p = &in[id];
        if (p->isLocked == 0) {
    		scaleVelocity(p, drag * drag);
	   	    applyForce(p, gravity[0]);
            constrainParticle(p, bounds);
        }
        out[id] = *p;
	}
}

kernel void SpringUpdate(global Particle2* particles,
                         global const Spring* springs,
                         global const Rect* bounds,
                         global Particle2* out,
                         const unsigned int n) {
    unsigned int id = get_global_id(0);
    if (id < n) {
        global const Spring *s = &springs[id];
        global Particle2 *a = &particles[s->a];
        global Particle2 *b = &particles[s->b];
        const float2 delta = b->pos - a->pos;
        const float dist = length(delta) + 1e-6;
        const float am = 1.0f/a->mass;
        const float bm = 1.0f/b->mass;
        const float normDistStrength = (dist - s->restLength) / (dist * (am + bm)) * s->strength;
        if (a->isLocked == 0) {
            a->pos += (delta * normDistStrength * am);
            constrainParticle(a, bounds);
            out[s->a]=*a;
        }
        if (b->isLocked == 0) {
            b->pos += (delta * -normDistStrength * bm);
            constrainParticle(b, bounds);
            out[s->b]=*b;
        }
    }
}

kernel void ConstrainParticles(global Particle2* particles,
                               global const Circle* constraints,
                               global Particle2* out,
                               const unsigned int np,
                               const unsigned int nc) {
    unsigned int id = get_global_id(0);
    if (id < np) {
        global Particle2 *p = &particles[id];
        for(unsigned int i=0; i<nc; i++) {
            global const Circle *c = &constraints[i];
            applyCircleConstraint(p,c);
        }
        out[id] = *p;
    }
}
