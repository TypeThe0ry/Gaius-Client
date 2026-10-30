#version 330

// gaius-shaderxlat front-end fixture (tests/frontend.rs): 1.21.11-style GL habits, vertex stage.

layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
// An integer attribute (RG16_SINT) declared as a float vector.
in vec2 UV1;

out vec2 overlay;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    overlay = UV1 / 16.0 + vec2(UV1.y, 0.0);
}
