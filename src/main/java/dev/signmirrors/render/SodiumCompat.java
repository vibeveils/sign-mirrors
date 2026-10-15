package dev.signmirrors.render;

import dev.signmirrors.SignMirrorsClient;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sodium keeps its terrain uniforms in a once-per-frame cache that is only reset during its terrain
 * setup, and assumes one level render per frame. Each mirror capture is an extra level render, so
 * reset that cache before each one. Reflection keeps Sodium an optional, compile-free dependency.
 */
final class SodiumCompat {
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("sodium");

    private static Method instanceNullable;
    private static Field uniformBufferManager;
    private static Method prepareFrame;
    private static boolean broken;

    private SodiumCompat() {}

    static void beforeExtraLevelRender() {
        if (!PRESENT || broken) return;
        try {
            if (instanceNullable == null) {
                Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
                instanceNullable = renderer.getMethod("instanceNullable");
                uniformBufferManager = renderer.getDeclaredField("uniformBufferManager");
                uniformBufferManager.setAccessible(true);
                prepareFrame = uniformBufferManager.getType().getMethod("prepareFrame");
            }
            Object renderer = instanceNullable.invoke(null);
            if (renderer == null) return;
            Object manager = uniformBufferManager.get(renderer);
            if (manager != null) prepareFrame.invoke(manager);
        } catch (Throwable t) {
            broken = true;
            SignMirrorsClient.LOGGER.warn("Sodium compatibility hook unavailable for this Sodium version", t);
        }
    }

    private static Method getProjection;
    private static boolean projectionBroken;

    /**
     * Sodium keeps its own copy of the level projection (a Matrix4f it fills from the same upload
     * call this mod adjusts). Overwrite it with the capture projection so Sodium's terrain uses it too.
     */
    static void setProjection(Object gameRenderer, org.joml.Matrix4f projection) {
        if (!PRESENT || projectionBroken) return;
        try {
            if (getProjection == null) {
                Class<?> storage = Class.forName("net.caffeinemc.mods.sodium.client.util.GameRendererStorage");
                getProjection = storage.getMethod("sodium$getProjectionMatrix");
            }
            if (getProjection.getDeclaringClass().isInstance(gameRenderer)
                    && getProjection.invoke(gameRenderer) instanceof org.joml.Matrix4f stored) {
                stored.set(projection);
            }
        } catch (Throwable t) {
            projectionBroken = true;
            SignMirrorsClient.LOGGER.warn("Sodium projection hook unavailable for this Sodium version", t);
        }
    }

    static boolean present() {
        return PRESENT;
    }

    static boolean broken() {
        return broken;
    }
}
