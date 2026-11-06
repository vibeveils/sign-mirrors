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
};

// The world rendered from the viewer's eye reflected through the mirror plane.
uniform sampler2D Reflection;

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

void main() {
    float reflectivity = Params.y;
    vec3 seen = reflectivity > 0.0 ? reflection(snapToPixelGrid(relPos)) : vec3(1.0);
    // Reflectivity blends from the plain tint colour (0) to the tinted reflection (1).
    vec3 color = mix(vec3(1.0), seen, reflectivity) * Tint.rgb;
    fragColor = vec4(color, Tint.a);
}
