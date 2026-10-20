#version 330

// The effects' bloom added over the scene. The bloom buffer holds colours at a quarter (room for overbright up to 4).
uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

const float STRENGTH = 0.5;

void main() {
    vec3 c = texture(InSampler, texCoord).rgb * 4.0 * STRENGTH;
    fragColor = vec4(c, 1.0);
}
