#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;

// Camera-relative world position (the camera sits at the origin, axes are world axes).
out vec3 relPos;

void main() {
    relPos = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
