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

vec3 environment(vec3 r, float rough) {
    float up = smoothstep(-0.25, 0.65, r.y);
    vec3 ground = vec3(0.10, 0.09, 0.08), horizon = vec3(0.75, 0.78, 0.82), sky = vec3(1.05, 1.08, 1.15);
    vec3 env = mix(mix(ground, horizon, smoothstep(-0.25, 0.1, r.y)), sky, up);
    float spec = pow(max(dot(r, SUN), 0.0), mix(180.0, 18.0, rough));
    return env + vec3(spec) * mix(1.4, 0.3, rough);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    vec3 albedo = tex.rgb * vertexColor.rgb;
    float metal = tex.a;
    vec3 n = normalize(gl_FrontFacing ? viewNormal : -viewNormal);
    vec3 v = normalize(-viewPos);
    float ndv = max(dot(n, v), 0.0);
    float rough = mix(0.45, 0.25, metal);
    vec3 r = reflect(-v, n);

    vec3 f0 = mix(vec3(0.04), albedo, metal);
    // non-metals (paint, fabric, rubber) only get a faint, rough sheen at grazing angles; metal the full Fresnel
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - ndv, 5.0) * mix(0.12, 1.0, metal);
    float diffuse = 0.42 + 0.68 * max(dot(n, SUN), 0.0);
    vec3 color = albedo * (1.0 - metal) * diffuse + environment(r, rough) * fresnel * mix(0.6, 1.0, metal);

    color = min(color, vec3(1.2)); // no blown-out white
    color = mix(overlayColor.rgb, color, overlayColor.a);
    vec4 outColor = vec4(color, 1.0) * ColorModulator * lightMapColor;
    fragColor = apply_fog(outColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
