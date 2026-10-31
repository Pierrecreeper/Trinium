#version 330 core
// Trinium Clear, Trinium proprietary license. Standalone composite/final format.
layout(location = 0) in vec4 position;
layout(location = 1) in vec4 texCoord;
out vec2 uv;
void main() {
    gl_Position = position;
    uv = texCoord.xy;
}
