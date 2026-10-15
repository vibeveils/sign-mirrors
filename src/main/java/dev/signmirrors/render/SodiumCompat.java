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

    static boolean present() {
        return PRESENT;
    }

    static boolean broken() {
        return broken;
    }
}
