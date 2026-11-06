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

    public boolean enabled = true;
    public Preset preset = Preset.HIGH;
    /** Show mirror controls in the sign editor (front side of a sign). */
    public boolean editorGui = true;

    // ---- Quality (set by preset unless CUSTOM) ----
    /** Smallest reflection resolution, used for small or distant mirrors. Power of two. */
    public int minResolution = 128;
    /** Largest reflection resolution, used when a mirror fills the view. Power of two. */
    public int maxResolution = 1024;
    /** Reflection texels per screen pixel of mirror size; 1.0 roughly matches screen sharpness. */
    public float resolutionScale = 1.0f;
    /** Draw other mirrors inside reflections (mirror-in-mirror). */
    public boolean recursiveMirrors = true;
    /**
     * Mirrors seen inside a reflection that are re-rendered from that reflection's viewpoint each
     * time it updates (so they move instead of looking frozen). Each one is an extra world render.
     */
    public int nestedMirrors = 1;

    // ---- Performance (set by preset unless CUSTOM) ----
    /** Reflections re-rendered per frame across all mirrors. Each one is an extra world render. */
    public int rendersPerFrame = 3;
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

    // ---- Shader packs (Iris) ----
    /** Render reflections with the active shader pack. Off: mirrors show only their tint while a pack is on. */
    public boolean irisReflections = true;
    /** Reflection renders per frame while a shader pack is on (each one is a full shader-pack render). */
    public int irisRendersPerFrame = 1;
    /** Size of every reflection texture while a shader pack is on. Power of two. */
    public int irisResolution = 1024;

    // ---- Advanced ----
    /** Flip reflections vertically when sampling. Turn on if reflections appear upside down on your graphics backend. */
    public boolean flipFaceV = false;

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
        switch (preset) {
            case LOW -> set(64, 256, 0.5f, false, 0, 1, 2, 32);
            case MEDIUM -> set(64, 512, 0.75f, true, 1, 2, 4, 48);
            case HIGH -> set(128, 1024, 1.0f, true, 1, 3, 6, 64);
            case ULTRA -> set(128, 2048, 1.0f, true, 2, 5, 8, 96);
            case CUSTOM -> { }
        }
        minResolution = pow2(Math.max(16, Math.min(2048, minResolution)));
        maxResolution = pow2(Math.max(minResolution, Math.min(2048, maxResolution)));
        resolutionScale = Math.max(0.1f, Math.min(4f, resolutionScale));
        rendersPerFrame = Math.max(0, Math.min(30, rendersPerFrame));
        nestedMirrors = Math.max(0, Math.min(4, nestedMirrors));
        irisRendersPerFrame = Math.max(0, Math.min(10, irisRendersPerFrame));
        irisResolution = pow2(Math.max(256, Math.min(2048, irisResolution)));
        maxActiveMirrors = Math.max(1, Math.min(32, maxActiveMirrors));
        maxMirrorDistance = Math.max(4, Math.min(512, maxMirrorDistance));
        occlusionCheckInterval = Math.max(1, Math.min(60, occlusionCheckInterval));
        visibilityGraceFrames = Math.max(0, Math.min(120, visibilityGraceFrames));
    }

    /** Whether the preset-controlled fields currently hold exactly what {@code p} would set. */
    public boolean matchesPreset(Preset p) {
        if (p == Preset.CUSTOM) return true;
        MirrorConfig t = new MirrorConfig();
        t.preset = p;
        t.applyPresetAndSanitize();
        return minResolution == t.minResolution && maxResolution == t.maxResolution
                && Math.abs(resolutionScale - t.resolutionScale) < 1e-3
                && recursiveMirrors == t.recursiveMirrors && nestedMirrors == t.nestedMirrors
                && rendersPerFrame == t.rendersPerFrame && maxActiveMirrors == t.maxActiveMirrors
                && Math.abs(maxMirrorDistance - t.maxMirrorDistance) < 1e-6;
    }

    private void set(int minRes, int maxRes, float scale, boolean recursive, int nested,
                     int renders, int active, double distance) {
        nestedMirrors = nested;
        minResolution = minRes;
        maxResolution = maxRes;
        resolutionScale = scale;
        recursiveMirrors = recursive;
        rendersPerFrame = renders;
        maxActiveMirrors = active;
        maxMirrorDistance = distance;
    }

    /** Rounds down to a power of two. */
    private static int pow2(int v) {
        return Integer.highestOneBit(Math.max(1, v));
    }
}
