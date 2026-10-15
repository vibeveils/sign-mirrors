#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform MirrorInfo {
    mat4 ProjMat;
    mat4 ModelViewMat;
    vec4 ProbePos;
    vec4 BoxMin;
    vec4 BoxMax;
    vec4 MirrorNormal;
    vec4 Params;         // x: 1/faceResolution, y: reflectivity, z: flip V (0/1), w: 1 = planar
    mat4 PlanarViewProj; // planar: projection x view of the reflected-eye render
    vec4 PlanarOrigin;   // planar: that render's camera, relative to this view's camera
};

// One texture per cube face, each rendered by a 90-degree capture camera (see CubeFace.java).
uniform sampler2D FacePosX;
uniform sampler2D FaceNegX;
uniform sampler2D FacePosY;
uniform sampler2D FaceNegY;
uniform sampler2D FacePosZ;
uniform sampler2D FaceNegZ;

layout(location = 0) in vec3 relPos;
layout(location = 0) out vec4 fragColor;

// Must match CubeFace: forward, screen-right and screen-up of the capture camera per face.
const vec3 FACE_F[6] = vec3[6](vec3( 1, 0, 0), vec3(-1, 0, 0), vec3(0, 1, 0), vec3(0, -1, 0), vec3(0, 0, 1), vec3(0, 0, -1));
const vec3 FACE_R[6] = vec3[6](vec3( 0, 0, 1), vec3( 0, 0, -1), vec3(-1, 0, 0), vec3(-1, 0, 0), vec3(-1, 0, 0), vec3(1, 0, 0));
const vec3 FACE_U[6] = vec3[6](vec3( 0, 1, 0), vec3( 0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(0, 1, 0), vec3(0, 1, 0));

int faceOf(vec3 d) {
    vec3 a = abs(d);
    if (a.x >= a.y && a.x >= a.z) return d.x > 0.0 ? 0 : 1;
    if (a.y >= a.z) return d.y > 0.0 ? 2 : 3;
    return d.z > 0.0 ? 4 : 5;
}

vec3 sampleFace(int f, vec2 uv) {
    if (f == 0) return texture(FacePosX, uv).rgb;
    if (f == 1) return texture(FaceNegX, uv).rgb;
    if (f == 2) return texture(FacePosY, uv).rgb;
    if (f == 3) return texture(FaceNegY, uv).rgb;
    if (f == 4) return texture(FacePosZ, uv).rgb;
    return texture(FaceNegZ, uv).rgb;
}

vec3 safeDir(vec3 r) {
    const float EPS = 1e-5;
    return vec3(abs(r.x) < EPS ? EPS : r.x,
                abs(r.y) < EPS ? EPS : r.y,
                abs(r.z) < EPS ? EPS : r.z);
}

// Planar mirrors: this pixel's world position projected into the render made from the eye reflected
// through the mirror. That render looked through exactly this pixel, so this is the exact reflection.
vec3 planarReflection() {
    vec4 clip = PlanarViewProj * vec4(relPos - PlanarOrigin.xyz, 1.0);
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    float halfTexel = 0.5 * Params.x;
    uv = clamp(uv, vec2(halfTexel), vec2(1.0 - halfTexel));
    if (Params.z > 0.5) uv.y = 1.0 - uv.y;
    return texture(FacePosX, uv).rgb;
}

void main() {
    if (Params.w > 0.5) {
        fragColor = vec4(planarReflection() * Params.y, 1.0);
        return;
    }
    vec3 n = normalize(MirrorNormal.xyz);
    vec3 view = normalize(relPos);
    vec3 r = reflect(view, n);

    // Parallax correction: find where the reflected ray leaves the proxy box (the "room"),
    // then look that point up from the probe instead of using the raw reflection direction.
    vec3 rs = safeDir(r);
    vec3 t1 = (BoxMax.xyz - relPos) / rs;
    vec3 t2 = (BoxMin.xyz - relPos) / rs;
    vec3 tFar = max(t1, t2);
    float t = max(min(min(tFar.x, tFar.y), tFar.z), 0.0);
    vec3 dir = relPos + r * t - ProbePos.xyz;

    // Only the probe's front hemisphere is captured; fold anything behind it onto the plane.
    float dn = dot(dir, n);
    if (dn < 0.0) dir -= n * dn;
    if (dot(dir, dir) < 1e-8) dir = r;

    int f = faceOf(dir);
    vec2 ndc = vec2(dot(dir, FACE_R[f]), dot(dir, FACE_U[f])) / dot(dir, FACE_F[f]);
    float halfTexel = 0.5 * Params.x;
    vec2 uv = clamp(ndc * 0.5 + 0.5, vec2(halfTexel), vec2(1.0 - halfTexel));
    if (Params.z > 0.5) uv.y = 1.0 - uv.y;

    fragColor = vec4(sampleFace(f, uv) * Params.y, 1.0);
}
