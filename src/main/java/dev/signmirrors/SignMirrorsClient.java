package dev.signmirrors;

import dev.signmirrors.config.MirrorConfig;
import dev.signmirrors.world.MirrorTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SignMirrorsClient implements ClientModInitializer {
    public static final String MOD_ID = "signmirrors";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static MirrorConfig config = new MirrorConfig();

    @Override
    public void onInitializeClient() {
        config = MirrorConfig.load();

        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> {
            if (be instanceof SignBlockEntity sign) MirrorTracker.INSTANCE.track(sign);
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> {
            if (be instanceof SignBlockEntity sign) MirrorTracker.INSTANCE.untrack(sign);
        });
        ClientTickEvents.END_CLIENT_TICK.register(MirrorTracker.INSTANCE::tick);
    }
}
