package dev.signmirrors.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.access.SignEditHost;
import dev.signmirrors.gui.MirrorEditor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the mirror editor to the sign editing screen (front side only). While the sign is a mirror the
 * title is hidden, the sign is drawn smaller and the background isn't dimmed, so the real mirror in
 * the world can be seen changing as the controls move.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen implements SignEditHost {
    @Final @Shadow private SignText.Mutable text;
    @Final @Shadow private String[] messages;
    @Final @Shadow protected SignBlockEntity sign;
    @Final @Shadow private SignTextSlot slot;
    @Shadow private int line;
    @Final @Shadow private TextFieldHelper signField;

    @Unique private MirrorEditor signmirrors$editor;

    protected AbstractSignEditScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private boolean signmirrors$mirrorMode() {
        return signmirrors$editor != null && signmirrors$editor.isMirror();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void signmirrors$init(CallbackInfo ci) {
        signmirrors$editor = null;
        if (slot != SignTextSlot.FRONT || !SignMirrorsClient.config.enabled || !SignMirrorsClient.config.editorGui) return;
        signmirrors$editor = MirrorEditor.attach(this, this.font, this.width, this.height);
    }

    // ---- SignEditHost ----

    @Override
    public String[] signmirrors$lines() {
        return messages.clone();
    }

    @Override
    public void signmirrors$setLines(String[] lines) {
        for (int i = 0; i < messages.length && i < lines.length; i++) {
            messages[i] = lines[i];
            text.setLine(i, Component.literal(lines[i]));
        }
        sign.setText(text.asImmutable(), slot);
        int end = messages[line].length();
        signField.setSelectionRange(end, end);
    }

    @Override
    public int signmirrors$maxLineWidth() {
        return sign.getMaxTextLineWidth();
    }

    @Override
    public void signmirrors$addWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    // ---- Input: our boxes and sliders first, so typing in them doesn't edit the sign ----

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void signmirrors$keyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (signmirrors$editor != null && signmirrors$editor.keyPressed(event)) cir.setReturnValue(true);
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void signmirrors$charTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (signmirrors$editor != null && signmirrors$editor.charTyped(event)) cir.setReturnValue(true);
    }

    @ModifyVariable(method = "extractSignText", at = @At("STORE"), name = "showCursor")
    private boolean signmirrors$hideCaret(boolean showCursor) {
        return showCursor && !(signmirrors$editor != null && signmirrors$editor.hasFocus());
    }

    // ---- Layout while editing a mirror ----

    @WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
    private boolean signmirrors$title(GuiGraphicsExtractor graphics, Font font, Component title, int x, int y, int color) {
        return !signmirrors$mirrorMode();
    }

    @WrapOperation(method = "extractSign", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;translate(FF)Lorg/joml/Matrix3x2f;", ordinal = 0))
    private Matrix3x2f signmirrors$shrinkSign(Matrix3x2fStack stack, float x, float y, Operation<Matrix3x2f> original) {
        Matrix3x2f result = original.call(stack, x, y);
        if (signmirrors$mirrorMode()) stack.scale(0.6f, 0.6f);
        return result;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // No dimming or blur: the mirror being edited is the preview.
        if (!signmirrors$mirrorMode()) super.extractBackground(graphics, mouseX, mouseY, delta);
    }
}
