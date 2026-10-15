package dev.signmirrors.render;

import net.minecraft.world.phys.Vec3;

/** Implemented on Camera by CameraMixin. */
public interface MirrorCameraAccess {
    void signmirrors$setPose(Vec3 position, float yaw, float pitch, boolean detached);
}
