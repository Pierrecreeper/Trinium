#version 330 core
#define EXPOSURE 1.0 // [0.8 0.9 1.0 1.1 1.2] Overall brightness.
#define SATURATION 1.05 // [0.8 0.9 1.0 1.05 1.1 1.2] Color saturation.
#define CONTRAST 1.05 // [0.9 1.0 1.05 1.1] Contrast around middle gray.
uniform sampler2D colortex0;
in vec2 uv;
layout(location = 0) out vec4 color;
void main() {
    vec4 scene = texture(colortex0, uv);
    float light = dot(scene.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 graded = mix(vec3(light), scene.rgb, SATURATION);
    graded = (graded - 0.5) * CONTRAST + 0.5;
    color = vec4(clamp(graded * EXPOSURE, 0.0, 1.0), scene.a);
}
