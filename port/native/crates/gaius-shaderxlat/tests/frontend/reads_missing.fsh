#version 330

// gaius-shaderxlat front-end fixture (tests/frontend.rs): reads an input no vertex output provides.

in vec2 overlay;
in vec4 missing;

out vec4 fragColor;

void main() {
    fragColor = missing * overlay.x;
}
