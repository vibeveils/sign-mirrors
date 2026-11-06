package dev.signmirrors.access;

import net.minecraft.client.gui.components.AbstractWidget;

/** What the mirror editor needs from the sign edit screen (implemented by AbstractSignEditScreenMixin). */
public interface SignEditHost {
    /** The four lines as currently typed. */
    String[] signmirrors$lines();

    /** Replaces all four lines and updates the sign (so the world shows the change live). */
    void signmirrors$setLines(String[] lines);

    int signmirrors$maxLineWidth();

    void signmirrors$addWidget(AbstractWidget widget);
}
