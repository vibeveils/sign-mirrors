package dev.signmirrors.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.signmirrors.SignMirrorsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * config/signmirrors.json — editable in-game via Mod Menu (with Cloth Config installed).
 * Choosing a preset other than CUSTOM overwrites the quality/performance fields with that preset.
 */
public class MirrorConfig {
    public enum Preset { LOW, MEDIUM, HIGH, ULTRA, CUSTOM }

    /**
     * PLANAR: exact reflections, one render per mirror from your reflected eye; re-rendered as you move.
     * CUBE_MAP: parallax-corrected cube map from a fixed probe; approximate (distorts things that
     * aren't on the room's walls) but reusable while you move.
     */
    public enum Technique { PLANAR, CUBE_MAP }

    public boolean enabled = true;
    public Preset preset = Preset.HIGH;
    public Technique technique = Technique.PLANAR;

    // ---- Quality (set by preset unless CUSTOM) ----
    /** Smallest cube-face resolution, used for small or distant mirrors. Power of two. */
    public int minResolution = 128;
    /** Largest cube-face resolution, used when a mirror fills the view. Power of two. */
    public int maxResolution = 1024;
    /** Face texels per screen pixel of mirror height; 1.0 roughly matches screen sharpness. */
    public float resolutionScale = 1.0f;
    /** Re-render faces the reflection can't currently show (keeps them fresh for when you move). */
    public boolean refreshHiddenFaces = true;
    /** Draw other mirrors inside reflections (mirror-in-mirror). */
    public boolean recursiveMirrors = true;

    // ---- Performance (set by preset unless CUSTOM) ----
    /** Cube faces re-rendered per frame across all mirrors. Each one is an extra square world render. */
    public int facesPerFrame = 3;
    /** Only this many of the most important visible mirrors get updates. */
    public int maxActiveMirrors = 6;
    /** Mirrors further away than this are neither updated nor drawn. */
    public double maxMirrorDistance = 64;

    // ---- Culling ----
    /** Skip mirrors outside the field of view. */
    public boolean frustumCulling = true;
    /** Skip mirrors hidden behind blocks or other mirrors. */
    public boolean occlusionCulling = true;
    /** Frames between occlusion raycasts per mirror. */
    public int occlusionCheckInterval = 3;
    /** Frames a mirror stays "visible" after it was last seen, to avoid popping. */
    public int visibilityGraceFrames = 10;

    // ---- Appearance / advanced ----
    /** Brightness multiplier on the reflection (real mirrors lose a little light). */
    public float reflectivity = 0.93f;
    /** Flip cube faces vertically when sampling. Turn on if reflections appear upside down on your graphics backend. */
    public boolean flipFaceV = false;
    /** Turn off chunk occlusion culling while rendering reflections, so chunks hidden from you but visible in a mirror aren't missing. */
    public boolean disableOcclusionCullingDuringCapture = true;
    /** Distance the parallax proxy box raycasts reach from the probe before giving up (open spaces). */
    public double proxyMaxDistance = 32;
    /** How far in front of the mirror centre the cube-map probe sits. */
    public double probeOffset = 0.5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("signmirrors.json");
    }

    public static MirrorConfig load() {
        MirrorConfig cfg = new MirrorConfig();
        try {
            if (Files.exists(path())) {
                try (Reader r = Files.newBufferedReader(path())) {
                    MirrorConfig read = GSON.fromJson(r, MirrorConfig.class);
                    if (read != null) cfg = read;
                }
            }
        } catch (Exception e) {
            SignMirrorsClient.LOGGER.warn("Could not read {}, using defaults", path(), e);
        }
        cfg.applyPresetAndSanitize();
        cfg.save();
        return cfg;
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(path())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            SignMirrorsClient.LOGGER.warn("Could not write {}", path(), e);
        }
    }

    public void applyPresetAndSanitize() {
        if (preset == null) preset = Preset.HIGH;
        if (technique == null) technique = Technique.PLANAR;
        switch (preset) {
            case LOW -> set(64, 256, 0.5f, false, false, 1, 2, 32);
            case MEDIUM -> set(64, 512, 0.75f, false, true, 2, 4, 48);
            case HIGH -> set(128, 1024, 1.0f, true, true, 3, 6, 64);
            case ULTRA -> set(128, 2048, 1.0f, true, true, 5, 8, 96);
            case CUSTOM -> { }
        }
        minResolution = pow2(Math.max(16, Math.min(2048, minResolution)));
        maxResolution = pow2(Math.max(minResolution, Math.min(2048, maxResolution)));
        resolutionScale = Math.max(0.1f, Math.min(4f, resolutionScale));
        facesPerFrame = Math.max(0, Math.min(30, facesPerFrame));
        maxActiveMirrors = Math.max(1, Math.min(32, maxActiveMirrors));
        maxMirrorDistance = Math.max(4, Math.min(512, maxMirrorDistance));
        occlusionCheckInterval = Math.max(1, Math.min(60, occlusionCheckInterval));
        visibilityGraceFrames = Math.max(0, Math.min(120, visibilityGraceFrames));
        reflectivity = Math.max(0f, Math.min(1.5f, reflectivity));
        proxyMaxDistance = Math.max(2, Math.min(256, proxyMaxDistance));
        probeOffset = Math.max(0.05, Math.min(4, probeOffset));
    }

    /** Whether the preset-controlled fields currently hold exactly what {@code p} would set. */
    public boolean matchesPreset(Preset p) {
        if (p == Preset.CUSTOM) return true;
        MirrorConfig t = new MirrorConfig();
        t.preset = p;
        t.applyPresetAndSanitize();
        return minResolution == t.minResolution && maxResolution == t.maxResolution
                && Math.abs(resolutionScale - t.resolutionScale) < 1e-3
                && refreshHiddenFaces == t.refreshHiddenFaces && recursiveMirrors == t.recursiveMirrors
                && facesPerFrame == t.facesPerFrame && maxActiveMirrors == t.maxActiveMirrors
                && Math.abs(maxMirrorDistance - t.maxMirrorDistance) < 1e-6;
    }

    private void set(int minRes, int maxRes, float scale, boolean hidden, boolean recursive,
                     int faces, int active, double distance) {
        minResolution = minRes;
        maxResolution = maxRes;
        resolutionScale = scale;
        refreshHiddenFaces = hidden;
        recursiveMirrors = recursive;
        facesPerFrame = faces;
        maxActiveMirrors = active;
        maxMirrorDistance = distance;
    }

    /** Rounds down to a power of two. */
    private static int pow2(int v) {
        return Integer.highestOneBit(Math.max(1, v));
    }
}
