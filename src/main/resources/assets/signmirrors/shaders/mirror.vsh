#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform MirrorInfo {
    mat4 ProjMat;
    mat4 ModelViewMat;  // camera rotation only; positions are camera-relative
    vec4 ProbePos;      // camera-relative probe position
    vec4 BoxMin;        // camera-relative proxy box
    vec4 BoxMax;
    vec4 MirrorNormal;  // world-space unit normal of the reflective face
    vec4 Params;        // x: 1/faceResolution, y: reflectivity, z: flip face V (0/1), w: 1 = planar
    mat4 PlanarViewProj; // planar: projection x view of the reflected-eye render
    vec4 PlanarOrigin;   // planar: that render's camera, relative to this view's camera
};

layout(location = 0) in vec3 Position;

// Camera-relative world position (camera at the origin, axes are world axes).
layout(location = 0) out vec3 relPos;

void main() {
    relPos = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
