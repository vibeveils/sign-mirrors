package dev.signmirrors.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.signmirrors.SignMirrorsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** config/signmirrors.json — written with defaults on first launch. */
public class MirrorConfig {
    /** Master switch. */
    public boolean enabled = true;
    /** Edge length of each cube-map face in pixels. The atlas per mirror is 3x2 faces. */
    public int probeResolution = 256;
    /** Cube faces re-rendered per frame across all mirrors. Each face is one extra (square) world render. */
    public int facesPerFrame = 2;
    /** Mirrors further than this from the camera are neither updated nor drawn. */
    public double maxMirrorDistance = 48;
    /** Only the N nearest visible mirrors get probe updates. */
    public int maxActiveMirrors = 4;
    /** Distance the parallax proxy box raycasts reach from the probe before giving up (open spaces). */
    public double proxyMaxDistance = 32;
    /** How far in front of the mirror centre the cube-map probe sits. */
    public double probeOffset = 0.5;
    /** Brightness multiplier on the reflection (real mirrors lose a little light). */
    public float reflectivity = 0.93f;
    /**
     * Turn off chunk occlusion culling while rendering reflections (as Iris does for its shadow pass),
     * so chunks hidden from the player but visible in the mirror aren't missing.
     */
    public boolean disableOcclusionCullingDuringCapture = true;
    /** Draw other mirrors inside captures, using their previous-frame reflection (mirror-in-mirror). */
    public boolean recursiveMirrors = true;
    /** Flip cube faces vertically when sampling. Turn on if reflections appear upside down on your graphics backend. */
    public boolean flipFaceV = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static MirrorConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("signmirrors.json");
        MirrorConfig cfg = new MirrorConfig();
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    MirrorConfig read = GSON.fromJson(r, MirrorConfig.class);
                    if (read != null) cfg = read;
                }
            }
            cfg.sanitize();
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception e) {
            SignMirrorsClient.LOGGER.warn("Could not read/write {}, using defaults", path, e);
        }
        return cfg;
    }

    private void sanitize() {
        probeResolution = Math.max(32, Math.min(1024, probeResolution));
        facesPerFrame = Math.max(0, Math.min(30, facesPerFrame));
        maxActiveMirrors = Math.max(1, Math.min(32, maxActiveMirrors));
        maxMirrorDistance = Math.max(4, maxMirrorDistance);
        proxyMaxDistance = Math.max(2, proxyMaxDistance);
        probeOffset = Math.max(0.05, Math.min(4, probeOffset));
    }
}
