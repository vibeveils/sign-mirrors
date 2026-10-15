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
   `ClientBlockEntityEvents`), parses the front text each tick, and builds the mirror quad from
   the sign's facing/rotation (`core/MirrorGeometry`).
2. **Proxy box** — every 2 s, rays are cast from the probe point along ±X/±Y/±Z to the first solid
   block (max `proxyMaxDistance`). That axis-aligned box approximates the room the mirror reflects.
3. **Secondary camera** — at the start of each frame's world render, `MirrorCapture` re-runs the
   vanilla world render from the probe point (just in front of the mirror centre), one cube face per
   pass, into a small square target, then copies it into that mirror's 3×2 face atlas.
   Only `facesPerFrame` faces are rendered per frame, round-robin across the nearest
   `maxActiveMirrors` visible mirrors, and only the ≤5 faces that can be seen in the reflection are
   ever rendered (the one facing into the wall is skipped). The hand, view bobbing and block
   outline are suppressed, the camera is detached so you can see yourself, and the chunk occlusion
   graph from the player's position is reused rather than rebuilt per probe.
4. **Parallax-corrected lookup** — the mirror quad's fragment shader reflects the view ray, finds
   where the reflected ray exits the proxy box, and samples the cube map in the direction from the
   probe to that point. That's what makes nearby walls and objects line up properly as you move,
   instead of the "painted on infinitely far away" look of a plain cube map.
5. **Mirror-in-mirror** — other mirrors are drawn inside captures using their previous result, so
   facing mirrors give a (1-frame-lagged) infinite corridor.

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
| `freezeOcclusionDuringCapture` | true | Reuse the player's chunk visibility for probes. |
| `flipAtlasV` | false | Turn on if reflections are upside down on your graphics backend. |

## Building

Needs **JDK 25**. First update `gradle.properties` with the exact 26.3 values from
<https://fabricmc.net/develop> (loader, Loom, Fabric API), then:

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

### What to check on the first 26.3 build

The parsing/geometry/cube-face code (`core/`) is plain Java and is unit-tested. The rendering side
was written against Mojang's Blaze3D API as it stood in the 1.21.x → 26.x line but could not be
compiled against 26.3 when this project was generated (no network access to Fabric's Maven), so
expect a few name fixes. All of it lives in five files:

| File | API it leans on |
|---|---|
| `render/MirrorDrawer.java` | `RenderPipeline.builder`, `RenderPipelines.MATRICES_PROJECTION_SNIPPET`, `DynamicUniforms.writeTransform(...)` arg list, `RenderPass.bindTexture(name, view, sampler)` + `RenderSystem.getSamplerCache()` (older: `bindSampler(name, view)`), `VertexFormat.uploadImmediateVertexBuffer` |
| `render/MirrorProbe.java` | `GpuDevice.createTexture/createTextureView/createBuffer` signatures, usage flags |
| `render/MirrorCapture.java` | `GameRenderer.renderLevel(DeltaTracker)`, `TextureTarget` constructor, `CommandEncoder.copyTextureToTexture` |
| `mixin/CameraMixin.java` | `Camera.setup`, `setPosition`, `setRotation(yaw, pitch)`, field `detached` |
| `mixin/GameRendererMixin.java` | method names `renderLevel`, `getFov`, `bobView`, `bobHurt`, `renderItemInHand`, `shouldRenderBlockOutline`, and the `LevelRenderer.renderLevel` call inside `renderLevel` |

`SectionOcclusionGraphMixin` is in a separate non-required mixin config, so if those methods have
moved it is skipped with a warning rather than crashing. If a capture throws at runtime, mirrors
disable themselves for the session and the error is logged instead of crashing the game.

## Known limitations

- Parallax correction assumes a box-shaped room. Odd shapes, and objects in the middle of the room,
  will be slightly off — that's inherent to the technique.
- Captures are full world renders at probe resolution; with many mirrors, lower `facesPerFrame`
  or `probeResolution`. Each face refreshes every few frames, so fast-moving things in reflections
  update at a lower rate than the main view.
- With the occlusion freeze on, chunks hidden from the player but visible from a probe (e.g. around
  a corner) can be missing in the reflection; turn it off to trade that for CPU cost and possible flicker.
- Translucent blocks (water, glass) *in front of* a mirror get drawn over by the mirror, because
  mirrors are drawn after the world.
- Shader packs (Iris) replace the world pipeline and are not supported.
