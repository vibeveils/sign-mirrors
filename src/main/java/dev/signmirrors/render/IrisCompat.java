package dev.signmirrors.render;

import dev.signmirrors.SignMirrorsClient;
import net.fabricmc.loader.api.FabricLoader;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
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
 *       frame, and mix the reflection into its frame history (TAA, motion blur, "previous" matrices).
 *       So captures get their own Iris pipeline, created with Iris's own factory, swapped in only
 *       while a capture renders ({@code mixin/optional/IrisPipelineManagerMixin}). All reflection
 *       textures share one size so that pipeline never resizes.</li>
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

    /** Capture pipelines, by Iris dimension id. */
    private static final Map<Object, Object> capturePipelines = new HashMap<>();
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

    /** Before a capture's level render: give it fresh per-frame uniforms. */
    static void beforeCapture() {
        if (!init()) return;
        try {
            counterBeginFrame.invoke(counter);
        } catch (Throwable t) {
            SignMirrorsClient.LOGGER.warn("Could not advance Iris's frame counter", t);
        }
    }

    /** After a capture's level render: put the player's pipeline back. */
    static void afterCapture() {
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
            Object pipeline = capturePipelines.get(dimension);
            if (pipeline == null) {
                @SuppressWarnings("unchecked")
                Function<Object, Object> factory = (Function<Object, Object>) managerFactory.get(manager);
                SignMirrorsClient.LOGGER.info("Creating a separate Iris pipeline for mirror reflections ({})", dimension);
                pipeline = factory.apply(dimension);
                if (!irisPipelineClass.isInstance(pipeline)) {
                    destroyQuietly(pipeline);
                    throw new IllegalStateException("Iris could not create a shader pipeline for reflections");
                }
                capturePipelines.put(dimension, pipeline);
            }
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

    /** Iris is tearing down its pipelines (shader reload, pack change): drop ours too. */
    public static void destroyCapturePipelines() {
        for (Object p : capturePipelines.values()) destroyQuietly(p);
        capturePipelines.clear();
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
