#version 460
#extension GL_ARB_separate_shader_objects : require

// Written for tests/base_vertex.rs (not a vanilla shader): the pattern resource packs use to
// recover the raw vertex number of a quad, gl_VertexIndex - gl_BaseVertex.
layout(std140, binding = 0) uniform Transform {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    int vertexIndex = gl_VertexIndex - gl_BaseVertex;
    int vertexId = vertexIndex % 4;
    if (vertexId == 1) {
        texCoord0 += vec2(1.0, 0.0);
    }
}
