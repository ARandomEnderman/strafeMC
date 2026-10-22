#version 330

// CS2's flashbang afterimage: the frame from the moment of the flash, laid over the scene at its fading strength.
uniform sampler2D InSampler;

layout(std140) uniform FlashInfo {
    vec4 Strength;
};

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(InSampler, texCoord).rgb, Strength.x);
}
