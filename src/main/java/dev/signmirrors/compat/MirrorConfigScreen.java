package dev.signmirrors.compat;

import dev.signmirrors.SignMirrorsClient;
import dev.signmirrors.config.MirrorConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Cloth Config screen for {@link MirrorConfig}. Only loaded when Cloth Config is present. */
final class MirrorConfigScreen {
    private static final int[] RESOLUTIONS = {16, 32, 64, 128, 256, 512, 1024, 2048};

    private MirrorConfigScreen() {}

    static Screen create(Screen parent) {
        MirrorConfig cfg = SignMirrorsClient.config;
        MirrorConfig defaults = new MirrorConfig();
        MirrorConfig.Preset presetWhenOpened = cfg.preset;
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(t("title"))
                .setSavingRunnable(() -> {
                    // Picking a different preset applies it; editing individual settings while the
                    // preset stayed the same switches to Custom so those edits are kept.
                    if (cfg.preset == presetWhenOpened && !cfg.matchesPreset(cfg.preset)) {
                        cfg.preset = MirrorConfig.Preset.CUSTOM;
                    }
                    cfg.applyPresetAndSanitize();
                    cfg.save();
                });
        ConfigEntryBuilder e = builder.entryBuilder();

        // ---- General ----
        ConfigCategory general = builder.getOrCreateCategory(t("category.general"));
        general.addEntry(e.startBooleanToggle(t("enabled"), cfg.enabled)
                .setDefaultValue(true).setTooltip(t("enabled.tooltip"))
                .setSaveConsumer(v -> cfg.enabled = v).build());
        general.addEntry(e.startEnumSelector(t("preset"), MirrorConfig.Preset.class, cfg.preset)
                .setDefaultValue(defaults.preset)
                .setEnumNameProvider(p -> t("preset." + p.name().toLowerCase()))
                .setTooltip(t("preset.tooltip"))
                .setSaveConsumer(v -> cfg.preset = v).build());
        general.addEntry(e.startTextDescription(t("preset.note")).build());

        // ---- Quality ----
        ConfigCategory quality = builder.getOrCreateCategory(t("category.quality"));
        quality.addEntry(e.startIntSlider(t("minResolution"), indexOf(cfg.minResolution), 0, RESOLUTIONS.length - 1)
                .setDefaultValue(indexOf(defaults.minResolution))
                .setTextGetter(i -> Component.literal(RESOLUTIONS[i] + " px"))
                .setTooltip(t("minResolution.tooltip"))
                .setSaveConsumer(i -> cfg.minResolution = RESOLUTIONS[i]).build());
        quality.addEntry(e.startIntSlider(t("maxResolution"), indexOf(cfg.maxResolution), 0, RESOLUTIONS.length - 1)
                .setDefaultValue(indexOf(defaults.maxResolution))
                .setTextGetter(i -> Component.literal(RESOLUTIONS[i] + " px"))
                .setTooltip(t("maxResolution.tooltip"))
                .setSaveConsumer(i -> cfg.maxResolution = RESOLUTIONS[i]).build());
        quality.addEntry(e.startIntSlider(t("resolutionScale"), Math.round(cfg.resolutionScale * 100), 10, 400)
                .setDefaultValue(Math.round(defaults.resolutionScale * 100))
                .setTextGetter(i -> Component.literal(i + "%"))
                .setTooltip(t("resolutionScale.tooltip"))
                .setSaveConsumer(i -> cfg.resolutionScale = i / 100f).build());
        quality.addEntry(e.startBooleanToggle(t("refreshHiddenFaces"), cfg.refreshHiddenFaces)
                .setDefaultValue(defaults.refreshHiddenFaces).setTooltip(t("refreshHiddenFaces.tooltip"))
                .setSaveConsumer(v -> cfg.refreshHiddenFaces = v).build());
        quality.addEntry(e.startBooleanToggle(t("recursiveMirrors"), cfg.recursiveMirrors)
                .setDefaultValue(defaults.recursiveMirrors).setTooltip(t("recursiveMirrors.tooltip"))
                .setSaveConsumer(v -> cfg.recursiveMirrors = v).build());
        quality.addEntry(e.startIntSlider(t("reflectivity"), Math.round(cfg.reflectivity * 100), 0, 150)
                .setDefaultValue(Math.round(defaults.reflectivity * 100))
                .setTextGetter(i -> Component.literal(i + "%"))
                .setTooltip(t("reflectivity.tooltip"))
                .setSaveConsumer(i -> cfg.reflectivity = i / 100f).build());

        // ---- Performance ----
        ConfigCategory perf = builder.getOrCreateCategory(t("category.performance"));
        perf.addEntry(e.startIntSlider(t("facesPerFrame"), cfg.facesPerFrame, 0, 30)
                .setDefaultValue(defaults.facesPerFrame).setTooltip(t("facesPerFrame.tooltip"))
                .setSaveConsumer(v -> cfg.facesPerFrame = v).build());
        perf.addEntry(e.startIntSlider(t("maxActiveMirrors"), cfg.maxActiveMirrors, 1, 32)
                .setDefaultValue(defaults.maxActiveMirrors).setTooltip(t("maxActiveMirrors.tooltip"))
                .setSaveConsumer(v -> cfg.maxActiveMirrors = v).build());
        perf.addEntry(e.startIntSlider(t("maxMirrorDistance"), (int) Math.round(cfg.maxMirrorDistance), 4, 256)
                .setDefaultValue((int) Math.round(defaults.maxMirrorDistance))
                .setTextGetter(i -> Component.literal(i + " blocks"))
                .setTooltip(t("maxMirrorDistance.tooltip"))
                .setSaveConsumer(v -> cfg.maxMirrorDistance = v).build());

        // ---- Culling ----
        ConfigCategory culling = builder.getOrCreateCategory(t("category.culling"));
        culling.addEntry(e.startBooleanToggle(t("frustumCulling"), cfg.frustumCulling)
                .setDefaultValue(defaults.frustumCulling).setTooltip(t("frustumCulling.tooltip"))
                .setSaveConsumer(v -> cfg.frustumCulling = v).build());
        culling.addEntry(e.startBooleanToggle(t("occlusionCulling"), cfg.occlusionCulling)
                .setDefaultValue(defaults.occlusionCulling).setTooltip(t("occlusionCulling.tooltip"))
                .setSaveConsumer(v -> cfg.occlusionCulling = v).build());
        culling.addEntry(e.startIntSlider(t("occlusionCheckInterval"), cfg.occlusionCheckInterval, 1, 60)
                .setDefaultValue(defaults.occlusionCheckInterval).setTooltip(t("occlusionCheckInterval.tooltip"))
                .setSaveConsumer(v -> cfg.occlusionCheckInterval = v).build());
        culling.addEntry(e.startIntSlider(t("visibilityGraceFrames"), cfg.visibilityGraceFrames, 0, 120)
                .setDefaultValue(defaults.visibilityGraceFrames).setTooltip(t("visibilityGraceFrames.tooltip"))
                .setSaveConsumer(v -> cfg.visibilityGraceFrames = v).build());

        // ---- Advanced ----
        ConfigCategory adv = builder.getOrCreateCategory(t("category.advanced"));
        adv.addEntry(e.startBooleanToggle(t("disableOcclusionCullingDuringCapture"), cfg.disableOcclusionCullingDuringCapture)
                .setDefaultValue(defaults.disableOcclusionCullingDuringCapture)
                .setTooltip(t("disableOcclusionCullingDuringCapture.tooltip"))
                .setSaveConsumer(v -> cfg.disableOcclusionCullingDuringCapture = v).build());
        adv.addEntry(e.startIntSlider(t("proxyMaxDistance"), (int) Math.round(cfg.proxyMaxDistance), 2, 256)
                .setDefaultValue((int) Math.round(defaults.proxyMaxDistance))
                .setTextGetter(i -> Component.literal(i + " blocks"))
                .setTooltip(t("proxyMaxDistance.tooltip"))
                .setSaveConsumer(v -> cfg.proxyMaxDistance = v).build());
        adv.addEntry(e.startIntSlider(t("probeOffset"), (int) Math.round(cfg.probeOffset * 100), 5, 400)
                .setDefaultValue((int) Math.round(defaults.probeOffset * 100))
                .setTextGetter(i -> Component.literal(String.format("%.2f blocks", i / 100.0)))
                .setTooltip(t("probeOffset.tooltip"))
                .setSaveConsumer(v -> cfg.probeOffset = v / 100.0).build());
        adv.addEntry(e.startBooleanToggle(t("flipFaceV"), cfg.flipFaceV)
                .setDefaultValue(defaults.flipFaceV).setTooltip(t("flipFaceV.tooltip"))
                .setSaveConsumer(v -> cfg.flipFaceV = v).build());

        return builder.build();
    }

    private static Component t(String key) {
        return Component.translatable("signmirrors.config." + key);
    }

    private static int indexOf(int resolution) {
        for (int i = 0; i < RESOLUTIONS.length; i++) if (RESOLUTIONS[i] >= resolution) return i;
        return RESOLUTIONS.length - 1;
    }
}
