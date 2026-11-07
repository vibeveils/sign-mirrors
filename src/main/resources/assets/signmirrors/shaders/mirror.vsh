#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform MirrorInfo {
    mat4 ProjMat;
    mat4 ModelViewMat;   // camera rotation only; positions are camera-relative
    mat4 PlanarViewProj; // projection x view of the reflected-eye render
    vec4 PlanarOrigin;   // that render's camera, relative to this view's camera
    vec4 Corner;         // the mirror's bottom-left corner, camera-relative
    vec4 AxisU;          // xyz: unit vector along the bottom edge, w: width in blocks
    vec4 AxisV;          // xyz: unit vector along the left edge, w: height in blocks
    vec4 Tint;           // rgb: tint, a: opacity
    vec4 Params;         // x: 1/resolution, y: reflectivity, z: flip V (0/1), w: pixels per block (0 = smooth)
    mat4 InvProjMat;     // inverse of ProjMat (shader-side depth test)
    vec4 DepthTest;      // x: test against SceneDepth (0/1), y: depth is [0,1] (0/1), zw: 1/screen size
};

layout(location = 0) in vec3 Position;

// Camera-relative world position (camera at the origin, axes are world axes).
layout(location = 0) out vec3 relPos;

void main() {
    relPos = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
