package cat.quart.mod.mixin;

import cat.quart.mod.QuartMod;
import cat.quart.mod.town.TownGenerator;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Després de la superfície vanilla, dibuixa el relleu real de Quart. */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {
    @Inject(method = "buildSurface(Lnet/minecraft/world/ChunkRegion;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/noise/NoiseConfig;Lnet/minecraft/world/chunk/Chunk;)V", at = @At("TAIL"))
    private void quart$terrain(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk, CallbackInfo ci) {
        if (!QuartMod.enabled() || region.toServerWorld().getRegistryKey() != World.OVERWORLD) return;
        TownGenerator.terrain(chunk, ((NoiseChunkGenerator) (Object) this).getSeaLevel());
    }
}
