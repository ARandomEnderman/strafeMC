#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// strafeMC viewmodel lighting (ours, tuned by eye): the texture's alpha is CS2's metalness. Diffuse from a key light
// plus ambient; a stand-in environment (bright sky above, dark ground below, a sun highlight) reflected with Fresnel;
// metal reflects in its own colour, paint gets a faint clear-coat sheen. Directions are in view space.
uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewPos;
in vec3 viewNormal;

out vec4 fragColor;

const vec3 SUN = normalize(vec3(0.35, 0.85, 0.4));

vec3 environment(vec3 r, float rough, float metal) {
    float up = smoothstep(-0.25, 0.65, r.y);
    // metal mirrors its surroundings in its own colour: a dimmer stand-in (CS2's map reflections are mostly dark), so
    // anodized finishes (Dopplers) read deep instead of pastel
    vec3 ground = vec3(0.10, 0.09, 0.08) * mix(1.0, 0.5, metal), horizon = vec3(0.75, 0.78, 0.82) * mix(1.0, 0.45, metal),
            sky = vec3(1.05, 1.08, 1.15) * mix(1.0, 0.55, metal);
    vec3 env = mix(mix(ground, horizon, smoothstep(-0.25, 0.1, r.y)), sky, up);
    // rough surfaces see a blurred, dimmer surrounding: towards its average
    env = mix(env, (ground + horizon + sky) * 0.28, smoothstep(0.3, 0.9, rough));
    // rough metal: its blurred reflection is far dimmer than a mirror's (CS2's dark smoke on Marble Fade / Dopplers)
    env *= mix(1.0, mix(1.0, 0.3, smoothstep(0.25, 0.75, rough)), metal);
    float spec = pow(max(dot(r, SUN), 0.0), mix(180.0, 18.0, rough));
    return env + vec3(spec) * mix(1.4, 0.3, rough);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec3 albedo = tex.rgb * vertexColor.rgb;
    // alpha: metal in the top bit, CS2's roughness in the low seven
    float alphaBits = tex.a * 255.0;
    float metal = step(127.5, alphaBits);
    float rough = clamp((alphaBits - metal * 128.0) / 127.0, 0.0, 1.0);
    vec3 n = normalize(gl_FrontFacing ? viewNormal : -viewNormal);
    vec3 v = normalize(-viewPos);
    float ndv = max(dot(n, v), 0.0);
    vec3 r = reflect(-v, n);

    // CS2's pearlescence (csgo_weapon): the finish's colour turned about the grey axis by scale x (1 - N.V) radians,
    // as much as it's saturated (grey metal stays grey); the scale comes in the vertex alpha (255: none)
    float pa = vertexColor.a * 255.0;
    if (pa < 254.5) {
        float angle = (pa - 128.0) / 100.0 * (1.0 - ndv);
        float c = cos(angle);
        vec3 k = vec3(0.57735);
        vec3 turned = albedo * c + cross(k, albedo) * sin(angle) + k * dot(k, albedo) * (1.0 - c);
        float hi = max(albedo.r, max(albedo.g, albedo.b));
        float sat = hi > 0.0 ? (hi - min(albedo.r, min(albedo.g, albedo.b))) / hi : 0.0;
        albedo = mix(vec3(dot(albedo, vec3(0.2125, 0.7154, 0.0721))), turned, pow(sat, 0.125));
    }
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    // non-metals (paint, fabric, rubber) only get a faint, rough sheen at grazing angles; metal the full Fresnel
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - ndv, 5.0) * mix(0.12, 1.0, metal) * (1.0 - 0.8 * max(0.0, rough - 0.25));
    float diffuse = 0.42 + 0.68 * max(dot(n, SUN), 0.0);
    vec3 color = albedo * (1.0 - metal) * diffuse + environment(r, rough, metal) * fresnel * mix(0.6, 1.0, metal);

    color = min(color, vec3(1.2)); // no blown-out white
    color = mix(overlayColor.rgb, color, overlayColor.a);
    vec4 outColor = vec4(color, 1.0) * ColorModulator * lightMapColor;
    fragColor = apply_fog(outColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
