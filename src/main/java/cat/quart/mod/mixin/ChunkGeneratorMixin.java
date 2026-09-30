package cat.quart.mod.mixin;

import cat.quart.mod.QuartMod;
import cat.quart.mod.town.TownData;
import cat.quart.mod.town.TownGenerator;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
    /** Dins del poble no hi van arbres, llacs ni flors vanilla: hi posem el poble. */
    @Inject(method = "generateFeatures", at = @At("HEAD"), cancellable = true)
    private void quart$features(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor, CallbackInfo ci) {
        if (!QuartMod.enabled() || !((Object) this instanceof NoiseChunkGenerator)) return;
        if (world.toServerWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;
        if (TownData.get().chunkInCore(chunk.getPos().x, chunk.getPos().z)) {
            TownGenerator.decorate(chunk);
            ci.cancel();
        }
    }

    /** Cap poblat, temple ni mina vanilla a sobre ni a prop de Quart. */
    @Inject(method = "setStructureStarts", at = @At("HEAD"), cancellable = true)
    private void quart$noStructures(DynamicRegistryManager registryManager, StructurePlacementCalculator placementCalculator,
                                    StructureAccessor structureAccessor, Chunk chunk, StructureTemplateManager templateManager, CallbackInfo ci) {
        if (!QuartMod.enabled() || !((Object) this instanceof NoiseChunkGenerator)) return;
        if (!QuartMod.isOverworldGenerator((ChunkGenerator) (Object) this)) return;
        if (TownData.get().chunkTouchesRaster(chunk.getPos().x, chunk.getPos().z, 160)) ci.cancel();
    }
}
