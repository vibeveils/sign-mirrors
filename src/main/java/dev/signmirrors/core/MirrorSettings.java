package dev.signmirrors.core;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a mirror sign can ask for, and how it is stored in the sign's text.
 *
 * <h2>Sign text</h2>
 * <pre>
 * line 1:     [Mirror]
 * lines 2-4:  ~ followed by a base64url payload, continued across lines 3 and 4 as needed
 * </pre>
 * The payload is compact binary: a version byte, a varint of field flags, then only the fields that
 * differ from the defaults (see {@link #encode()}). The in-game editor writes it; nobody needs to
 * type it. Signs in the original text format ({@link MirrorSpec}: {@code 2,1r} / {@code 15,10}) are
 * still read, and converted to this format the first time they are changed in the editor.
 *
 * <h2>Conventions</h2>
 * Directions are as seen by someone reading the sign. The anchor is the point of the mirror chosen
 * by the alignment (e.g. bottom-right corner for {@code RIGHT}/{@code BOTTOM}); it sits where the
 * original format put it, moved by the offset (right / up / out of the sign, in blocks). The mirror
 * is then rotated about the anchor: yaw turns the reflective face toward the reader's right, pitch
 * tilts it up, roll turns it clockwise as seen by the reader... see {@link MirrorGeometry}.
 */
public final class MirrorSettings {
    public static final int FORMAT_VERSION = 1;
    /** Marks a line 2 holding the payload (not a valid legacy size, so the formats can't be confused). */
    public static final char PAYLOAD_MARK = '~';

    public static final double MIN_SIZE = 1 / 16.0, MAX_SIZE = 16;
    public static final double MAX_OFFSET = 16;
    public static final double MAX_YAW_PITCH = 90, MAX_ROLL = 180;
    public static final double MAX_FRAME_PX = 8;
    public static final int MAX_PIXELS_PER_BLOCK = 256;

    /**
     * Frame looks, by id. Ids are stored in signs, so only ever append. Like Signed Paintings' backs,
     * the main choices are NONE, SIGN_WOOD (planks of the sign's own wood) and BLOCK (the texture of
     * the block the sign is attached to); the rest are fixed materials (block-atlas texture paths).
     */
    public enum Frame {
        NONE("none", null),
        SIGN_WOOD("sign_wood", null),
        OAK("oak_planks", "block/oak_planks"),
        SPRUCE("spruce_planks", "block/spruce_planks"),
        BIRCH("birch_planks", "block/birch_planks"),
        JUNGLE("jungle_planks", "block/jungle_planks"),
        ACACIA("acacia_planks", "block/acacia_planks"),
        DARK_OAK("dark_oak_planks", "block/dark_oak_planks"),
        MANGROVE("mangrove_planks", "block/mangrove_planks"),
        CHERRY("cherry_planks", "block/cherry_planks"),
        PALE_OAK("pale_oak_planks", "block/pale_oak_planks"),
        BAMBOO("bamboo_planks", "block/bamboo_planks"),
        CRIMSON("crimson_planks", "block/crimson_planks"),
        WARPED("warped_planks", "block/warped_planks"),
        STRIPPED_OAK("stripped_oak_log", "block/stripped_oak_log"),
        STRIPPED_DARK_OAK("stripped_dark_oak_log", "block/stripped_dark_oak_log"),
        STONE("stone", "block/stone"),
        SMOOTH_STONE("smooth_stone", "block/smooth_stone"),
        DEEPSLATE("polished_deepslate", "block/polished_deepslate"),
        BLACKSTONE("polished_blackstone", "block/polished_blackstone"),
        QUARTZ("quartz", "block/quartz_block_side"),
        OBSIDIAN("obsidian", "block/obsidian"),
        IRON("iron", "block/iron_block"),
        GOLD("gold", "block/gold_block"),
        COPPER("copper", "block/copper_block"),
        WHITE_CONCRETE("white_concrete", "block/white_concrete"),
        BLACK_CONCRETE("black_concrete", "block/black_concrete"),
        BLOCK("block", null);

        /** Translation key suffix. */
        public final String key;
        /** Block-atlas texture, or null for NONE / SIGN_WOOD. */
        public final String texture;

        Frame(String key, String texture) {
            this.key = key;
            this.texture = texture;
        }

        public static Frame byId(int id) {
            Frame[] all = values();
            return id >= 0 && id < all.length ? all[id] : NONE;
        }

        public Frame next(int step) {
            Frame[] all = values();
            return all[Math.floorMod(ordinal() + step, all.length)];
        }

        /** One of the fixed materials (not NONE / SIGN_WOOD / BLOCK). */
        public boolean isMaterial() {
            return texture != null;
        }

        /** The next fixed material, wrapping around. */
        public Frame nextMaterial(int step) {
            Frame f = isMaterial() ? this : OAK;
            if (!isMaterial()) return f;
            do {
                f = f.next(step);
            } while (!f.isMaterial());
            return f;
        }

        /** Frame mode shown in the editor: NONE, SIGN_WOOD, BLOCK, or the material group. */
        public int mode() {
            return switch (this) {
                case NONE -> 0;
                case SIGN_WOOD -> 1;
                case BLOCK -> 2;
                default -> 3;
            };
        }
    }

    // ---- Placement ----
    public double width = 1, height = 1;
    public MirrorSpec.HAlign hAlign = MirrorSpec.HAlign.CENTER;
    public MirrorSpec.VAlign vAlign = MirrorSpec.VAlign.BOTTOM;
    public double yaw, pitch, roll;
    public double offsetX, offsetY, offsetZ;
    // ---- Appearance ----
    public Frame frame = Frame.NONE;
    /** Frame border width in texture pixels (1/16 block). */
    public double frameWidth = 2;
    /** Reflection pixels per block, 0 = smooth. */
    public int pixelsPerBlock;
    /** 0xRRGGBB multiplied into the reflection. */
    public int tint = 0xFFFFFF;
    /** 0..100 percent. */
    public int opacity = 100;
    /** 0..100 percent: 0 shows the plain tint colour, 100 a full reflection. */
    public int reflectivity = 100;
    /** Hide the sign board and text (they are covered by the mirror by default anyway). */
    public boolean hideSign;

    /** True when this came from the original text format and hasn't been rewritten yet. */
    public transient boolean legacy;

    public MirrorSettings() {}

    public MirrorSettings copy() {
        MirrorSettings s = new MirrorSettings();
        s.width = width; s.height = height; s.hAlign = hAlign; s.vAlign = vAlign;
        s.yaw = yaw; s.pitch = pitch; s.roll = roll;
        s.offsetX = offsetX; s.offsetY = offsetY; s.offsetZ = offsetZ;
        s.frame = frame; s.frameWidth = frameWidth; s.pixelsPerBlock = pixelsPerBlock;
        s.tint = tint; s.opacity = opacity; s.reflectivity = reflectivity; s.hideSign = hideSign;
        s.legacy = legacy;
        return s;
    }

    /** Converts a sign in the original format; the result has exactly the same geometry. */
    public static MirrorSettings fromLegacy(MirrorSpec spec) {
        MirrorSettings s = new MirrorSettings();
        s.width = spec.width();
        s.height = spec.height();
        s.hAlign = spec.hAlign();
        s.vAlign = spec.vAlign();
        // The original format swung hinged edges "away from the wall"; this one always uses one direction.
        s.yaw = spec.yawDeg() * (spec.hAlign() == MirrorSpec.HAlign.LEFT ? -1 : 1);
        s.pitch = spec.pitchDeg() * (spec.vAlign() == MirrorSpec.VAlign.BOTTOM ? -1 : 1);
        s.legacy = true;
        return s;
    }

    /**
     * Reads a sign's front text. Empty if it isn't a mirror sign. A payload that can't be decoded
     * (cut short, or from a newer version of the mod) gives a default mirror.
     */
    public static Optional<MirrorSettings> parse(String l1, String l2, String l3, String l4) {
        if (l1 == null || !l1.trim().toLowerCase(Locale.ROOT).equals(MirrorSpec.TAG)) return Optional.empty();
        String p2 = l2 == null ? "" : l2.trim();
        if (!p2.isEmpty() && p2.charAt(0) == PAYLOAD_MARK) {
            String payload = p2.substring(1) + trim(l3) + trim(l4);
            return Optional.of(decode(payload).orElseGet(MirrorSettings::new));
        }
        return MirrorSpec.parse(l1, l2, l3).map(MirrorSettings::fromLegacy);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    public void sanitize() {
        width = clamp(finite(width, 1), MIN_SIZE, MAX_SIZE);
        height = clamp(finite(height, 1), MIN_SIZE, MAX_SIZE);
        if (hAlign == null) hAlign = MirrorSpec.HAlign.CENTER;
        if (vAlign == null) vAlign = MirrorSpec.VAlign.BOTTOM;
        yaw = clamp(finite(yaw, 0), -MAX_YAW_PITCH, MAX_YAW_PITCH);
        pitch = clamp(finite(pitch, 0), -MAX_YAW_PITCH, MAX_YAW_PITCH);
        roll = clamp(finite(roll, 0), -MAX_ROLL, MAX_ROLL);
        offsetX = clamp(finite(offsetX, 0), -MAX_OFFSET, MAX_OFFSET);
        offsetY = clamp(finite(offsetY, 0), -MAX_OFFSET, MAX_OFFSET);
        offsetZ = clamp(finite(offsetZ, 0), -MAX_OFFSET, MAX_OFFSET);
        if (frame == null) frame = Frame.NONE;
        frameWidth = clamp(finite(frameWidth, 2), 0.5, MAX_FRAME_PX);
        pixelsPerBlock = Math.max(0, Math.min(MAX_PIXELS_PER_BLOCK, pixelsPerBlock));
        tint &= 0xFFFFFF;
        opacity = Math.max(0, Math.min(100, opacity));
        reflectivity = Math.max(0, Math.min(100, reflectivity));
    }

    private static double finite(double v, double fallback) {
        return Double.isFinite(v) ? v : fallback;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // =====================================================================================
    // Payload
    // =====================================================================================

    private static final int F_SIZE = 1, F_ALIGN = 1 << 1, F_ROTATION = 1 << 2, F_OFFSET = 1 << 3,
            F_FRAME = 1 << 4, F_PIXELS = 1 << 5, F_TINT = 1 << 6, F_OPACITY = 1 << 7,
            F_REFLECTIVITY = 1 << 8, F_HIDE_SIGN = 1 << 9;

    /** Payload text (without the leading {@link #PAYLOAD_MARK}). Values are stored quantised (see below). */
    public String encode() {
        MirrorSettings s = copy();
        s.sanitize();
        MirrorSettings d = new MirrorSettings();
        int flags = 0;
        if (q16(s.width) != q16(d.width) || q16(s.height) != q16(d.height)) flags |= F_SIZE;
        if (s.hAlign != d.hAlign || s.vAlign != d.vAlign) flags |= F_ALIGN;
        if (q2(s.yaw) != 0 || q2(s.pitch) != 0 || q2(s.roll) != 0) flags |= F_ROTATION;
        if (q16(s.offsetX) != 0 || q16(s.offsetY) != 0 || q16(s.offsetZ) != 0) flags |= F_OFFSET;
        if (s.frame != Frame.NONE) flags |= F_FRAME;
        if (s.pixelsPerBlock != 0) flags |= F_PIXELS;
        if (s.tint != d.tint) flags |= F_TINT;
        if (s.opacity != d.opacity) flags |= F_OPACITY;
        if (s.reflectivity != d.reflectivity) flags |= F_REFLECTIVITY;
        if (s.hideSign) flags |= F_HIDE_SIGN;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT_VERSION);
        uvarint(out, flags);
        if ((flags & F_SIZE) != 0) { uvarint(out, q16(s.width)); uvarint(out, q16(s.height)); }
        if ((flags & F_ALIGN) != 0) out.write(s.hAlign.ordinal() * 3 + s.vAlign.ordinal());
        if ((flags & F_ROTATION) != 0) { svarint(out, q2(s.yaw)); svarint(out, q2(s.pitch)); svarint(out, q2(s.roll)); }
        if ((flags & F_OFFSET) != 0) { svarint(out, q16(s.offsetX)); svarint(out, q16(s.offsetY)); svarint(out, q16(s.offsetZ)); }
        if ((flags & F_FRAME) != 0) { uvarint(out, s.frame.ordinal()); uvarint(out, q2(s.frameWidth)); }
        if ((flags & F_PIXELS) != 0) uvarint(out, s.pixelsPerBlock);
        if ((flags & F_TINT) != 0) { out.write(s.tint >> 16 & 0xFF); out.write(s.tint >> 8 & 0xFF); out.write(s.tint & 0xFF); }
        if ((flags & F_OPACITY) != 0) out.write(s.opacity);
        if ((flags & F_REFLECTIVITY) != 0) out.write(s.reflectivity);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** Empty if the payload is malformed, truncated or from an unknown version. */
    public static Optional<MirrorSettings> decode(String payload) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payload.replaceAll("\\s", ""));
            Reader in = new Reader(bytes);
            if (in.u8() != FORMAT_VERSION) return Optional.empty();
            int flags = in.uvarint();
            MirrorSettings s = new MirrorSettings();
            if ((flags & F_SIZE) != 0) { s.width = in.uvarint() / 16.0; s.height = in.uvarint() / 16.0; }
            if ((flags & F_ALIGN) != 0) {
                int a = in.u8();
                if (a > 8) return Optional.empty();
                s.hAlign = MirrorSpec.HAlign.values()[a / 3];
                s.vAlign = MirrorSpec.VAlign.values()[a % 3];
            }
            if ((flags & F_ROTATION) != 0) { s.yaw = in.svarint() / 2.0; s.pitch = in.svarint() / 2.0; s.roll = in.svarint() / 2.0; }
            if ((flags & F_OFFSET) != 0) { s.offsetX = in.svarint() / 16.0; s.offsetY = in.svarint() / 16.0; s.offsetZ = in.svarint() / 16.0; }
            if ((flags & F_FRAME) != 0) { s.frame = Frame.byId(in.uvarint()); s.frameWidth = in.uvarint() / 2.0; }
            if ((flags & F_PIXELS) != 0) s.pixelsPerBlock = in.uvarint();
            if ((flags & F_TINT) != 0) s.tint = in.u8() << 16 | in.u8() << 8 | in.u8();
            if ((flags & F_OPACITY) != 0) s.opacity = in.u8();
            if ((flags & F_REFLECTIVITY) != 0) s.reflectivity = in.u8();
            s.hideSign = (flags & F_HIDE_SIGN) != 0;
            s.sanitize();
            return Optional.of(s);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    /** Sixteenths of a block. */
    private static int q16(double v) { return (int) Math.round(v * 16); }
    /** Half degrees / half pixels. */
    private static int q2(double v) { return (int) Math.round(v * 2); }

    private static void uvarint(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static void svarint(ByteArrayOutputStream out, int v) {
        uvarint(out, (v << 1) ^ (v >> 31));
    }

    private static final class Reader {
        private final byte[] b;
        private int i;

        Reader(byte[] b) { this.b = b; }

        int u8() {
            if (i >= b.length) throw new IndexOutOfBoundsException("payload too short");
            return b[i++] & 0xFF;
        }

        int uvarint() {
            int v = 0;
            for (int shift = 0; shift < 32; shift += 7) {
                int x = u8();
                v |= (x & 0x7F) << shift;
                if ((x & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("varint too long");
        }

        int svarint() {
            int z = uvarint();
            return (z >>> 1) ^ -(z & 1);
        }
    }

    // =====================================================================================
    // Sign lines
    // =====================================================================================

    /** Measures text the way the sign renders it. */
    @FunctionalInterface
    public interface TextWidth {
        int width(String s);
    }

    /**
     * The four sign lines for these settings, splitting the payload so each line fits in
     * {@code maxLineWidth}. Returns null when it doesn't fit on the sign at all (very small signs).
     */
    public String[] toLines(int maxLineWidth, TextWidth font) {
        String rest = PAYLOAD_MARK + encode();
        String[] lines = {"[Mirror]", "", "", ""};
        for (int line = 1; line < 4 && !rest.isEmpty(); line++) {
            int n = fitting(rest, maxLineWidth, font);
            lines[line] = rest.substring(0, n);
            rest = rest.substring(n);
        }
        return rest.isEmpty() ? lines : null;
    }

    private static int fitting(String s, int maxWidth, TextWidth font) {
        int n = 0;
        while (n < s.length() && font.width(s.substring(0, n + 1)) <= maxWidth) n++;
        return Math.max(1, n);
    }

    // =====================================================================================

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof MirrorSettings s)) return false;
        return Double.compare(width, s.width) == 0 && Double.compare(height, s.height) == 0
                && hAlign == s.hAlign && vAlign == s.vAlign
                && Double.compare(yaw, s.yaw) == 0 && Double.compare(pitch, s.pitch) == 0 && Double.compare(roll, s.roll) == 0
                && Double.compare(offsetX, s.offsetX) == 0 && Double.compare(offsetY, s.offsetY) == 0
                && Double.compare(offsetZ, s.offsetZ) == 0 && frame == s.frame
                && Double.compare(frameWidth, s.frameWidth) == 0 && pixelsPerBlock == s.pixelsPerBlock
                && tint == s.tint && opacity == s.opacity && reflectivity == s.reflectivity && hideSign == s.hideSign;
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, hAlign, vAlign, yaw, pitch, roll, offsetX, offsetY, offsetZ,
                frame, frameWidth, pixelsPerBlock, tint, opacity, reflectivity, hideSign);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "MirrorSettings[%sx%s %s/%s rot=%s,%s,%s off=%s,%s,%s frame=%s/%s px=%d tint=%06X op=%d refl=%d hide=%s%s]",
                width, height, hAlign, vAlign, yaw, pitch, roll, offsetX, offsetY, offsetZ, frame, frameWidth,
                pixelsPerBlock, tint, opacity, reflectivity, hideSign, legacy ? " legacy" : "");
    }
}
