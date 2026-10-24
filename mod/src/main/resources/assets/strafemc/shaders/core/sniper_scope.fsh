#version 330

// CS2's sniper scope (its legacy_sniper_scope shader, same steps and constants): the frame seen through a round
// lens (barrel warp, a magnified rim, colour fringes, vignette, black outside), with the reticle lines across the
// screen that widen into a blur and fade to 30% darkness as the gun gets less accurate. Same constants as CS2's.
uniform sampler2D InSampler;

layout(std140) uniform ScopeInfo {
    vec4 Size;  // viewport width, height (pixels), inaccuracy (LegacySniperScopeInaccuracy), line width (pixels)
    vec4 Extra; // bob x, y (LegacySniperScopeBob), spread gap (LegacySniperScopeSpreadGap, pixels), unused
};

in vec2 texCoord;
out vec4 fragColor;

float strafeSegmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 ab = b - a, ap = p - a;
    float t = dot(ap, ab);
    if (t <= 0.0) return length(ap);
    float l = dot(ab, ab);
    if (l <= t) return length(p - b);
    return length(p - (a + ab * (t / l)));
}

void main() {
    vec2 vp = Size.xy;
    vec2 bob = Extra.xy;
    vec2 frag = texCoord * vp;
    vec2 uv = texCoord;
    vec2 p = uv - 0.5;
    p.x *= vp.x / vp.y;
    p -= (bob / vp.y) * -1.2;
    float r = length(p);
    vec2 dir = r > 1e-6 ? p / r : vec2(0.0);
    float a = clamp(Size.z * 2.0, 0.0, 1.0);
    float radius = mix(0.425, 0.38, a);
    float lens = smoothstep(radius, radius - 0.03, r);
    vec2 warped = uv + dir * pow(r, 6.0);
    float rim = smoothstep(radius - 0.04, radius, r);
    warped = mix(warped, warped + dir * rim * rim * 0.05, vec2(rim));
    vec2 fringe = dir * sqrt(smoothstep(0.05, 1.0, r)) * 0.5;
    float red = texture(InSampler, warped - fringe * 0.015).r;
    float green = texture(InSampler, warped - fringe * 0.02).g;
    float blue = texture(InSampler, warped - fringe * 0.025).b;

    vec2 centre = vp * 0.5 + 0.25 + bob * a * -4.0;
    float across = strafeSegmentDistance(frag, centre - vec2(vp.x, 0.0), centre + vec2(vp.x, 0.0));
    float down = strafeSegmentDistance(frag, centre - vec2(0.0, vp.y), centre + vec2(0.0, vp.y));
    float halfWidth = max(0.5, Size.w * 0.5);
    float soft = halfWidth + max(0.0, vp.y * 0.1 * a) + 0.25;
    float lines = clamp(smoothstep(halfWidth, soft, across) * smoothstep(halfWidth, soft, down), 0.0, 1.0);
    if (Extra.z > 0.0) { // the gap in the middle while accurate
        vec2 gapAt = centre + 0.25;
        if (mod(Size.w, 2.0) < 0.5) gapAt -= 0.5;
        float in_gap = smoothstep(Extra.z + 0.5, Extra.z, distance(frag, gapAt));
        lines = mix(clamp(lines + in_gap * 0.5, 0.0, 1.0) + in_gap * 0.03, lines, smoothstep(0.0, 0.02, a));
    }

    vec3 seen = vec3(red, green, blue) * smoothstep(0.55, 0.2, r);
    vec3 col = mix(seen, seen * lines, vec3(clamp(mix(1.0, 0.3, pow(a, 0.3)), 0.0, 1.0)));
    fragColor = vec4(col * lens, 1.0);
}
