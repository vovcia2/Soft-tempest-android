package pl.vovcia.softtempest

/**
 * GLSL ES 3.00 sources for the noise overlay.
 *
 * Design notes (see README, "Shader design"):
 *  * The noise is a pure function of (pixel, frame seed). The seed is a fresh random 32-bit value
 *    uploaded every frame, so no two frames share a pattern and an attacker averaging N captured
 *    frames only gains the usual 1/sqrt(N) on the *noise*, never a fixed residue.
 *  * Hashing is PCG-style integer arithmetic (Jarzynski & Olano, "Hash Functions for GPU Rendering",
 *    JCGT 2020) rather than the classic `fract(sin(dot(...)) * 43758.5453)`, which has visible
 *    low-precision structure on mobile GPUs and is far from uniform.
 *  * Output is written with PREMULTIPLIED alpha, because Android surfaces composited by
 *    SurfaceFlinger are premultiplied. Writing straight alpha would produce a colour fringe and a
 *    brightness bias in the composited result.
 */
object Shaders {

    /** Full-screen triangle generated from gl_VertexID; no vertex buffers are needed. */
    const val VERTEX = """#version 300 es
void main() {
    // Covers clip space with a single oversized triangle (no seams, no diagonal artefacts).
    vec2 p = vec2(
        (gl_VertexID == 1) ? 3.0 : -1.0,
        (gl_VertexID == 2) ? 3.0 : -1.0
    );
    gl_Position = vec4(p, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;

uniform float uTime;        // seconds since the renderer started (wrapped), for slow modulation
uniform float uAmplitude;   // overlay opacity 0..1
uniform int   uMode;        // 0 = white, 1 = high-frequency horizontal, 2 = temporal
uniform vec2  uResolution;  // surface size in pixels
uniform uint  uSeed;        // fresh random seed uploaded every frame

out vec4 fragColor;

const int MODE_WHITE = 0;
const int MODE_HFREQ_HORIZONTAL = 1;
const int MODE_TEMPORAL = 2;

// --- PCG-family integer hashes -------------------------------------------------------------

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

uvec3 pcg3d(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return v;
}

// Uniform float in [0,1) from a 32-bit hash: use the top 24 bits so the result is exact in fp32.
float toUnit(uint h) {
    return float(h >> 8u) * (1.0 / 16777216.0);
}

// One uniform sample in [0,1) at integer pixel (x, y) for the given seed / channel.
float sample1(ivec2 p, uint seed, uint channel) {
    uvec3 h = pcg3d(uvec3(uint(p.x), uint(p.y), seed ^ (channel * 0x9E3779B9u)));
    return toUnit(h.x ^ pcg(h.y + h.z));
}

// --- Spectral shaping ------------------------------------------------------------------------

// Second-difference along X: n(x) = h(x-1) - 2 h(x) + h(x+1), scaled to roughly [-1, 1].
// Frequency response |H(w)| = 4 sin^2(w/2): zero at DC, maximum at the horizontal Nyquist rate.
// Each pixel therefore differs strongly from its row neighbours, which concentrates the energy in
// the highest horizontal spatial frequencies (i.e. at the pixel-clock rate of the video path).
float hfreqHorizontal(ivec2 p, uint seed) {
    float a = sample1(p + ivec2(-1, 0), seed, 0u);
    float b = sample1(p,                seed, 0u);
    float c = sample1(p + ivec2( 1, 0), seed, 0u);
    return clamp((a - 2.0 * b + c) * 0.5, -1.0, 1.0);
}

// --- Main ----------------------------------------------------------------------------------

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);

    // Slow secondary entropy source: even if the CPU seed were to repeat, uTime perturbs it.
    uint timeSalt = uint(uTime * 1000.0);
    uint seed = pcg(uSeed ^ (timeSalt * 0x85EBCA6Bu));

    vec3  rgb;
    float alpha;

    if (uMode == MODE_HFREQ_HORIZONTAL) {
        // Signed high-pass noise; sign selects black or white, magnitude drives opacity.
        float n = hfreqHorizontal(p, seed);
        rgb   = vec3(step(0.0, n));
        alpha = uAmplitude * abs(n);
    } else if (uMode == MODE_TEMPORAL) {
        // Several independent horizontal-HF sub-patterns per frame. Each row picks one of them
        // by its own per-frame hash, and every pixel gets an additional random sign flip, so
        // the pattern decorrelates along the scan (time) axis as well as across frames.
        uint row = pcg(uint(p.y) * 7919u ^ seed);
        uint sub = row & 3u;
        uint subSeed = seed + (sub + 1u) * 0x9E3779B9u;
        float n = hfreqHorizontal(p, subSeed);
        float flip = (sample1(p, seed ^ 0xA511E9B3u, 2u) < 0.5) ? -1.0 : 1.0;
        n *= flip;
        rgb   = vec3(step(0.0, n));
        alpha = uAmplitude * abs(n);
    } else {
        // White noise: independent uniform value per channel per pixel, constant alpha.
        rgb = vec3(
            sample1(p, seed, 0u),
            sample1(p, seed, 1u),
            sample1(p, seed, 2u)
        );
        alpha = uAmplitude;
    }

    // Premultiplied alpha for SurfaceFlinger.
    fragColor = vec4(rgb * alpha, alpha);
}
"""
}
