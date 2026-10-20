#version 330

// CS2's bloom downsample (bloom_cs): 13 taps, weights 1/8 centre box, 1/16 edges, 1/32 corners; the first level
// averages its 4-tap groups weighted by 1 / (1 + luma) (keeps single bright pixels from flickering).
uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

#ifdef FIRST
// CS2's soft threshold on the first level: colour x smoothstep(0, threshold, luma) (the buffer holds a quarter; the
// threshold of 0.5 is ours, FLAGGED: CS2 takes it from the map's post-processing)
const float THRESHOLD = 0.5;
vec3 t(vec3 c) { vec3 v = c * 4.0; return c * smoothstep(0.0, THRESHOLD, dot(v, vec3(0.299, 0.587, 0.114))); }
vec3 w(vec3 c) { c = t(c); return c / (1.0 + dot(c, vec3(0.299, 0.587, 0.114))); }
float k(vec3 c) { c = t(c); return 1.0 / (1.0 + dot(c, vec3(0.299, 0.587, 0.114))); }
#endif

vec3 s(vec2 o) {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    return texture(InSampler, texCoord + o * px).rgb;
}

void main() {
    vec3 a = s(vec2(-2.0, 2.0)), b = s(vec2(0.0, 2.0)), c = s(vec2(2.0, 2.0));
    vec3 d = s(vec2(-2.0, 0.0)), e = s(vec2(0.0)), f = s(vec2(2.0, 0.0));
    vec3 g = s(vec2(-2.0, -2.0)), h = s(vec2(0.0, -2.0)), i = s(vec2(2.0, -2.0));
    vec3 j = s(vec2(-1.0, 1.0)), kk = s(vec2(1.0, 1.0)), l = s(vec2(-1.0, -1.0)), m = s(vec2(1.0, -1.0));
#ifdef FIRST
    vec3 o = (w(j) + w(kk) + w(l) + w(m)) / (k(j) + k(kk) + k(l) + k(m)) * 0.5
           + (w(a) + w(b) + w(d) + w(e)) / (k(a) + k(b) + k(d) + k(e)) * 0.125
           + (w(b) + w(c) + w(e) + w(f)) / (k(b) + k(c) + k(e) + k(f)) * 0.125
           + (w(d) + w(e) + w(g) + w(h)) / (k(d) + k(e) + k(g) + k(h)) * 0.125
           + (w(e) + w(f) + w(h) + w(i)) / (k(e) + k(f) + k(h) + k(i)) * 0.125;
#else
    vec3 o = (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (e + j + kk + l + m) * 0.125;
#endif
    fragColor = vec4(o, 1.0);
}
