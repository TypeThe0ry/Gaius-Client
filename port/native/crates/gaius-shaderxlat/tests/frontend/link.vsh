#version 330

// gaius-shaderxlat front-end fixture (tests/frontend.rs): a 26.2-style vertex stage.

layout(std140) uniform Globals {
    vec4 Scale;
};

layout(std140) uniform Transform {
    mat4 ModelView;
    mat4 Projection;
};

uniform isamplerBuffer Offsets;

// Declared out of the vertex format's order: locations follow the format.
in vec2 UV0;
in vec3 Position;
in vec4 Color;

out vec4 vertexColor;
out vec2 texCoord;
out float fogDistance;

void main() {
    int offset = texelFetch(Offsets, gl_VertexID).r;
    vec3 pos = Position + vec3(float(offset), 0.0, 0.0);
    gl_Position = Projection * ModelView * vec4(pos * Scale.xyz, 1.0);
    vertexColor = Color;
    texCoord = UV0;
    fogDistance = length(pos);
}
