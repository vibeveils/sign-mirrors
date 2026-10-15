package dev.signmirrors.render;

import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible camera entity for the secondary camera. Never added to the level, so it is not ticked,
 * saved or rendered; Minecraft just reads its position and rotation in Camera.update. A marker has
 * zero size, so its eye position is its position.
 */
final class ProbeEntity extends Marker {
    ProbeEntity(Level level) {
        super(EntityTypes.MARKER, level);
    }

    /** Sets current and previous pose identically so the camera's partial-tick interpolation is a no-op. */
    void pose(Vec3 pos, float yaw, float pitch) {
        this.setPos(pos.x, pos.y, pos.z);
        this.xo = pos.x;
        this.yo = pos.y;
        this.zo = pos.z;
        this.xOld = pos.x;
        this.yOld = pos.y;
        this.zOld = pos.z;
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yRotO = yaw;
        this.xRotO = pitch;
    }
}
