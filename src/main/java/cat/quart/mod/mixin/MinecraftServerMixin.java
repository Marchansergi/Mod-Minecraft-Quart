package cat.quart.mod.mixin;

import cat.quart.mod.QuartMod;
import cat.quart.mod.town.TownData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.level.ServerWorldProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** El punt d'aparició és davant de l'Estació de Quart. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "setupSpawn", at = @At("TAIL"))
    private static void quart$spawn(ServerWorld world, ServerWorldProperties props, boolean bonusChest, boolean debugWorld, CallbackInfo ci) {
        if (!QuartMod.enabled() || debugWorld || !(world.getChunkManager().getChunkGenerator() instanceof NoiseChunkGenerator)) return;
        TownData td = TownData.get();
        props.setSpawnPos(new BlockPos(td.spawnX, td.spawnY, td.spawnZ), 150.0f);
        world.getGameRules().get(GameRules.SPAWN_RADIUS).set(0, world.getServer());
        QuartMod.LOGGER.info("Quart: punt d'aparició a l'Estació ({}, {}, {})", td.spawnX, td.spawnY, td.spawnZ);
    }
}
