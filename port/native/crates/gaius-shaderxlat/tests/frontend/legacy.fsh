#version 330

// gaius-shaderxlat front-end fixture (tests/frontend.rs): 1.21.11-style GL habits, fragment stage.

// Neither is declared by the pipeline.
layout(std140) uniform Globals {
    vec4 Tint;
};
uniform sampler2D Sampler1;

in vec2 overlay;
// No vertex output provides it and nothing reads it.
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    fragColor = texture(Sampler1, overlay) * Tint;
}
