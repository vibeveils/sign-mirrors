package dev.signmirrors.core;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a mirror sign asks for, parsed from its front text.
 *
 * <pre>
 * line 1: [Mirror]                 (case-insensitive, required)
 * line 2: W,H[align]               e.g. "2,1r", "3x2 lt", "1.5,2"   (optional, default 1,1 centred/bottom)
 *         horizontal align: l | c | r   (edge of the mirror that lines up with the sign's block)
 *         vertical align:   b | m | t   (b = mirror grows upward from the sign's block bottom)
 * line 3: yaw[,pitch]                  e.g. "15", "45", "10,-5", "y30 p10", "p15"   (optional, degrees)
 *         For l/r and b/t hinges, positive angles swing the free edge away from the wall.
 *         For centred hinges, positive yaw turns the reflective face to the viewer's right
 *         and positive pitch tilts it upward.
 * </pre>
 */
public record MirrorSpec(double width, double height, HAlign hAlign, VAlign vAlign, double yawDeg, double pitchDeg) {

    public enum HAlign { LEFT, CENTER, RIGHT }
    public enum VAlign { BOTTOM, MIDDLE, TOP }

    public static final String TAG = "[mirror]";
    public static final double MAX_SIZE = 16.0;
    public static final double MAX_ANGLE = 89.0;

    public static final MirrorSpec DEFAULT = new MirrorSpec(1, 1, HAlign.CENTER, VAlign.BOTTOM, 0, 0);

    private static final Pattern SIZE = Pattern.compile(
            "^(\\d+(?:\\.\\d+)?)\\s*[,x×*]\\s*(\\d+(?:\\.\\d+)?)\\s*([a-z]*)$");
    private static final Pattern NUM = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern TAGGED = Pattern.compile("([yp])\\s*([-+]?\\d+(?:\\.\\d+)?)");

    /** Returns empty if the sign is not a mirror sign. Malformed lines 2/3 fall back to defaults. */
    public static Optional<MirrorSpec> parse(String line1, String line2, String line3) {
        if (line1 == null || !line1.trim().toLowerCase(Locale.ROOT).equals(TAG)) return Optional.empty();

        double w = 1, h = 1;
        HAlign ha = HAlign.CENTER;
        VAlign va = VAlign.BOTTOM;
        String l2 = clean(line2);
        if (!l2.isEmpty()) {
            Matcher m = SIZE.matcher(l2);
            if (m.matches()) {
                w = clampSize(Double.parseDouble(m.group(1)));
                h = clampSize(Double.parseDouble(m.group(2)));
                for (char c : m.group(3).toCharArray()) {
                    switch (c) {
                        case 'l' -> ha = HAlign.LEFT;
                        case 'r' -> ha = HAlign.RIGHT;
                        case 'c' -> ha = HAlign.CENTER;
                        case 'b' -> va = VAlign.BOTTOM;
                        case 't' -> va = VAlign.TOP;
                        case 'm' -> va = VAlign.MIDDLE;
                        default -> { /* ignore unknown flags */ }
                    }
                }
            }
        }

        double yaw = 0, pitch = 0;
        String l3 = clean(line3).replace("°", "").replace("deg", "");
        if (!l3.isEmpty()) {
            Matcher tagged = TAGGED.matcher(l3);
            boolean anyTagged = false;
            while (tagged.find()) {
                anyTagged = true;
                double v = Double.parseDouble(tagged.group(2));
                if (tagged.group(1).equals("y")) yaw = v; else pitch = v;
            }
            if (!anyTagged) {
                Matcher n = NUM.matcher(l3);
                if (n.find()) yaw = Double.parseDouble(n.group());
                if (n.find()) pitch = Double.parseDouble(n.group());
            }
        }
        return Optional.of(new MirrorSpec(w, h, ha, va, clampAngle(yaw), clampAngle(pitch)));
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static double clampSize(double v) {
        return Math.max(0.0625, Math.min(MAX_SIZE, v));
    }

    private static double clampAngle(double v) {
        return Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, v));
    }
}
