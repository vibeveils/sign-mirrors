package dev.signmirrors.world;

import dev.signmirrors.mixin.SignBlockEntityAccessor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.util.List;

/** Reads the four front lines of a sign (unfiltered), via {@link SignBlockEntityAccessor}. */
final class SignTextReader {
    private SignTextReader() {}

    /** Returns the 4 front lines, or null if the sign has no text yet. */
    static String[] frontLines(SignBlockEntity sign) {
        SignText text = ((SignBlockEntityAccessor) sign).signmirrors$frontText();
        if (text == null) return null;
        List<Component> messages = text.getMessages(false);
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = i < messages.size() && messages.get(i) != null ? messages.get(i).getString() : "";
        }
        return lines;
    }
}
