#version 330

// CS2's death camera colour (death_cam_phase1 / _phase2): the frame multiplied by this colour (red kept, green and
// blue toward 0.58 for the red tint, everything toward 0 for the fade to black).
layout(std140) uniform FadeInfo {
    vec4 Multiply;
};

out vec4 fragColor;

void main() {
    fragColor = Multiply; // alpha: 1 for the multiply, the overlay's own for the over pipeline
}
