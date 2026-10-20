#version 330

// CS2's bloom upsample (bloom_cs): a 3x3 tent over the smaller level, blended into the larger one (alpha = the mix).
uniform sampler2D InSampler;

in vec2 texCoord;
out vec4 fragColor;

const float RADIUS = 1.0, MIX = 0.6;

void main() {
    vec2 px = RADIUS / vec2(textureSize(InSampler, 0));
    vec3 c = texture(InSampler, texCoord).rgb * 0.25
        + (texture(InSampler, texCoord + vec2(0.0, px.y)).rgb + texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb
         + texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb + texture(InSampler, texCoord - vec2(0.0, px.y)).rgb) * 0.125
        + (texture(InSampler, texCoord + vec2(-px.x, px.y)).rgb + texture(InSampler, texCoord + px).rgb
         + texture(InSampler, texCoord - px).rgb + texture(InSampler, texCoord + vec2(px.x, -px.y)).rgb) * 0.0625;
    fragColor = vec4(c, MIX);
}
