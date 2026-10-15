# Sign Mirrors (Fabric, Minecraft 26.3, client-side)

Write `[Mirror]` on a sign and it becomes a live mirror. Works on any server — nothing is
installed server-side; other players without the mod just see a sign.

## Sign format

| Line | Meaning | Examples |
|---|---|---|
| 1 | `[Mirror]` (case-insensitive) — required | `[Mirror]` |
| 2 | `W,H` in blocks, plus optional alignment letters | `2,1r` · `3x2 lt` · `1.5,2` · *(blank = 1,1)* |
| 3 | Off-axis angle(s) in degrees: `yaw[,pitch]`, or tagged | `15` · `45` · `10,-5` · `p15` · `y30 p10` |
| 4 | Free text (ignored) | |

**Alignment (line 2)** — which edge of the mirror lines up with the sign's block:

- Horizontal: `l` left · `c` centre (default) · `r` right — left/right as seen when reading the sign.
  `2,1r` = 2 wide, 1 tall, its right edge at the sign's right edge, extending 2 blocks to your left.
- Vertical: `b` bottom (default, grows upward from the sign's block) · `m` middle · `t` top (grows downward).

**Angles (line 3)** — the aligned edge acts as a hinge:

- With `l`/`r` (yaw) or `b`/`t` (pitch) hinges, positive angles swing the free edge *away from the
  wall* like a door or a tilted vanity mirror. Negative angles go the other way.
- With centred hinges, positive yaw turns the reflective face to your right; positive pitch tilts it up.
- Clamped to ±89°. Sizes are clamped to 16 blocks.

Wall signs put the mirror flush against the wall behind them (just proud of the sign board, which
it hides). Standing and ceiling-hanging signs put it through the middle of the block, facing the
same way as the text. Wall-hanging signs are not supported.

## How it works

1. **Tracking** — `MirrorTracker` follows every loaded sign block entity, including signs already in
   the world when you join (each chunk's block entities are scanned as it loads). It reads the
   front text each tick and builds the mirror quad from the sign's facing/rotation.
2. **Proxy box** — every 2 s, rays are cast from the probe point along ±X/±Y/±Z to the first solid
   block (max *Room size limit*). That axis-aligned box approximates the room the mirror reflects.
3. **Visibility and level of detail** (`render/MirrorVisibility`, once per frame):
   - *Frustum culling* — mirrors outside the view are skipped.
   - *Occlusion culling* — rays from the camera to nine points on the mirror; it's hidden only if
     every ray is blocked by a solid block or by another mirror. Checked every few frames, with a
     grace period so mirrors don't pop in and out.
   - *Resolution* — each mirror's projected screen size picks its cube-face resolution (between the
     min and max, times *Resolution scale*). A mirror filling the view gets the maximum; a small
     distant one the minimum. Changes wait a few frames, and the new probe is filled before it
     replaces the old one, so there's no grey flash.
   - *Priority* — bigger, closer and more central mirrors are refreshed more often.
   - *Visible faces* — the same reflect-and-parallax maths as the shader, run on a grid over the
     mirror, finds which cube faces the reflection actually reads from where you're standing.
     Usually that's only one to three faces.
4. **Secondary camera** — the budget (*Reflection updates per frame*) goes to the highest-priority
   faces: never-captured visible faces first (a few may exceed the budget so new mirrors fill in
   at once), then visible faces by staleness, then occasionally the faces you can't currently see.
   Each face is a vanilla `extract` + `renderLevel` pass from the probe entity, at exactly 90° x 90°,
   straight into that face's render target. The hand, view bobbing, block outline and clouds are
   suppressed, and you appear in the reflection.
5. **Parallax-corrected lookup** — the mirror's fragment shader reflects the view ray, finds where it
   exits the proxy box, and samples the cube faces from the probe toward that point.
6. **Mirror-in-mirror** — mirrors are drawn at Fabric's `LevelRenderEvents.END_MAIN`, which also fires
   inside captures (frustum-culled per view), so facing mirrors show each other one frame behind.

## Options

With [Mod Menu](https://modrinth.com/mod/modmenu) and [Cloth Config](https://modrinth.com/mod/cloth-config)
installed, open Mods → Sign Mirrors → Configure. Without them, edit `config/signmirrors.json`.

| Preset | Face resolution | Scale | Updates/frame | Mirrors at once | Distance | Mirror-in-mirror | Off-screen refresh |
|---|---|---|---|---|---|---|---|
| Low | 32–128 | 50% | 1 | 2 | 32 | off | off |
| Medium | 64–256 | 75% | 2 | 4 | 48 | on | off |
| **High** (default) | 64–512 | 100% | 3 | 6 | 64 | on | on |
| Ultra | 128–1024 | 125% | 5 | 8 | 96 | on | on |
| Custom | your settings | | | | | | |

Changing any Quality/Performance setting yourself switches the preset to Custom.

| Page | Setting | What it does |
|---|---|---|
| Quality | Min / max face resolution | Range the LOD picks from. Max is the biggest quality knob. |
| | Resolution scale | Reflection texels per screen pixel of mirror height. |
| | Refresh off-screen reflection directions | Keep faces you can't see fresh for when you move. |
| | Mirrors in reflections | Mirror-in-mirror. |
| | Reflectivity | Reflection brightness. |
| Performance | Reflection updates per frame | Faces re-rendered per frame — the main cost. |
| | Mirrors updated at once | Most important N visible mirrors. |
| | Mirror draw distance | Beyond this, mirrors are plain signs. |
| Culling | Skip mirrors outside the view / hidden mirrors | Frustum / occlusion culling. |
| | Hidden-mirror check interval, grace period | Raycast frequency and anti-pop delay. |
| Advanced | Full chunk visibility in reflections | Turns off chunk occlusion culling during captures. |
| | Room size limit, probe distance | Parallax box reach and probe placement. |
| | Flip reflections vertically | Backend workaround. |

## Building

Needs **JDK 25**. Versions in `gradle.properties` (loader 0.19.5, Loom 1.18-SNAPSHOT,
Fabric API 0.162.0+26.3, Gradle 9.7.1) match FabricMC's official 26.3 example mod.

```
./gradlew build        # jar in build/libs/
./gradlew test         # unit tests for parsing, geometry and cube-face maths
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
| `render/MirrorDrawer` (pipeline, bind group, render pass, draw) | Sodium `ShaderChunkRenderer` / `DefaultChunkRenderer`, Iris `HorizonRenderer` |
| `render/MirrorCapture` (camera entity, `Camera.update`, extract + render) | Tweakeroo freecam, Fabric's client game-test frame driver |
| `mixin/CameraMixin` (`calculateFov` in `Camera.update`) | Tweakeroo `MixinCamera` |
| `mixin/GameRendererMixin` (`ProjectionMatrixBuffer.getBuffer`, `bobView`) | Sodium and Tweakeroo `GameRenderer` mixins |
| End-of-world-pass hook | Fabric API `LevelRenderEvents.END_MAIN` |

26.3 has no public getter for sign text, so `mixin/SignBlockEntityAccessor` reads the private
`frontText` field (the same field Tweakeroo's 26.3 sign-copy feature uses).

If a capture throws at runtime, mirrors disable themselves for the session and the error is logged
instead of crashing the game.

## Known limitations

- Parallax correction assumes a box-shaped room. Odd shapes, and objects in the middle of the room,
  will be slightly off — that's inherent to the technique.
- Each face update is a full world render. Lower *Reflection updates per frame* or the resolution
  settings (or pick a lower preset) if frame rate suffers. Reflections refresh by priority, so
  fast-moving things in reflections update less often than the main view.
- With *Full chunk visibility in reflections* off, chunks hidden from the player but visible in the mirror
  may be missing, and chunk visibility may flicker; with it on, captures cost more CPU.
- Translucent blocks (water, glass) *in front of* a mirror get drawn over by the mirror, because
  mirrors are drawn after the world. Glass also counts as blocking for hidden-mirror culling.
- Clouds are not drawn inside reflections (the cloud renderer can't share a frame between two views).
- Sodium: supported. Sodium assumes one level render per frame, so before each capture the mod resets
  Sodium's per-frame terrain uniforms (`render/SodiumCompat`, via reflection — no compile dependency).
- Shader packs (Iris) replace the world pipeline and are untested.
