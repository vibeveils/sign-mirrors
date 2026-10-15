#version 330

uniform sampler2D Sampler0; // 3x2 cube-face atlas, layout in CubeFace.java

layout(std140) uniform MirrorInfo {
    vec4 ProbePos;     // camera-relative probe position
    vec4 BoxMin;       // camera-relative proxy box
    vec4 BoxMax;
    vec4 MirrorNormal; // world-space unit normal of the reflective face
    vec4 Params;       // x: 1/faceResolution, y: reflectivity, z: flip atlas V (0/1)
};

in vec3 relPos;
out vec4 fragColor;

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

vec3 safeDir(vec3 r) {
    const float EPS = 1e-5;
    return vec3(abs(r.x) < EPS ? EPS : r.x,
                abs(r.y) < EPS ? EPS : r.y,
                abs(r.z) < EPS ? EPS : r.z);
}

void main() {
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
    vec3 hit = relPos + r * t;
    vec3 dir = hit - ProbePos.xyz;

    // Only the front hemisphere of the probe is captured; fold anything behind it onto the plane.
    float dn = dot(dir, n);
    if (dn < 0.0) dir -= n * dn;
    if (dot(dir, dir) < 1e-8) dir = r;

    int f = faceOf(dir);
    vec2 ndc = vec2(dot(dir, FACE_R[f]), dot(dir, FACE_U[f])) / dot(dir, FACE_F[f]);
    float halfTexel = 0.5 * Params.x;
    vec2 uv = clamp(ndc * 0.5 + 0.5, vec2(halfTexel), vec2(1.0 - halfTexel));
    if (Params.z > 0.5) uv.y = 1.0 - uv.y;
    vec2 atlasUv = (vec2(float(f % 3), float(f / 3)) + uv) / vec2(3.0, 2.0);

    vec3 color = texture(Sampler0, atlasUv).rgb * Params.y;
    fragColor = vec4(color, 1.0);
}
