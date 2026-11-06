# Sign Mirrors (Fabric, Minecraft 26.3, client-side)

Turn any sign into a live, exact mirror — sized, placed, framed, pixelated, tinted and faded from
the sign editor. Works on any server: nothing is installed server-side, and other players without
the mod just see a sign.

## Making a mirror

Place a sign (or edit one) and use the **mirror controls in the sign editor**, in the style of
[Signed Paintings](https://github.com/Nettakrim/Signed-Paintings):

- On a plain sign, press **Make into mirror**.
- While the sign is a mirror the editor stops dimming the world, shrinks the sign preview and shows
  two panels; every change appears live on the real mirror behind the screen.

| Placement (left) | |
|---|---|
| Anchor grid | Which point of the mirror sits at the sign: corners, edge middles or centre. Rotation happens about it. |
| Width / Height | 1/16 to 16 blocks. *Ratio* keeps them in proportion; *Size 1×1* resets. |
| Offset right / up / out | Moves the mirror away from the sign, in blocks (sign's own axes). The slider covers ±2; type up to ±16. |
| Turn / Tilt / Roll | Yaw (face turns to the reader's right), pitch (face tilts up), roll (counter-clockwise). *Straighten* clears offset and angles. |

| Appearance (right) | |
|---|---|
| Frame | None, the sign's own wood, any plank type, stripped logs, stone, deepslate, blackstone, quartz, obsidian, iron, gold, copper, concrete. |
| Frame px | Border width, ½ to 8 texture pixels. |
| Pixels/block | Pixelates the reflection into sharp square pixels (0 = smooth). Each pixel shows the reflection through its centre, so it stays stable as you move. Pixelated mirrors also render their reflection at lower resolution. |
| Tint # | Colour multiplied into the reflection (`RRGGBB`). |
| Opacity | 0–100%. Below 100 the mirror is blended over what's behind it. |
| Reflective | 0 shows just the tint colour (no reflection is rendered at all, so it's free); 100 a full reflection. |
| Sign: shown / hidden | Hide the sign board and text, leaving only the mirror and frame. |
| Remove mirror | Clears the text: a plain sign again. |

Settings are stored in the sign's own text, so they travel with the sign, work on any server and
need nothing server-side. Players without the mod just see a sign. Line 1 is `[Mirror]`; lines 2–4
hold a compact code starting with `~` (base64url of the non-default settings — the editor writes
it, you never type it). If the settings don't fit (hanging signs have short lines) the editor says so.

### Signs in the original format still work

`[Mirror]` / `2,1r` / `15,10` signs keep working exactly as before, and are read with the same
size, alignment and angles:

| Line | Meaning | Examples |
|---|---|---|
| 1 | `[Mirror]` (case-insensitive) | `[Mirror]` |
| 2 | `W,H` in blocks, plus optional alignment letters `l`/`c`/`r` and `b`/`m`/`t` | `2,1r` · `3x2 lt` · `1.5,2` |
| 3 | Angle(s) in degrees: `yaw[,pitch]`, or tagged | `15` · `10,-5` · `p15` · `y30 p10` |

Opening one in the editor shows *Old-format sign*; the first change you make rewrites it in the new
format (with the frame, offset, tint and other new options available). Signs you don't touch are
never rewritten.

Wall signs put the mirror flush against the wall (just proud of the sign board, which it covers);
standing and ceiling-hanging signs put it through the middle of the block, facing the same way as
the text. Wall-hanging signs are not supported.

## How it works

1. **Tracking** — `MirrorTracker` follows every loaded sign block entity, including signs already in
   the world when you join (each chunk's block entities are scanned as it loads). It reads the front
   text each tick, so edits (including live edits from the editor) apply immediately; an edited mirror
   keeps its GPU texture.
2. **Visibility and level of detail** (`render/MirrorVisibility`, once per frame):
   - *Frustum culling* — mirrors outside the view are skipped.
   - *Occlusion culling* — rays from the camera to nine points on the mirror; it's hidden only if
     every ray is blocked by a solid block or by another mirror. Checked every few frames, with a
     grace period so mirrors don't pop in and out.
   - *Resolution* — the on-screen size of the visible part of the mirror sets the reflection's
     resolution (between the min and max, times *Resolution scale*), so close-ups stay sharp.
     Changes wait a few frames, and the new texture is rendered before it replaces the old one.
   - *Priority* — bigger, closer and more central mirrors are refreshed more often.
3. **Secondary camera** — a flat mirror seen from your eye shows the world as seen from your eye
   reflected through the mirror plane. For each mirror the budget allows (*Reflection renders per
   frame*, most important first, urgent whenever your eye or view has moved) the mod renders the
   world once from that reflected eye: a vanilla `extract` + `renderLevel` pass from an invisible
   probe entity, framing only the on-screen part of the mirror plus a margin, with an oblique near
   plane on the glass so the wall and room behind it are clipped away. The hand, view bobbing, block
   outline and clouds are suppressed, and you appear in the reflection.
4. **Drawing** — the mirror's fragment shader projects each pixel (snapped to the pixel grid when
   pixelated) into that render, then applies tint, reflectivity and opacity. Opaque mirrors are drawn
   first, see-through ones back to front.
5. **Frames and hidden signs** — frames are boxes textured with a block texture, emitted from the
   sign's own block entity renderer, so they're lit like the sign and show up in reflections. Hidden
   signs skip their text there, and their board is left out of the chunk mesh (vanilla and Sodium).
6. **Mirror-in-mirror** — mirrors are drawn at Fabric's `LevelRenderEvents.END_MAIN`, which also fires
   inside captures, so facing mirrors show each other one frame behind.

## Options

With [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config)
installed, open Mods → Sign Mirrors → Configure. Without them, edit `config/signmirrors.json`.

| Preset | Resolution | Scale | Renders/frame | Mirrors at once | Distance | Mirror-in-mirror |
|---|---|---|---|---|---|---|
| Low | 64–256 | 50% | 1 | 2 | 32 | off |
| Medium | 64–512 | 75% | 2 | 4 | 48 | on |
| **High** (default) | 128–1024 | 100% | 3 | 6 | 64 | on |
| Ultra | 128–2048 | 100% | 5 | 8 | 96 | on |
| Custom | your settings | | | | | |

Changing any Quality/Performance setting yourself switches the preset to Custom.

| Page | Setting | What it does |
|---|---|---|
| General | Mirrors enabled | Off turns every mirror back into a plain sign. |
| | Mirror controls in the sign editor | Show the editor panels. |
| Quality | Min / max reflection resolution | Range the LOD picks from. Max is the biggest quality knob. |
| | Resolution scale | Reflection texels per screen pixel of mirror. |
| | Mirrors in reflections | Mirror-in-mirror. |
| Performance | Reflection renders per frame | Mirrors re-rendered per frame — the main cost. |
| | Mirrors updated at once | Most important N visible mirrors. |
| | Mirror draw distance | Beyond this, mirrors aren't drawn. |
| Culling | Skip mirrors outside the view / hidden mirrors | Frustum / occlusion culling. |
| | Hidden-mirror check interval, grace period | Raycast frequency and anti-pop delay. |
| Advanced | Flip reflections vertically | Backend workaround. |

Tint, opacity and reflectivity are per mirror now (set in the sign editor), so the old global
*Reflectivity* option is gone, as are the cube-map options.

## Building

Needs **JDK 25**. Versions in `gradle.properties` (loader 0.19.5, Loom 1.18-SNAPSHOT,
Fabric API 0.162.0+26.3, Gradle 9.7.1) match FabricMC's official 26.3 example mod.

```
./gradlew build        # jar in build/libs/
./gradlew test         # unit tests: sign format, legacy conversion, geometry, frames, projection maths
./gradlew runClient
```

### GitHub Actions

`.github/workflows/build.yml` builds and tests on every push and pull request (JDK 25, Gradle
cache) and uploads the mod jar as a workflow artifact. Pushing a tag like `v0.1.0` also creates a
GitHub Release with the jar attached:

```
git tag v0.1.0 && git push origin v0.1.0
```

If a build fails, the test reports are uploaded as a separate artifact.

### Where the 26.3 API usage comes from

26.3 moved the GPU layer into Mojang's new `com.mojang.renderpearl` library (bind-group pipelines,
Vulkan-style draw calls) and split each frame into extract-then-render. Every Minecraft and
renderpearl call in this mod is one that is also used by code already building against 26.3 —
Fabric API, Iris, Sodium and Tweakeroo's 26.3 branches:

| Mod code | Modelled on |
|---|---|
| `render/MirrorDrawer` (pipelines incl. blending, bind group, render pass, draw) | Sodium `ShaderChunkRenderer` / `DefaultChunkRenderer`, Iris `HorizonRenderer` |
| `render/MirrorCapture` (camera entity, `Camera.update`, extract + render) | Tweakeroo freecam, Fabric's client game-test frame driver |
| `mixin/CameraMixin` (`calculateFov` in `Camera.update`) | Tweakeroo `MixinCamera` |
| `mixin/GameRendererMixin` (`ProjectionMatrixBuffer.getBuffer`, `bobView`) | Sodium and Tweakeroo `GameRenderer` mixins |
| End-of-world-pass hook | Fabric API `LevelRenderEvents.END_MAIN` |
| `mixin/AbstractSignEditScreenMixin`, `gui/*` (editor hooks, widgets) | Signed Paintings' 26.3 sign-editor hooks (same injection points; code is our own) |
| `mixin/AbstractSignRendererMixin`, `render/FrameRenderer` (frames, `submitCustomGeometry`, block sprites) | Signed Paintings' 26.3 sign renderer hooks |
| `mixin/optional/SectionCompilerMixin`, `SodiumBlockRendererMixin` (hiding the sign board) | Signed Paintings' chunk-mesh hooks for vanilla and Sodium |

26.3 has no public getter for sign text, so `mixin/SignBlockEntityAccessor` reads the private
`frontText` field (the same field Tweakeroo's 26.3 sign-copy feature uses).

If a capture throws at runtime, mirrors disable themselves for the session and the error is logged
instead of crashing the game.

## Known limitations

- When your eye moves faster than the budget can re-render every visible mirror, less important
  mirrors show the previous position's view for a frame or two.
- Each reflection update is a full world render. Lower *Reflection renders per frame* or the
  resolution settings (or pick a lower preset) if frame rate suffers.
- Translucent blocks (water, glass) *in front of* a mirror get drawn over by the mirror, because
  mirrors are drawn after the world. Glass also counts as blocking for hidden-mirror culling.
- See-through mirrors (opacity below 100%) are sorted against each other, not against other
  translucent things in the world.
- Frames are drawn by the sign's block entity, so a frame far from its sign (large offset) disappears
  when the sign's block is culled.
- A hidden sign stays hidden beyond the mirror draw distance (the frame still shows; the glass doesn't).
- Clouds are not drawn inside reflections (the cloud renderer can't share a frame between two views).
- Sodium: supported. Sodium assumes one level render per frame, so before each capture the mod resets
  Sodium's per-frame terrain uniforms (`render/SodiumCompat`, via reflection — no compile dependency).
- Shader packs (Iris) replace the world pipeline and are untested.
