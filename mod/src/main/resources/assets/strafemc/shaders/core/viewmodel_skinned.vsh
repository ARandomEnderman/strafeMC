#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// strafeMC viewmodel skinned on the GPU: the mesh sits in a static buffer, each frame only the joint matrices change.
// Same outputs as viewmodel.vsh, so viewmodel.fsh lights it the same way.
in vec3 Position;
in vec2 UV0;
in vec3 SkinNormal;
in vec4 SkinJoints;
in vec4 SkinWeights;

uniform sampler2D Sampler2;

// Joint: per joint, (inverse model-view x camera) x view placement x skin; Params: lightmap uv, pearlescence alpha, U shift
layout(std140) uniform Skin {
    mat4 Joint[MAX_JOINTS];
    vec4 Params;
};

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 viewPos;
out vec3 viewNormal;

void main() {
    mat4 m = Joint[int(SkinJoints.x)] * SkinWeights.x + Joint[int(SkinJoints.y)] * SkinWeights.y
            + Joint[int(SkinJoints.z)] * SkinWeights.z + Joint[int(SkinJoints.w)] * SkinWeights.w;
    vec4 pos = m * vec4(Position, 1.0);
    vec4 vp = ModelViewMat * pos;
    gl_Position = ProjMat * vp;
    viewPos = vp.xyz;
    viewNormal = mat3(ModelViewMat) * normalize(mat3(m) * SkinNormal);
    sphericalVertexDistance = fog_spherical_distance(pos.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(pos.xyz);
    vertexColor = vec4(1.0, 1.0, 1.0, Params.z);
    lightMapColor = sample_lightmap(Sampler2, ivec2(Params.xy));
    overlayColor = vec4(1.0);
    texCoord0 = UV0 + vec2(Params.w, 0.0);
}
