#version 330

// The smoke, marched at a lower resolution (CS2 does the same), laid over the scene: premultiplied colour + coverage.
uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 s = texture(InSampler, texCoord);
    if (s.a <= 0.0) discard;
    fragColor = s;
}
