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

1. **Tracking** — `MirrorTracker` follows every loaded sign block entity (Fabric's
   `ClientBlockEntityEvents`), reads the front text each tick, and builds the mirror quad from
   the sign's facing/rotation (`core/MirrorGeometry`).
2. **Proxy box** — every 2 s, rays are cast from the probe point along ±X/±Y/±Z to the first solid
   block (max `proxyMaxDistance`). That axis-aligned box approximates the room the mirror reflects.
3. **Secondary camera** — at the start of each frame's extraction, `MirrorCapture` points the game
   camera at an invisible probe entity just in front of the mirror and runs vanilla's own
   `extract` + `renderLevel` once per cube face, straight into that face's square render target.
   Only `facesPerFrame` faces are rendered per frame, round-robin across the nearest
   `maxActiveMirrors` visible mirrors, and only the ≤5 faces that can be seen in the reflection are
   ever rendered (the one facing into the wall is skipped). FOV is forced to 90°, the projection is
   made square, and the hand, view bobbing and block outline are suppressed. Because the camera
   entity is the probe, your own player model shows up in the mirror.
4. **Parallax-corrected lookup** — the mirror quad's fragment shader reflects the view ray, finds
   where the reflected ray exits the proxy box, and samples the cube faces in the direction from the
   probe to that point. That's what makes nearby walls and objects line up properly as you move,
   instead of the "painted on infinitely far away" look of a plain cube map.
5. **Mirror-in-mirror** — mirrors are drawn at Fabric's `LevelRenderEvents.END_MAIN`, which also fires
   inside captures, so facing mirrors show each other (one frame behind).

## Config — `config/signmirrors.json`

| Key | Default | Notes |
|---|---|---|
| `probeResolution` | 256 | Per cube face. 128 is fine for small mirrors; 512 for big ones. |
| `facesPerFrame` | 2 | The main cost: each face is one extra square world render. 1 = cheapest. |
| `maxActiveMirrors` | 4 | Nearest N visible mirrors get updates. |
| `maxMirrorDistance` | 48 | Beyond this, mirrors aren't drawn (the sign shows). |
| `proxyMaxDistance` | 32 | Parallax box reach. Larger suits outdoor mirrors. |
| `probeOffset` | 0.5 | Probe distance in front of the mirror. |
| `reflectivity` | 0.93 | Brightness multiplier. |
| `recursiveMirrors` | true | Draw other mirrors inside captures. |
| `disableOcclusionCullingDuringCapture` | true | Turn off chunk occlusion culling for reflections (as Iris does for shadows). |
| `flipFaceV` | false | Turn on if reflections are upside down on your graphics backend. |

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

Sign text is read through a small reflection helper (`world/SignTextReader`) because 26.3 reworked
the sign text accessors around `SignTextSlot`; the game is unobfuscated, so this is reliable.

If a capture throws at runtime, mirrors disable themselves for the session and the error is logged
instead of crashing the game.

## Known limitations

- Parallax correction assumes a box-shaped room. Odd shapes, and objects in the middle of the room,
  will be slightly off — that's inherent to the technique.
- Captures are full world renders at probe resolution; with many mirrors, lower `facesPerFrame`
  or `probeResolution`. Each face refreshes every few frames, so fast-moving things in reflections
  update at a lower rate than the main view.
- Each face capture is a full vanilla world render at probe resolution. With
  `disableOcclusionCullingDuringCapture` off, chunks hidden from the player but visible in the mirror
  may be missing, and chunk visibility may flicker; with it on, captures cost more CPU.
- Translucent blocks (water, glass) *in front of* a mirror get drawn over by the mirror, because
  mirrors are drawn after the world.
- Shader packs (Iris) and Sodium replace parts of the world pipeline; combinations with them are untested.
