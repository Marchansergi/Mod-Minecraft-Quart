package cat.quart.mod.town;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

/**
 * Escriptura de blocs retallada al chunk que s'està generant. Tot el que es genera és
 * determinista a partir del ràster, de manera que cada chunk només escriu dins seu i el
 * resultat és el mateix sigui quin sigui l'ordre de generació.
 */
public final class Sink {
    private final Chunk chunk;
    private final int minX, minZ, bottomY, topY;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    public Sink(Chunk chunk) {
        this.chunk = chunk;
        this.minX = chunk.getPos().getStartX();
        this.minZ = chunk.getPos().getStartZ();
        this.bottomY = chunk.getBottomY();
        this.topY = chunk.getTopY();
    }

    public int minX() {
        return minX;
    }

    public int minZ() {
        return minZ;
    }

    public boolean contains(int x, int z) {
        return x >= minX && z >= minZ && x < minX + 16 && z < minZ + 16;
    }

    public void set(int x, int y, int z, BlockState s) {
        if (!contains(x, z) || y < bottomY || y >= topY) return;
        chunk.setBlockState(pos.set(x, y, z), s, false);
    }

    /** només posa el bloc si hi ha aire (per a fulles i plantes) */
    public void setIfAir(int x, int y, int z, BlockState s) {
        if (!contains(x, z) || y < bottomY || y >= topY) return;
        pos.set(x, y, z);
        if (chunk.getBlockState(pos).isAir()) chunk.setBlockState(pos, s, false);
    }

    public BlockState get(int x, int y, int z) {
        if (!contains(x, z) || y < bottomY || y >= topY) return Blocks.AIR.getDefaultState();
        return chunk.getBlockState(pos.set(x, y, z));
    }
}
