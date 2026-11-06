package dev.signmirrors.world;

import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.core.MirrorGeometry;
import dev.signmirrors.core.MirrorSettings;
import dev.signmirrors.core.V3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches every loaded sign block entity and keeps a {@link Mirror} for each one whose front text
 * starts with "[Mirror]". Runs on the client/render thread (tick + render), so no locking, except
 * for {@link #isSignHidden}, which chunk-meshing threads read.
 */
public final class MirrorTracker {
    public static final MirrorTracker INSTANCE = new MirrorTracker();

    private final Set<SignBlockEntity> signs = new HashSet<>();
    /** Positions (BlockPos.asLong) of signs whose board is hidden; read by chunk meshing on worker threads. */
    private final Set<Long> hiddenSigns = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, Mirror> mirrors = new HashMap<>();
    private ClientLevel lastLevel;

    private MirrorTracker() {}

    public void track(SignBlockEntity sign) { signs.add(sign); }

    /** ClientChunkEvents.CHUNK_LOAD: picks up signs that were already placed in the world. */
    public void trackChunk(LevelChunk chunk) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof SignBlockEntity sign) signs.add(sign);
        }
    }

    public void untrack(SignBlockEntity sign) {
        signs.remove(sign);
        Mirror m = mirrors.remove(sign.getBlockPos());
        if (m != null) m.releaseProbe();
        // The block is going away (or its chunk is), so no rebuild is needed for it.
        hiddenSigns.remove(sign.getBlockPos().asLong());
    }

    public Collection<Mirror> all() { return mirrors.values(); }

    public Mirror at(BlockPos pos) { return mirrors.get(pos); }

    /** Whether the sign block at this position should be left out of the chunk mesh. Thread-safe. */
    public boolean isSignHidden(long pos) {
        return !hiddenSigns.isEmpty() && hiddenSigns.contains(pos);
    }

    public void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            // Only drop mirrors here. Signs in the new level were already tracked by the load events,
            // which fire while the level's chunks arrive -- before this first tick sees the new level.
            for (Mirror m : mirrors.values()) m.releaseProbe();
            mirrors.clear();
            hiddenSigns.clear();
            lastLevel = mc.level;
        }
        if (mc.level == null) {
            signs.clear();
            return;
        }
        ClientLevel level = mc.level;
        signs.removeIf(sign -> sign.isRemoved() || sign.getLevel() != level);
        MirrorConfig cfg = SignMirrorsClient.config;

        // Text arrives (and changes) via block entity update packets, and live from the sign editor,
        // so re-check every tick; it's a few string ops per sign.
        Set<BlockPos> seen = new HashSet<>();
        for (SignBlockEntity sign : signs) {
            if (sign.isRemoved()) continue;
            BlockPos pos = sign.getBlockPos();
            String[] lines = SignTextReader.frontLines(sign);
            if (lines == null) continue;
            BlockState state = sign.getBlockState();
            String key = String.join("\n", lines) + '|' + state;
            Mirror existing = mirrors.get(pos);
            if (existing != null && existing.key.equals(key)) {
                seen.add(pos);
                continue;
            }
            var settings = MirrorSettings.parse(lines[0], lines[1], lines[2], lines[3]);
            if (settings.isEmpty()) continue;
            MirrorGeometry geometry = buildGeometry(pos, state, settings.get());
            if (geometry == null) continue;
            seen.add(pos);
            Mirror m = existing != null ? existing : new Mirror(pos.immutable());
            // Edits keep the probe: the next capture re-renders it for the new shape.
            m.update(key, settings.get(), geometry, woodType(state));
            if (existing == null) mirrors.put(m.pos, m);
        }
        mirrors.entrySet().removeIf(e -> {
            if (seen.contains(e.getKey())) return false;
            e.getValue().releaseProbe();
            return true;
        });

        // Hidden sign boards are left out of chunk meshes; rebuild the section when that changes.
        Set<Long> wantHidden = new HashSet<>();
        if (cfg.enabled) {
            for (Mirror m : mirrors.values()) if (m.settings.hideSign) wantHidden.add(m.pos.asLong());
        }
        for (Long p : wantHidden) if (hiddenSigns.add(p)) rebuild(level, BlockPos.of(p));
        hiddenSigns.removeIf(p -> {
            if (wantHidden.contains(p)) return false;
            rebuild(level, BlockPos.of(p));
            return true;
        });

        // Probes are freed when far away.
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double release = cfg.maxMirrorDistance * 1.5;
        for (Mirror m : mirrors.values()) {
            if (dist(cam, m.geometry.center()) > release || !m.reflects()) m.releaseProbe();
        }
    }

    private static void rebuild(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, 3);
    }

    private static String woodType(BlockState state) {
        if (!(state.getBlock() instanceof SignBlock sign)) return "oak";
        String name = sign.type().name();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    public void clear() {
        for (Mirror m : mirrors.values()) m.releaseProbe();
        mirrors.clear();
        signs.clear();
        hiddenSigns.clear();
    }

    private static MirrorGeometry buildGeometry(BlockPos pos, BlockState state, MirrorSettings settings) {
        if (state.getBlock() instanceof WallSignBlock && state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            V3 front = new V3(facing.getStepX(), 0, facing.getStepZ());
            return MirrorGeometry.build(pos.getX(), pos.getY(), pos.getZ(), front, MirrorGeometry.Mount.WALL, settings);
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            V3 front = MirrorGeometry.frontFromRotation16(state.getValue(BlockStateProperties.ROTATION_16));
            return MirrorGeometry.build(pos.getX(), pos.getY(), pos.getZ(), front, MirrorGeometry.Mount.STANDING, settings);
        }
        return null; // wall hanging signs etc. are not supported
    }

    /** Distance from {@code from} toward {@code to} until the first solid block, or the full length. */
    public static double raycast(Minecraft mc, V3 from, V3 to) {
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
