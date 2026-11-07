#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform MirrorInfo {
    mat4 ProjMat;
    mat4 ModelViewMat;
    mat4 PlanarViewProj;
    vec4 PlanarOrigin;
    vec4 Corner;
    vec4 AxisU;
    vec4 AxisV;
    vec4 Tint;
    vec4 Params;
    mat4 InvProjMat;     // inverse of the projection with ordinary depth (near 0)
    mat4 InvProjAlt;     // inverse of the game's reverse-Z projection (near 1)
    vec4 DepthTest;      // x: test against SceneDepth (0/1), y: depth is [0,1] (0/1), zw: 1/screen size
};

// The world rendered from the viewer's eye reflected through the mirror plane.
uniform sampler2D Reflection;
// The scene's depth buffer (only read when DepthTest.x is set: with a shader pack, where mirrors are
// drawn over the finished image and the hardware depth test can't be trusted).
uniform sampler2D SceneDepth;

layout(location = 0) in vec3 relPos;
layout(location = 0) out vec4 fragColor;

// Pixelated mirrors: move the point to the centre of its cell on the mirror's pixel grid, so each
// cell shows one flat colour -- the reflection seen through the cell's centre.
vec3 snapToPixelGrid(vec3 p) {
    float ppb = Params.w;
    if (ppb <= 0.0) return p;
    vec3 d = p - Corner.xyz;
    vec2 uv = vec2(dot(d, AxisU.xyz), dot(d, AxisV.xyz));
    vec2 size = vec2(AxisU.w, AxisV.w);
    vec2 cell = min(floor(uv * ppb), max(ceil(size * ppb) - 1.0, 0.0));
    // The last cell may be partial; keep its sample on the glass.
    vec2 snapped = min((cell + 0.5) / ppb, size);
    return Corner.xyz + AxisU.xyz * snapped.x + AxisV.xyz * snapped.y;
}

// This point's position projected into the reflected-eye render. That render looked through exactly
// this point from the mirrored eye, so this is the exact reflection.
vec3 reflection(vec3 p) {
    vec4 clip = PlanarViewProj * vec4(p - PlanarOrigin.xyz, 1.0);
    vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
    float halfTexel = 0.5 * Params.x;
    uv = clamp(uv, vec2(halfTexel), vec2(1.0 - halfTexel));
    if (Params.z > 0.5) uv.y = 1.0 - uv.y;
    return texture(Reflection, uv).rgb;
}

// View-space depth (negative, in front of the camera) of window depth d read with one projection.
float viewDepth(mat4 inverseProjection, vec2 uv, float d) {
    float ndcZ = DepthTest.y > 0.5 ? d : d * 2.0 - 1.0;
    vec4 v = inverseProjection * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    if (abs(v.w) < 1e-7) return -1e9;                    // infinitely far
    float z = v.z / v.w;
    return z > 0.0 ? -1e9 : z;                           // behind the camera: not a real reading
}

// Whether something in the scene is in front of this fragment (compared in view space).
// The depth buffer may hold ordinary depth or reverse-Z. Read the wrong way, a depth value always
// lands almost on the near plane (closer than it really is), so the further of the two readings
// is the right one -- whichever convention the buffer uses.
bool hiddenByScene(vec3 p) {
    vec2 uv = gl_FragCoord.xy * DepthTest.zw;
    float d = texture(SceneDepth, uv).r;
    float sceneZ = min(viewDepth(InvProjMat, uv, d), viewDepth(InvProjAlt, uv, d)); // further = more negative
    float fragZ = (ModelViewMat * vec4(p, 1.0)).z;
    float bias = 0.02 + 0.002 * abs(fragZ);
    return fragZ < sceneZ - bias;                        // fragment is further away than the scene
}

void main() {
    if (DepthTest.x > 0.5 && hiddenByScene(relPos)) discard;
    float reflectivity = Params.y;
    vec3 seen = reflectivity > 0.0 ? reflection(snapToPixelGrid(relPos)) : vec3(1.0);
    // Reflectivity blends from the plain tint colour (0) to the tinted reflection (1).
    vec3 color = mix(vec3(1.0), seen, reflectivity) * Tint.rgb;
    fragColor = vec4(color, Tint.a);
}
