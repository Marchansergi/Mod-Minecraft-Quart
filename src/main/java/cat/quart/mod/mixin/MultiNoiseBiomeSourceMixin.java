package cat.quart.mod.mixin;

import cat.quart.mod.QuartMod;
import cat.quart.mod.town.TownData;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bioma de plana a tota la zona del poble (res de neu, desert ni oceà a Quart). */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
    @Unique
    private volatile RegistryEntry<Biome> quart$plains;
    @Unique
    private volatile boolean quart$checked;

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void quart$biome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise, CallbackInfoReturnable<RegistryEntry<Biome>> cir) {
        if (!QuartMod.enabled()) return;
        if (!quart$checked) {
            for (RegistryEntry<Biome> e : ((MultiNoiseBiomeSource) (Object) this).getBiomes()) {
                if (e.matchesKey(BiomeKeys.PLAINS)) quart$plains = e;
            }
            quart$checked = true;
        }
        RegistryEntry<Biome> plains = quart$plains;
        if (plains == null) return; // no és l'overworld
        TownData td = TownData.get();
        int bx = x << 2, bz = z << 2;
        if (td.inRaster(bx, bz) && td.blend(bx, bz) < 0.7) cir.setReturnValue(plains);
    }
}
