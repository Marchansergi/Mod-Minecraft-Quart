package cat.quart.mod;

import cat.quart.mod.town.TownData;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuartMod implements ModInitializer {
    public static final String MOD_ID = "quartmod";
    public static final Logger LOGGER = LoggerFactory.getLogger("Quart");

    private static boolean enabled = true;

    @Override
    public void onInitialize() {
        long t = System.currentTimeMillis();
        try {
            TownData td = TownData.get();
            LOGGER.info("Quart: poble carregat ({} x {} blocs, {} edificis) en {} ms", td.w, td.h, td.buildings.size() - 1,
                    System.currentTimeMillis() - t);
        } catch (RuntimeException e) {
            enabled = false;
            LOGGER.error("Quart: no s'han pogut carregar les dades; el mod queda desactivat", e);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /** generador de soroll amb la configuració de l'overworld (normal, amplificat o grans biomes) */
    public static boolean isOverworldGenerator(ChunkGenerator gen) {
        return gen instanceof NoiseChunkGenerator n && (n.matchesSettings(ChunkGeneratorSettings.OVERWORLD)
                || n.matchesSettings(ChunkGeneratorSettings.AMPLIFIED) || n.matchesSettings(ChunkGeneratorSettings.LARGE_BIOMES));
    }
}
