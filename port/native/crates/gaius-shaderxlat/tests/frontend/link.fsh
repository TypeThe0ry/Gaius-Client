#version 330

// gaius-shaderxlat front-end fixture (tests/frontend.rs): a 26.2-style fragment stage.

layout(std140) uniform Globals {
    vec4 Scale;
};

layout(std140) uniform Fog {
    vec4 FogColor;
    float FogEnd;
};

uniform sampler2D Sampler0;
uniform samplerCube Sky;

// A subset of the vertex outputs, in another order.
in vec2 texCoord;
in vec4 vertexColor;

out vec4 fragColor;
out vec4 skyColor;

void main() {
    vec4 color = texture(Sampler0, texCoord) * vertexColor;
    fragColor = mix(color, FogColor, Scale.w);
    skyColor = texture(Sky, vec3(texCoord, 1.0));
}
