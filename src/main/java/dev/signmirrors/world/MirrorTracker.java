package dev.signmirrors.world;

import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSpec;
import dev.signmirrors.core.ProxyBox;
import dev.signmirrors.core.V3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches every loaded sign block entity and keeps a {@link Mirror} for each one whose front text
 * starts with "[Mirror]". Runs on the client/render thread (tick + render), so no locking.
 */
public final class MirrorTracker {
    public static final MirrorTracker INSTANCE = new MirrorTracker();

    private static final int BOX_REFRESH_TICKS = 40;

    private final Set<SignBlockEntity> signs = new HashSet<>();
    private final Map<BlockPos, Mirror> mirrors = new HashMap<>();
    private ClientLevel lastLevel;

    private MirrorTracker() {}

    public void track(SignBlockEntity sign) { signs.add(sign); }

    public void untrack(SignBlockEntity sign) {
        signs.remove(sign);
        Mirror m = mirrors.remove(sign.getBlockPos());
        if (m != null) m.releaseProbe();
    }

    public Collection<Mirror> all() { return mirrors.values(); }

    public void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            clear();
            lastLevel = mc.level;
        }
        if (mc.level == null) return;
        MirrorConfig cfg = SignMirrorsClient.config;

        // Text arrives (and changes) via block entity update packets, so re-check every tick; it's a few string ops per sign.
        Set<BlockPos> seen = new HashSet<>();
        for (SignBlockEntity sign : signs) {
            if (sign.isRemoved()) continue;
            BlockPos pos = sign.getBlockPos();
            String[] lines = SignTextReader.frontLines(sign);
            if (lines == null) continue;
            String l2 = lines[1], l3 = lines[2];
            var spec = MirrorSpec.parse(lines[0], l2, l3);
            if (spec.isEmpty()) continue;
            BlockState state = sign.getBlockState();
            String key = l2 + '|' + l3 + '|' + state + '|' + cfg.probeOffset;
            seen.add(pos);

            Mirror existing = mirrors.get(pos);
            if (existing != null && existing.key.equals(key)) continue;
            MirrorGeometry geometry = buildGeometry(pos, state, spec.get(), cfg.probeOffset);
            if (geometry == null) continue;
            if (existing != null) existing.releaseProbe();
            mirrors.put(pos.immutable(), new Mirror(pos.immutable(), key, spec.get(), geometry));
        }
        mirrors.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            e.getValue().releaseProbe();
            return true;
        });
        signs.removeIf(SignBlockEntity::isRemoved);

        // Proxy boxes follow the room as blocks change; probes are freed when far away.
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double release = cfg.maxMirrorDistance * 1.5;
        for (Mirror m : mirrors.values()) {
            double d = dist(cam, m.geometry.center());
            if (d > release) {
                m.releaseProbe();
                continue;
            }
            if (mc.player != null && (m.box == null || ++m.boxAgeTicks >= BOX_REFRESH_TICKS)) {
                m.box = ProxyBox.build(m.geometry, cfg.proxyMaxDistance, (from, to) -> raycast(mc, from, to));
                m.boxAgeTicks = 0;
            }
        }
    }

    /** Mirrors worth spending capture time on this frame: near, in front of, and roughly toward the camera. */
    public List<Mirror> active(Vec3 cam, Vec3 look, MirrorConfig cfg) {
        List<Mirror> out = new ArrayList<>();
        for (Mirror m : mirrors.values()) {
            V3 c = m.geometry.center();
            V3 toCam = new V3(cam.x - c.x(), cam.y - c.y(), cam.z - c.z());
            double d = toCam.length();
            if (d > cfg.maxMirrorDistance) continue;
            if (toCam.dot(m.geometry.normal()) <= 0) continue; // looking at the back
            V3 toMirror = toCam.mul(-1).normalize();
            if (d > 3 && toMirror.dot(new V3(look.x, look.y, look.z)) < -0.2) continue; // well behind us
            out.add(m);
        }
        out.sort(Comparator.comparingDouble(m -> dist(cam, m.geometry.center())));
        return out.size() > cfg.maxActiveMirrors ? new ArrayList<>(out.subList(0, cfg.maxActiveMirrors)) : out;
    }

    public void clear() {
        for (Mirror m : mirrors.values()) m.releaseProbe();
        mirrors.clear();
        signs.clear();
    }

    private static MirrorGeometry buildGeometry(BlockPos pos, BlockState state, MirrorSpec spec, double probeOffset) {
        if (state.getBlock() instanceof WallSignBlock && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            V3 front = new V3(facing.getStepX(), 0, facing.getStepZ());
            return MirrorGeometry.build(pos.getX(), pos.getY(), pos.getZ(), front, MirrorGeometry.Mount.WALL, spec, probeOffset);
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            V3 front = MirrorGeometry.frontFromRotation16(state.getValue(BlockStateProperties.ROTATION_16));
            return MirrorGeometry.build(pos.getX(), pos.getY(), pos.getZ(), front, MirrorGeometry.Mount.STANDING, spec, probeOffset);
        }
        return null; // wall hanging signs etc. are not supported
    }

    private static double raycast(Minecraft mc, V3 from, V3 to) {
        Vec3 a = new Vec3(from.x(), from.y(), from.z());
        Vec3 b = new Vec3(to.x(), to.y(), to.z());
        BlockHitResult hit = mc.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS ? a.distanceTo(b) : a.distanceTo(hit.getLocation());
    }

    private static double dist(Vec3 a, V3 b) {
        double dx = a.x - b.x(), dy = a.y - b.y(), dz = a.z - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
