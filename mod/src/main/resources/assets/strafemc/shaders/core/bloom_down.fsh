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
vec3 strafeThreshold(vec3 c) { vec3 v = c * 4.0; return c * smoothstep(0.0, THRESHOLD, dot(v, vec3(0.299, 0.587, 0.114))); }
vec3 strafeWeighted(vec3 c) { c = strafeThreshold(c); return c / (1.0 + dot(c, vec3(0.299, 0.587, 0.114))); }
float strafeWeight(vec3 c) { c = strafeThreshold(c); return 1.0 / (1.0 + dot(c, vec3(0.299, 0.587, 0.114))); }
#endif

vec3 strafeTap(vec2 o) {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    return texture(InSampler, texCoord + o * px).rgb;
}

void main() {
    vec3 a = strafeTap(vec2(-2.0, 2.0)), b = strafeTap(vec2(0.0, 2.0)), c = strafeTap(vec2(2.0, 2.0));
    vec3 d = strafeTap(vec2(-2.0, 0.0)), e = strafeTap(vec2(0.0)), f = strafeTap(vec2(2.0, 0.0));
    vec3 g = strafeTap(vec2(-2.0, -2.0)), h = strafeTap(vec2(0.0, -2.0)), i = strafeTap(vec2(2.0, -2.0));
    vec3 j = strafeTap(vec2(-1.0, 1.0)), kk = strafeTap(vec2(1.0, 1.0)), l = strafeTap(vec2(-1.0, -1.0)), m = strafeTap(vec2(1.0, -1.0));
#ifdef FIRST
    vec3 o = (strafeWeighted(j) + strafeWeighted(kk) + strafeWeighted(l) + strafeWeighted(m)) / (strafeWeight(j) + strafeWeight(kk) + strafeWeight(l) + strafeWeight(m)) * 0.5
           + (strafeWeighted(a) + strafeWeighted(b) + strafeWeighted(d) + strafeWeighted(e)) / (strafeWeight(a) + strafeWeight(b) + strafeWeight(d) + strafeWeight(e)) * 0.125
           + (strafeWeighted(b) + strafeWeighted(c) + strafeWeighted(e) + strafeWeighted(f)) / (strafeWeight(b) + strafeWeight(c) + strafeWeight(e) + strafeWeight(f)) * 0.125
           + (strafeWeighted(d) + strafeWeighted(e) + strafeWeighted(g) + strafeWeighted(h)) / (strafeWeight(d) + strafeWeight(e) + strafeWeight(g) + strafeWeight(h)) * 0.125
           + (strafeWeighted(e) + strafeWeighted(f) + strafeWeighted(h) + strafeWeighted(i)) / (strafeWeight(e) + strafeWeight(f) + strafeWeight(h) + strafeWeight(i)) * 0.125;
#else
    vec3 o = (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (e + j + kk + l + m) * 0.125;
#endif
    fragColor = vec4(o, 1.0);
}
