package com.ae2vm.addon.coremod;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.Map;

/**
 * Legacy Mixin coremod for Forge 1.10.2 (Mixin 0.7.x).
 *
 * <p>Forge 1.10.2 has no native mixin support, so Sponge Mixin is bootstrapped from an
 * {@link IFMLLoadingPlugin} loaded via {@code -Dfml.coreMods.load=com.ae2vm.addon.coremod.AE2VMCoreMod}
 * (and the {@code FMLCorePlugin} manifest attribute for the built jar).
 */
public class AE2VMCoreMod implements IFMLLoadingPlugin {

    public AE2VMCoreMod() {
        MixinBootstrap.init();
        Mixins.addConfiguration("ae2vm.mixins.json");
        // Forge 1.10.2 runtime is SRG-named ("searge" context).
        MixinEnvironment.getDefaultEnvironment().setObfuscationContext("searge");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
