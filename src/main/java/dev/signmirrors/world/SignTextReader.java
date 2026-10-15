package dev.signmirrors.world;

import dev.signmirrors.SignMirrorsClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reads the front text lines of a sign. 26.x reworked the sign text accessors around
 * {@code SignTextSlot}, so rather than hard-code one getter this finds the accessor by shape once
 * (the game is unobfuscated, so runtime names are the real ones) and caches it:
 * <ul>
 *   <li>a no-arg method whose name mentions "front" and returns the sign-text object, or</li>
 *   <li>a one-arg method taking an enum (the text slot) — called with the constant named FRONT.</li>
 * </ul>
 * The returned object's {@code (int, boolean) -> Component} method gives each line.
 */
final class SignTextReader {
    private static Method textGetter;
    private static Object frontSlot;
    private static Method lineGetter;
    private static boolean failed;

    private SignTextReader() {}

    /** Returns the 4 front lines, or null if the sign text can't be read on this version. */
    static String[] frontLines(SignBlockEntity sign) {
        if (failed) return null;
        try {
            if (textGetter == null) resolve(sign);
            Object text = frontSlot == null ? textGetter.invoke(sign) : textGetter.invoke(sign, frontSlot);
            if (text == null) return null;
            if (lineGetter == null) lineGetter = findLineGetter(text.getClass());
            String[] lines = new String[4];
            for (int i = 0; i < 4; i++) {
                Component c = (Component) lineGetter.invoke(text, i, false);
                lines[i] = c == null ? "" : c.getString();
            }
            return lines;
        } catch (Throwable t) {
            failed = true;
            SignMirrorsClient.LOGGER.error("Could not read sign text on this Minecraft version; mirrors disabled", t);
            return null;
        }
    }

    private static void resolve(SignBlockEntity sign) throws NoSuchMethodException {
        Method best = null;
        for (Method m : SignBlockEntity.class.getMethods()) {
            String name = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() == 0 && name.contains("front") && hasLineGetter(m.getReturnType())) {
                best = m;
                break;
            }
        }
        if (best != null) {
            textGetter = best;
            frontSlot = null;
            return;
        }
        for (Method m : SignBlockEntity.class.getMethods()) {
            if (m.getParameterCount() != 1) continue;
            Class<?> param = m.getParameterTypes()[0];
            if (!param.isEnum() || !hasLineGetter(m.getReturnType())) continue;
            for (Object constant : param.getEnumConstants()) {
                if (((Enum<?>) constant).name().toUpperCase(Locale.ROOT).contains("FRONT")) {
                    textGetter = m;
                    frontSlot = constant;
                    return;
                }
            }
        }
        throw new NoSuchMethodException("No front-text accessor found on SignBlockEntity");
    }

    private static boolean hasLineGetter(Class<?> type) {
        try {
            findLineGetter(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Method findLineGetter(Class<?> type) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == int.class && p[1] == boolean.class
                    && Component.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        throw new NoSuchMethodException("No (int, boolean) -> Component line getter on " + type.getName());
    }
}
