#version 330

// Volumetric smoke, CS2's smoke_volume pixel shader (shaders/vfx/smoke_volume, the D_SMOKE_QUALITY +
// D_SMOKE_USE_NOISE_TEXTURE + D_SMOKE_NEW_VISUALS variant) with materials/dev/smoke_volume.vmat's values: a ray from
// the camera through every smoke's voxel volume, stepped g_flRayStepLength units at a time, each step's density warped
// and eroded by the 3D smoke noise, lit by the volume's light (ambient + sun) and composited front to back.
// Everything here is in CS2 units (40 per block) with CS2's axes (z up), relative to the camera.
// Differences from CS2: no sun shadow map (the sun term is the light volume's visibility only), no moment-based
// transparency with particles, no character depth pass. Each 3D texture is a 2D atlas of 1-texel-padded slices.
// CS2's light volume (ambient light probes + the sun's visibility per voxel) is Minecraft's light here: each voxel holds
// its block and sky light, looked up in the lightmap (day, night, torches), and how open it is to the sky.

uniform sampler2D DepthSampler;
uniform sampler2D NoiseSampler;
uniform sampler2D DensitySampler;
uniform sampler2D LightSampler;
uniform sampler2D BlueNoiseSampler;
uniform sampler2D LightmapSampler;

#define MAX_SMOKES 8
#define MAX_BLASTS 5
#define MAX_TRACERS 16

layout(std140) uniform SmokeInfo {
    mat4 ClipToWorld;       // clip space -> camera-relative CS2 units
    vec4 ViewRight;         // camera axes in CS2 world space
    vec4 ViewUp;
    vec4 ViewForward;
    vec4 Timing;            // x: time (s), y: frame number, z: smokes, w: HE blasts
    vec4 SunDir;            // xyz towards the sun
    vec4 SunColor;          // rgb, w: tracers
    vec4 Flash;             // xyz: last muzzle flash / blast, w: its time
    vec4 SmokeCenter[MAX_SMOKES];   // xyz: voxel grid centre, w: atlas slot
    vec4 SmokeMin[MAX_SMOKES];      // filled voxels' bounds
    vec4 SmokeMax[MAX_SMOKES];
    vec4 SmokeState[MAX_SMOKES];    // x: age (s), y: frame blend, z: slot, w: blast mask bit
    vec4 SmokeTint[MAX_SMOKES];     // rgb team colour
    vec4 SmokeFade[MAX_SMOKES];     // x: density scale, z: tint amount (0 full), w: shrink while dissipating (1 none)
    vec4 Blast[MAX_BLASTS];         // xyz, w: time it went off
    vec4 BlastMask[2];              // per blast: which smokes it touches, as bits
    vec4 LightScale;                // x: lightmap colour -> CS2 light, y: 1 if the depth range is 0..1
    vec4 TracerStart[MAX_TRACERS];  // xyz, w: age (s)
    vec4 TracerEnd[MAX_TRACERS];    // xyz, w: > 0 lights the smoke along it
    vec4 TracerSize[MAX_TRACERS];   // x: thickness factor
};

in vec2 texCoord;
out vec4 fragColor;

// materials/dev/smoke_volume.vmat
const float RAY_STEP = 6.0;             // g_flRayStepLength
const float DENSITY = 0.85;             // g_flDensity
const float NOISE_SPEED = 1.5;          // g_flNoiseSpeed
const float NOISE_STRENGTH_LOW = 0.5;   // g_flNoiseStrengthLow
const float NOISE_STRENGTH_HIGH = 0.35; // g_flNoiseStrengthHigh
const float NOISE_NORMAL = 0.5;         // g_flNoiseNormalStrength
const float NOISE_NORMAL_DIRECT = 0.5;  // g_flNoiseNormalStrengthDirect
const float NOISE_OFFSET = -0.95;       // g_flNoiseOffset
const float VOLUME_NORMAL_BLEND = 0.5;  // g_flVolumeNormalBlend
const float NOISE_SCALE_LOW = 3.0;      // g_flNoiseScaleLow
const float NOISE_SCALE_HIGH = 7.0;     // g_flNoiseScaleHigh
const float NOISE_POWER = 1.0;          // g_flNoisePower
const float NOISE_MIN = 0.0;            // g_flNoiseMin
const float NOISE_MAX = 1.05;           // g_flNoiseMax
const float AMBIENT = 0.6;              // g_flAmbientStrength
const float DIRECT = 0.35;              // g_flDirectStrength
const float RIM = 0.25;                 // g_flRimLightStrength
const float INTERIOR_DARKENING = 0.5;   // g_flInteriorDarkening
const float BOIL = -0.3;                // g_flBoilStrength
const float AO_STRENGTH = 0.06;         // g_flAOEffectStrength
const float AO_DISTANCE = 16.0;         // g_flAOEffectDistance
const float WISPY = 0.0;                // g_flWispy
const float JITTER = 0.8;               // g_flSmokeNoiseStrength (scales the start jitter)
const vec3 SMOKE_COLOR = vec3(1.0);     // g_vSmokeColor
const float GOLDEN = 0.618034;

// ---------------------------------------------------------------- atlases

// density / light volume: slot rows of 32 slices, each 34x34 (32 voxels + a 1-texel clamped border)
vec4 volume(sampler2D tex, float slot, vec3 uvw) {
    uvw = clamp(uvw, 0.0, 1.0);
    float z = uvw.z * 32.0 - 0.5;
    float z0 = clamp(floor(z), 0.0, 31.0), z1 = min(z0 + 1.0, 31.0), f = clamp(z - z0, 0.0, 1.0);
    vec2 size = vec2(textureSize(tex, 0));
    vec2 inTile = 1.0 + uvw.xy * 32.0;
    vec2 a = (vec2(z0 * 34.0, slot * 34.0) + inTile) / size, b = (vec2(z1 * 34.0, slot * 34.0) + inTile) / size;
    return mix(textureLod(tex, a, 0.0), textureLod(tex, b, 0.0), f);
}

// the 128^3 noise, wrapping: 16 x 8 slices of 130x130 (128 + a 1-texel wrapped border)
vec4 smokeNoise(vec3 p) {
    vec3 w = fract(p);
    float z = w.z * 128.0 - 0.5;
    float z0 = mod(floor(z), 128.0), z1 = mod(z0 + 1.0, 128.0), f = z - floor(z);
    vec2 inTile = 1.0 + w.xy * 128.0;
    vec2 a = (vec2(mod(z0, 16.0), floor(z0 / 16.0)) * 130.0 + inTile) / vec2(2080.0, 1040.0);
    vec2 b = (vec2(mod(z1, 16.0), floor(z1 / 16.0)) * 130.0 + inTile) / vec2(2080.0, 1040.0);
    return mix(textureLod(NoiseSampler, a, 0.0), textureLod(NoiseSampler, b, 0.0), f);
}

// CS2's noise value: pow, remapped between g_flNoiseMin/Max (or the wispy curve), R + 0.95 G, x 4.6
float noiseValue(vec3 p) {
    vec4 n = pow(smokeNoise(p * 0.07), vec4(NOISE_POWER));
    vec4 m = mix(mix(vec4(NOISE_MIN), vec4(NOISE_MAX), n), mix(vec4(0.25), vec4(-1.5), n), WISPY);
    return (m.x + m.y * 0.95) * 4.6;
}

// the density pair CS2 keeps per voxel: away from the scene the larger, close to a surface the smaller
float densityAt(float slot, vec3 uvw, float frame, float toEnd) {
    vec4 t = volume(DensitySampler, slot, uvw);
    vec2 v = mix(t.xz, t.yw, frame);
    return v.x > v.y ? mix(v.y, v.x, smoothstep(10.0, 40.0, toEnd)) : v.y;
}

vec2 boxHit(vec3 ro, vec3 inv, vec3 lo, vec3 hi) {
    vec3 a = inv * (lo - ro), b = inv * (hi - ro);
    vec3 n = min(a, b), x = max(a, b);
    return vec2(max(max(n.x, n.y), n.z), min(min(x.x, x.y), x.z));
}

vec3 saturate3(vec3 c) {
    // the light volume's colour, saturation x 1.1 (RGB -> HSV -> RGB as CS2 does)
    float mx = max(c.r, max(c.g, c.b)), d = mx - min(c.r, min(c.g, c.b));
    if (d == 0.0) return vec3(mx);
    vec3 hsv;
    vec3 k = (vec3(mx) - c) / d;
    vec3 e = k.xyz - k.zxy;
    if (c.r >= mx) hsv = vec3(e.z, d / mx, mx);
    else if (c.g >= mx) hsv = vec3(e.x + 2.0, d / mx, mx);
    else hsv = vec3(e.y + 4.0, d / mx, mx);
    hsv.x = fract(hsv.x / 6.0);
    float s = clamp(hsv.y * 1.1, 0.0, 1.0);
    if (s == 0.0) return vec3(hsv.z);
    float h6 = hsv.x * 6.0, i = floor(h6), f = h6 - i;
    float p = hsv.z * (1.0 - s), q = hsv.z * (1.0 - s * f), t = hsv.z * (1.0 - s * (1.0 - f));
    if (i == 0.0) return vec3(hsv.z, t, p);
    if (i == 1.0) return vec3(q, hsv.z, p);
    if (i == 2.0) return vec3(p, hsv.z, t);
    if (i == 3.0) return vec3(p, q, hsv.z);
    if (i == 4.0) return vec3(t, p, hsv.z);
    return vec3(hsv.z, p, q);
}

vec3 viewToWorld(vec3 v) {
    return v.x * ViewRight.xyz + v.y * ViewUp.xyz - v.z * ViewForward.xyz;
}

vec3 worldToView(vec3 v) {
    return vec3(dot(v, ViewRight.xyz), dot(v, ViewUp.xyz), -dot(v, ViewForward.xyz));
}

// ---------------------------------------------------------------- the march

vec4 accum = vec4(0.0);
float sunAccum = 0.0, aoAccum = 0.0;
// bullet tracers pushing the smoke aside: glow, push direction, push amount (refreshed every 16 steps)
float tracerGlow = 0.0, tracerPush = 0.0;
vec3 tracerDir = vec3(0.0, 0.0, 0.01);

void tracers(vec3 p) {
    tracerGlow = 0.0;
    tracerPush = 0.0;
    tracerDir = vec3(0.0, 0.0, 0.01);
    int n = int(SunColor.w);
    for (int k = 0; k < MAX_TRACERS; k++) {
        if (k >= n) break;
        vec3 s = TracerStart[k].xyz, e = TracerEnd[k].xyz, seg = e - s, rel = p - s;
        float line = clamp(length(rel - seg * clamp(dot(rel, seg) / dot(seg, seg), 0.0, 1.0)) * 0.05 * TracerSize[k].x, 0.0, 1.0);
        float life = smoothstep(0.0, 0.01, TracerStart[k].w) * (1.0 - smoothstep(0.01, 0.2, TracerStart[k].w));
        float glow = 0.0;
        if (line < 1.0) {
            float push = max(tracerPush, smoothstep(0.0, 1.0, 1.0 - clamp(TracerStart[k].w
                    + clamp(line + (1.0 - clamp(length(p - e) * 0.01, 0.0, 1.0)), 0.0, 1.0), 0.0, 1.0)));
            tracerDir = mix(tracerDir, normalize(s - e), push);
            tracerPush = push;
            glow = pow(1.0 - line, 64.0) * life * 10.0;
        }
        if (TracerEnd[k].w > 0.0) {
            float g = (1.0 - clamp(length(rel) * 0.01, 0.0, 1.0)) * life;
            tracerGlow = max(tracerGlow, max(g * g, glow));
        }
    }
}

void sampleSmoke(int i, vec3 p, vec3 dir, vec3 rayEnd, vec3 sceneHit, float weight) {
    vec3 center = SmokeCenter[i].xyz;
    float slot = SmokeState[i].z, frame = SmokeState[i].y;
    vec3 q = p + normalize(tracerDir) * pow(tracerPush, 3.0) * 20.0;
    vec3 uvw = clamp(((q - center) * 0.05 + 16.0) / 32.0, 0.0, 1.0);
    float toEnd = distance(q, rayEnd);
    float d = clamp(mix(densityAt(slot, uvw, frame, toEnd), -0.05, tracerPush), 0.0, 1.0);
    if (d <= 0.01) return;

    // the volume's own normal (view space), from the density next to this point
    vec3 volN = vec3(0.0, 0.0, 1.0);
    if (VOLUME_NORMAL_BLEND > 0.0) {
        float up = densityAt(slot, clamp(uvw - ViewUp.xyz * 0.03, 0.0, 1.0), frame, toEnd);
        float right = densityAt(slot, clamp(uvw + ViewRight.xyz * 0.03, 0.0, 1.0), frame, toEnd);
        volN = normalize(vec3(d - right, up - d, 0.06));
    }
    float nearScene = max(0.0, toEnd - min(20.0, abs(sceneHit.z - center.z) * 2.0));
    float dens = clamp((d - 0.01) * 1.0101, 0.0, 1.0) * SmokeFade[i].x;
    dens = clamp(dens + (1.0 - clamp(length(q) * 0.1, 0.0, 1.0)) * dens, 0.0, 1.0); // thicker right at the eye

    // HE blasts: the smoke is pushed out and thinned, then flows back in
    float keep = 1.0, hole = 0.0;
    vec3 lp = q;
    int blasts = int(Timing.w), bit = int(SmokeState[i].w);
    for (int k = 0; k < MAX_BLASTS; k++) {
        if (k >= blasts) break;
        int mask = int(BlastMask[k / 4][k % 4]);
        if ((mask & (1 << bit)) == 0) continue;
        float age = Timing.x - Blast[k].w;
        if (age >= SmokeState[i].x - 0.4) continue;
        float dist = distance(lp, Blast[k].xyz);
        if (dist >= 250.0) continue;
        float fresh = pow(1.0 - smoothstep(0.0, 2.0, age), 128.0);
        float edge = clamp((48.0 - nearScene) / 48.0, 0.0, 1.0) * (1.0 - smoothstep(0.0, 7.0, age));
        lp = mix(lp, Blast[k].xyz, (1.0 - smoothstep(100.0, 250.0, dist)) * step(age * 1250.0, dist) * (1.0 - fresh));
        keep = min(keep, max(smoothstep(200.0, 240.0, dist + fresh * 250.0) + pow(smoothstep(0.5, 5.0, age), 1.8), edge));
        hole = edge;
    }
    if (blasts > 0) dens = mix(dens * 0.02, dens, keep);

    // the noise: slowly turning and drifting low-frequency noise, high-frequency noise boiled along its normal
    float time = Timing.x * NOISE_SPEED;
    vec3 c = uvw - 0.5, cq = c * 7.0;
    float ang = time * 0.04 + (0.2 + (sin(cq.z * 5.0) + 0.5) * 0.15) * sin(time * 0.5 + 0.5) * sin(time * 0.187 + 0.5) * 0.2;
    float cs = cos(ang), sn = sin(ang);
    float rx = cq.x * cs - cq.y * sn, ry = cq.x * sn + cq.y * cs;
    float t2 = time + sin(time * 0.5) * 0.02;
    vec2 xz = vec2(rx, cq.z) + vec2(sin(t2 + cq.z * 2.7), cos(t2 + rx * 2.7)) * 0.05;
    vec3 r3 = vec3(xz.x, ry, xz.y);
    r3.z += (sin(xz.x * 3.0 + time * 0.35) + sin(ry * 2.84 + time * 0.235)) * 0.05;
    vec3 low = r3 * NOISE_SCALE_LOW, scroll = vec3(2.0, 2.0, 4.5) * (time * 0.1);
    vec3 upStep = ViewUp.xyz * 0.2, rightStep = ViewRight.xyz * 0.2;
    float n0 = noiseValue(abs(low) - scroll);
    float n1 = noiseValue(abs(low + upStep) - scroll);
    float n2 = noiseValue(abs(low + rightStep) - scroll);
    float flat0 = 0.8 / NOISE_SCALE_LOW;
    vec3 nLow = normalize(vec3(n0 - n2, n0 - n1, flat0));
    vec3 hp = (cq + viewToWorld(nLow + volN) * pow(max(n0, 0.0), 0.1) * BOIL * 0.2) + vec3(2.0, 2.0, 4.5) * ((n0 - 1.0) * 0.2 * BOIL * c.z);
    hp.x += sin(r3.z + time * 0.25) * 0.05;
    vec3 high = hp * NOISE_SCALE_HIGH;
    float h0 = noiseValue(high), h1 = noiseValue(high + upStep), h2 = noiseValue(high + rightStep);
    float highStrength = NOISE_STRENGTH_HIGH * (dot(dir, ViewForward.xyz) * clamp(length(lp) * 0.005, 0.0, 1.0));
    float noise = mix(0.95, clamp(n0, 0.0, 1.0), NOISE_STRENGTH_LOW) + mix(0.95, clamp(h0, 0.0, 1.0), highStrength) + NOISE_OFFSET;

    // dissipating: the smoke shrinks towards its middle
    float body = dens;
    float shrink = SmokeFade[i].w;
    if (shrink < 1.0) {
        float s = clamp(shrink, 1e-4, 0.9999), a = smoothstep(0.0, 0.8, s), b = smoothstep(0.2, 1.0, s);
        if (a == b) body = dens * s;
        else body = dens * clamp(smoothstep(a, b, clamp(length(vec3(c.xy, c.z * 1.2)), 0.0, 1.0)), 0.0, 1.0);
    }
    float alpha = mix(body - (1.0 - noise), body + noise, body) * clamp(SmokeFade[i].x * SmokeFade[i].w * 8.0, 0.0, 1.0);
    if (alpha < 1e-4) return;

    // lighting
    vec3 radial = normalize(c) * (1.0 - clamp(hole, 0.0, 1.0));
    vec3 nHigh = normalize(vec3(h0 - h2, h0 - h1, flat0));
    vec3 n = viewToWorld(mix(worldToView(radial), volN, VOLUME_NORMAL_BLEND)
            + vec3(nLow.xy * NOISE_STRENGTH_LOW + nHigh.xy * mix(0.5, 1.0, clamp(n0 - 0.5, 0.0, 1.0)) * highStrength, 0.0)
            * NOISE_NORMAL * mix(1.0, 2.0, clamp((200.0 - distance(lp, center)) * 0.005, 0.0, 1.0)));
    vec3 lightPos = lp + n * ((h0 - 1.0) * 8.0);
    vec4 light = volume(LightSampler, slot, clamp(c + (n * 0.05 - radial * 0.025) + 0.5, 0.03, 0.97));
    vec4 lpv = vec4(textureLod(LightmapSampler, light.xy * (15.0 / 16.0) + 0.5 / 16.0, 0.0).rgb * LightScale.x, light.w);
    float ndl = dot(mix(radial, n, NOISE_NORMAL_DIRECT), SunDir.xyz);
    float sun = pow(clamp(ndl * 0.8 + 0.2, 0.0, 1.0), 1.5) + pow(clamp(ndl * 1.4 - 0.5, 0.0, 1.0), 3.0);
    if (sun > 0.0) sun *= lpv.w;
    vec3 amb = saturate3(lpv.rgb);
    vec3 col = normalize(amb + 0.001) * min(length(amb), 4.0) * clamp(1.0 - min(0.25, alpha), 0.0, 1.0)
            * clamp(1.0 - (body * 2.4 - alpha) * INTERIOR_DARKENING, 0.0, 1.0) * noise * (0.75 + sun * 0.25) * (1.0 + n.z * 0.5) * AMBIENT
            + SunColor.rgb * (0.5 * sun * (1.0 - tracerPush)) * DIRECT;
    vec3 tinted = col * SmokeTint[i].rgb;
    const vec3 LUM = vec3(0.2125, 0.7154, 0.0721);
    col = mix(col, tinted * (dot(col, LUM) / dot(tinted + 0.001, LUM)), vec3(0.5 * (1.0 - SmokeFade[i].z)))
            * mix(vec3(1.0), normalize(SMOKE_COLOR + 0.01) * 1.732, clamp(0.5 + alpha * 6.0, 0.0, 1.0));
    col = mix(col, col * keep, 0.6);
    float ao = smoothstep(0.0, 0.2, alpha + 0.3) * clamp((AO_DISTANCE - nearScene) / AO_DISTANCE, 0.0, 1.0) * body * 6.0 * AO_STRENGTH;
    float a = smoothstep(0.0, 0.2 / (DENSITY * mix(0.5, 2.0, accum.w)), alpha);
    float flash = (1.0 - smoothstep(0.0, 0.3, Timing.x - Flash.w)) * (1.0 - smoothstep(0.0, 300.0, distance(Flash.xyz, lightPos)));
    vec4 s = vec4(((col + col * vec3(8.0, 4.0, 0.0) * tracerGlow) + vec3(flash)) * mix(1.0, 0.85, clamp(hole * 20.0, 0.0, 1.0)) * a, a);

    // composited weight = step length / 4 times (whole times, then the fraction)
    float w = weight;
    for (int k = 0; k < 8; k++) {
        if (w < 1.0) break;
        accum += s * (1.0 - accum.w);
        w -= 1.0;
    }
    accum += s * ((1.0 - accum.w) * w);
    sunAccum += sun * weight;
    aoAccum += ao * weight;
}

void main() {
    vec4 clip = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 far = ClipToWorld * clip;
    vec3 dir = normalize(far.xyz / far.w);
    vec3 inv = 1.0 / dir;
    int smokes = int(Timing.z);

    vec2 span = vec2(1e9, -1e9);
    vec2 hits[MAX_SMOKES];
    for (int i = 0; i < MAX_SMOKES; i++) {
        if (i >= smokes) break;
        hits[i] = boxHit(vec3(0.0), inv, SmokeMin[i].xyz, SmokeMax[i].xyz);
        if (hits[i].x <= hits[i].y) span = vec2(min(span.x, hits[i].x), max(span.y, hits[i].y));
    }
    if (span.x > span.y || span.y < 0.0) discard;

    float depth = textureLod(DepthSampler, texCoord, 0.0).r;
    vec4 scene = ClipToWorld * vec4(texCoord * 2.0 - 1.0, LightScale.y > 0.5 ? depth : depth * 2.0 - 1.0, 1.0);
    vec3 sceneHit = scene.xyz / scene.w;
    float sceneDist = dot(sceneHit, dir);

    float blue = texelFetch(BlueNoiseSampler, ivec2(gl_FragCoord.xy) & 255, 0).x;
    float t = max(span.x, 4.0) + RAY_STEP * fract(blue + Timing.y * GOLDEN) * JITTER * mix(0.1, 0.8, clamp((span.x + 150.0) * 0.05, 0.0, 1.0));
    float limit = sceneDist - 2.0;
    if (t > limit) discard;
    vec3 rayEnd = dir * min(span.y, limit);
    float len = distance(rayEnd, dir * t), stop = t + len;
    int steps = int(clamp(ceil(len / RAY_STEP) + 10.0, 1.0, 500.0));
    bool haveTracers = SunColor.w > 0.0;

    vec3 p = dir * t;
    float last = t;
    for (int k = 0; k < 500; k++) {
        if (k >= steps) break;
        if (haveTracers && ((k & 15) == 0 || k < 16)) tracers(p);
        for (int i = 0; i < MAX_SMOKES; i++) {
            if (i >= smokes) break;
            if (t < hits[i].x || t > hits[i].y) continue;
            sampleSmoke(i, p, dir, rayEnd, sceneHit, RAY_STEP * 0.25);
        }
        if (accum.w > 0.991) {
            accum.w = 1.0;
            break;
        }
        last = t;
        p += dir * RAY_STEP;
        t += RAY_STEP;
        if (t >= stop) break;
    }
    // g_bExtraSample: one more sample at the ray's end for the part of a step left over
    if (accum.w < 0.991) {
        float tl = last;
        for (int i = 0; i < MAX_SMOKES; i++) {
            if (i >= smokes) break;
            if (tl < hits[i].x || tl > hits[i].y) continue;
            sampleSmoke(i, rayEnd, dir, rayEnd, sceneHit, (stop - tl) * 0.25);
        }
    }

    float rim = pow(clamp(dot(dir, SunDir.xyz), 0.0, 1.0), 4.0) * 0.25;
    float ao = clamp(aoAccum - accum.w * 0.2, 0.0, 1.0);
    vec4 r = vec4(accum.rgb * (1.0 - ao), accum.w + ao);
    r.rgb *= vec3(1.0) + SunColor.rgb * SunColor.rgb * ((rim + pow(rim, 50.0) * 8.0) * (1.0 - sqrt(accum.w)) * accum.w) * (sunAccum * RIM);
    if (r.w < 1e-5) discard;
    fragColor = vec4(r.rgb, clamp(r.w, 1e-5, 0.9999));
}
