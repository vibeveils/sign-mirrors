package dev.signmirrors.render;

import dev.signmirrors.SignMirrorsClient;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Iris shader packs, reached by reflection so Iris stays an optional, compile-free dependency.
 *
 * <h2>Why mirrors need help under Iris</h2>
 * <ul>
 *   <li><b>Drawing.</b> With a shader pack the world is drawn into Iris's own buffers and Iris's final
 *       pass then overwrites the whole screen, so anything drawn into the screen during the world
 *       pass (where mirrors normally go) disappears. Under Iris, mirrors are drawn right after Iris's
 *       final pass instead ({@code mixin/optional/LevelRendererIrisMixin}), depth-tested against
 *       the scene depth Iris leaves in the main depth buffer. Iris renders the level with ordinary
 *       (not reverse) depth, so the mirror's projection is converted to match.</li>
 *   <li><b>Reflections.</b> Each capture is a full shader-pack render. Doing that with the player's
 *       own Iris pipeline would resize all of its buffers to the reflection's size and back every
 *       frame, and mix the reflection into its frame history (TAA, motion blur, auto exposure,
 *       "previous" matrices). So captures get their own Iris pipelines, created with Iris's own
 *       factory, swapped in only while a capture renders ({@code mixin/optional/IrisPipelineManagerMixin}).
 *       All reflection textures share one size so those pipelines never resize.</li>
 *   <li><b>Temporal coherence.</b> Packs blend each frame with the previous one (TAA, exposure
 *       adaptation, motion blur). If one pipeline renders mirror A, then mirror B, then A again, every
 *       frame's "previous frame" is a different view, and the reflection flickers -- badly up close,
 *       where the view changes fastest. So the most important reflections get a pipeline of their own
 *       (config.irisDedicatedPipelines); a pipeline is reassigned only once its reflection has gone
 *       unused for a while, so the expensive pack compile happens rarely. The rest share one.</li>
 *   <li><b>Per-frame uniforms.</b> Iris refreshes camera uniforms once per frame counter tick; the
 *       counter is advanced before each capture so every reflection gets its own camera's values.</li>
 * </ul>
 */
public final class IrisCompat {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("iris");

    private static Method isPackInUse;
    private static Class<?> irisPipelineClass;
    private static Field managerPipeline, managerFactory;
    private static Object counter;
    private static Method counterBeginFrame;
    private static Field bypass;
    private static Method destroy;
    private static boolean broken;
    private static boolean captureFailed;

    /** A capture pipeline and the reflection (probe) it currently belongs to. */
    private static final class Slot {
        Object pipeline;
        Object dimension;
        Object owner;
        long lastUsed;
    }

    /** Pipelines owned by one reflection each. */
    private static final List<Slot> dedicated = new ArrayList<>();
    /** The pipeline shared by reflections without one of their own. */
    private static Slot shared;
    /** The reflection being rendered now (its probe), or null. */
    private static Object currentKey;
    /** A dedicated pipeline is reassigned after its reflection hasn't rendered for this many frames. */
    private static final long SLOT_IDLE_FRAMES = 90;
    /** The player's pipeline while a capture pipeline is swapped in. */
    private static Object savedPipeline;
    private static Object savedManager;

    private IrisCompat() {}

    private static boolean init() {
        if (!PRESENT || broken) return false;
        if (isPackInUse != null) return true;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            isPackInUse = iris.getMethod("isPackInUseQuick");
            irisPipelineClass = Class.forName("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            Class<?> manager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
            managerPipeline = manager.getDeclaredField("pipeline");
            managerPipeline.setAccessible(true);
            managerFactory = manager.getDeclaredField("pipelineFactory");
            managerFactory.setAccessible(true);
            Class<?> time = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            counter = time.getField("COUNTER").get(null);
            counterBeginFrame = counter.getClass().getMethod("beginFrame");
            bypass = Class.forName("net.irisshaders.iris.vertices.ImmediateState").getField("bypass");
            destroy = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline").getMethod("destroy");
            return true;
        } catch (Throwable t) {
            broken = true;
            isPackInUse = null;
            SignMirrorsClient.LOGGER.warn("Iris is installed but its internals don't match what Sign Mirrors expects; mirrors may not show with shader packs", t);
            return false;
        }
    }

    /** Whether a shader pack is rendering the world right now. */
    public static boolean shaderPackActive() {
        if (!init()) return false;
        try {
            return (boolean) isPackInUse.invoke(null);
        } catch (Throwable t) {
            broken = true;
            return false;
        }
    }

    /** Whether reflections can be rendered with the active shader pack (false after a failure). */
    public static boolean canCapture() {
        return !captureFailed && SignMirrorsClient.config.irisReflections;
    }

    // ---- Around each capture ----

    /** Before a capture's level render into {@code key} (its probe): give it fresh per-frame uniforms. */
    static void beforeCapture(Object key) {
        currentKey = key;
        if (!init()) return;
        try {
            counterBeginFrame.invoke(counter);
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.warn("Could not advance Iris's frame counter", t);
        }
    }

    /** After a capture's level render: put the player's pipeline back. */
    static void afterCapture() {
        currentKey = null;
        if (savedManager == null) return;
        try {
            managerPipeline.set(savedManager, savedPipeline);
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.warn("Could not restore Iris's pipeline", t);
        } finally {
            savedManager = null;
            savedPipeline = null;
        }
    }

    /**
     * Called from PipelineManager.preparePipeline: during a capture, returns (creating if needed) the
     * capture pipeline for this dimension and makes it current; otherwise null (Iris carries on).
     */
    public static Object capturePipelineFor(Object manager, Object dimension) {
        if (!MirrorCapture.isCapturing() || captureFailed || !init()) return null;
        try {
            Slot slot = slotFor(currentKey, dimension);
            if (slot.pipeline == null || !slot.dimension.equals(dimension)) {
                destroyQuietly(slot.pipeline);
                slot.pipeline = null;
                @SuppressWarnings("unchecked")
                Function<Object, Object> factory = (Function<Object, Object>) managerFactory.get(manager);
                SignMirrorsClient.LOGGER.info("Creating an Iris pipeline for mirror reflections ({}, {})",
                        dimension, slot == shared ? "shared" : "dedicated " + (dedicated.indexOf(slot) + 1));
                Object created = factory.apply(dimension);
                if (!irisPipelineClass.isInstance(created)) {
                    destroyQuietly(created);
                    throw new IllegalStateException("Iris could not create a shader pipeline for reflections");
                }
                slot.pipeline = created;
                slot.dimension = dimension;
            }
            slot.lastUsed = MirrorCapture.frame();
            Object pipeline = slot.pipeline;
            if (savedManager == null) {
                savedManager = manager;
                savedPipeline = managerPipeline.get(manager);
            }
            managerPipeline.set(manager, pipeline);
            return pipeline;
        } catch (Throwable t) {
            captureFailed = true;
            SignMirrorsClient.LOGGER.error("Reflections can't be rendered with this shader pack; mirrors will show only their tint while it is on", t);
            return null;
        }
    }

    /**
     * The pipeline for this reflection: its own if it has one; else a free or idle dedicated one
     * (or a new one, up to the configured number); else the shared one.
     */
    private static Slot slotFor(Object key, Object dimension) {
        long frame = MirrorCapture.frame();
        int wanted = SignMirrorsClient.config.irisDedicatedPipelines;
        while (dedicated.size() > wanted) destroyQuietly(dedicated.remove(dedicated.size() - 1).pipeline);
        if (key != null) {
            for (Slot s : dedicated) if (s.owner == key) return s;
            Slot idle = null;
            for (Slot s : dedicated) {
                if (s.owner == null || frame - s.lastUsed > SLOT_IDLE_FRAMES) {
                    if (idle == null || s.lastUsed < idle.lastUsed) idle = s;
                }
            }
            if (idle == null && dedicated.size() < wanted) {
                idle = new Slot();
                dedicated.add(idle);
            }
            if (idle != null) {
                idle.owner = key;
                return idle;
            }
        }
        if (shared == null) shared = new Slot();
        return shared;
    }

    /** Iris is tearing down its pipelines (shader reload, pack change): drop ours too. */
    public static void destroyCapturePipelines() {
        for (Slot s : dedicated) destroyQuietly(s.pipeline);
        dedicated.clear();
        if (shared != null) destroyQuietly(shared.pipeline);
        shared = null;
        captureFailed = false;
    }

    private static void destroyQuietly(Object pipeline) {
        try {
            if (destroy != null && pipeline != null) destroy.invoke(pipeline);
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.warn("Could not destroy a reflection pipeline", t);
        }
    }

    // ---- Drawing ----

    /** Keeps Iris from trying to swap the mirror shader for a shader-pack program while it's drawn. */
    static void setBypass(boolean value) {
        if (!init()) return;
        try {
            bypass.setBoolean(null, value);
        } catch (Throwable ignored) {
            // only affects a log message
        }
    }

    /**
     * The game's projection is reverse-Z; Iris renders the level with ordinary depth (near 0 / -1,
     * far 1). Converts the depth row the same way Iris does for its own matrices.
     */
    static Matrix4f undoReverseZ(Matrix4f m, boolean zeroToOne) {
        Matrix4f r = new Matrix4f(m);
        if (zeroToOne) {
            r.m02(r.m03() - 2f * r.m02());
            r.m12(r.m13() - 2f * r.m12());
            r.m22(r.m23() - 2f * r.m22());
            r.m32(r.m33() - 2f * r.m32());
        } else {
            r.m02(-r.m02());
            r.m12(-r.m12());
            r.m22(-r.m22());
            r.m32(-r.m32());
        }
        return r;
    }
}
