package cat.quart.mod.town;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** Arbres mediterranis: pi pinyer, alzina, plàtan, xiprer, olivera i palmera. */
public final class Trees {
    private Trees() {
    }

    private static final int R = 6;

    public static void place(Sink sink, TownData td) {
        for (int z = sink.minZ() - R; z < sink.minZ() + 16 + R; z++) {
            for (int x = sink.minX() - R; x < sink.minX() + 16 + R; x++) {
                if (!td.inCore(x, z) || td.buildingId(x, z) != 0 || td.deco(x, z) != 0) continue;
                int s = td.surface(x, z);
                int h = Pal.hash(x, z, 1234);
                int kind = -1;
                switch (s) {
                    case Surf.FOREST -> {
                        if (h % 24 == 0) kind = (h >>> 8) % 100 < 45 ? 0 : 1;
                    }
                    case Surf.PARK -> {
                        if (h % 60 == 0) kind = new int[]{0, 1, 2, 2}[(h >>> 8) % 4];
                    }
                    case Surf.PLAZA_TREES -> {
                        if (h % 35 == 0) kind = 2;
                    }
                    case Surf.GARDEN -> {
                        if (h % 130 == 0) kind = new int[]{3, 3, 1, 4, 4, 5}[(h >>> 8) % 6];
                    }
                    case Surf.SHRUB -> {
                        if (h % 70 == 0) kind = 1;
                    }
                    case Surf.GRASS, Surf.MEADOW -> {
                        if (h % 450 == 0) kind = 1;
                    }
                    default -> {
                    }
                }
                if (kind < 0 || nearBuilding(td, x, z) || nearDecoration(td, x, z)) continue;
                int g = td.height(x, z);
                switch (kind) {
                    case 0 -> stonePine(sink, td, x, g, z, h);
                    case 1 -> holmOak(sink, td, x, g, z, h);
                    case 2 -> planeTree(sink, td, x, g, z, h);
                    case 3 -> cypress(sink, td, x, g, z, h);
                    case 4 -> olive(sink, td, x, g, z, h);
                    default -> palm(sink, td, x, g, z, h);
                }
            }
        }
    }

    private static boolean nearDecoration(TownData td, int x, int z) {
        for (TownData.Decoration d : td.decorations) if (d.blocksTrees(x, z)) return true;
        return false;
    }

    private static boolean nearBuilding(TownData td, int x, int z) {
        for (int dz = -2; dz <= 2; dz++)
            for (int dx = -2; dx <= 2; dx++)
                if (td.buildingId(x + dx, z + dz) != 0) return true;
        return false;
    }

    private static void trunk(Sink sink, int x, int g, int z, int height, BlockState log) {
        for (int y = 1; y <= height; y++) sink.set(x, g + y, z, log);
    }

    private static void leaf(Sink sink, TownData td, int x, int y, int z, BlockState leaves) {
        if (td.buildingId(x, z) != 0) return;
        sink.setIfAir(x, y, z, leaves);
    }

    private static void blob(Sink sink, TownData td, int x, int y, int z, double rx, double ry, BlockState leaves, int seed) {
        int irx = (int) Math.ceil(rx), iry = (int) Math.ceil(ry);
        for (int dy = -iry; dy <= iry; dy++)
            for (int dz = -irx; dz <= irx; dz++)
                for (int dx = -irx; dx <= irx; dx++) {
                    double v = (dx * dx + dz * dz) / (rx * rx) + (dy * dy) / (ry * ry);
                    if (v <= 1.0 && !(v > 0.7 && Pal.hash(x + dx, (y + dy) * 31 + z + dz, seed) % 3 == 0))
                        leaf(sink, td, x + dx, y + dy, z + dz, leaves);
                }
    }

    /** pi pinyer: tronc alt i nu, copa plana en forma de para-sol */
    private static void stonePine(Sink sink, TownData td, int x, int g, int z, int h) {
        int ht = 7 + h % 4;
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState();
        trunk(sink, x, g, z, ht, log);
        BlockState lv = Pal.leaves(Blocks.OAK_LEAVES);
        blob(sink, td, x, g + ht + 1, z, 3.6 + (h >>> 5) % 2, 1.3, lv, h);
        // branques
        int ox = ((h >>> 3) & 1) == 0 ? 1 : -1;
        sink.set(x + ox, g + ht, z, log);
    }

    /** alzina: tronc curt, copa rodona i fosca */
    private static void holmOak(Sink sink, TownData td, int x, int g, int z, int h) {
        int ht = 2 + h % 3;
        trunk(sink, x, g, z, ht + 1, Blocks.DARK_OAK_LOG.getDefaultState());
        blob(sink, td, x, g + ht + 2, z, 2.6 + (h >>> 6) % 2, 2.2, Pal.leaves(Blocks.DARK_OAK_LEAVES), h);
    }

    /** plàtan de plaça */
    static void planeTree(Sink sink, TownData td, int x, int g, int z, int h) {
        int ht = 4 + h % 3;
        trunk(sink, x, g, z, ht + 2, Blocks.BIRCH_LOG.getDefaultState());
        blob(sink, td, x, g + ht + 2, z, 3.2, 2.6, Pal.leaves(Blocks.OAK_LEAVES), h);
    }

    /** xiprer de jardí */
    private static void cypress(Sink sink, TownData td, int x, int g, int z, int h) {
        int ht = 6 + h % 4;
        BlockState lv = Pal.leaves(Blocks.SPRUCE_LEAVES);
        trunk(sink, x, g, z, ht - 1, Blocks.SPRUCE_LOG.getDefaultState());
        for (int y = 1; y <= ht + 1; y++) {
            leaf(sink, td, x, g + y, z, lv);
            if (y >= 2 && y <= ht - 1) {
                leaf(sink, td, x + 1, g + y, z, lv);
                leaf(sink, td, x - 1, g + y, z, lv);
                leaf(sink, td, x, g + y, z + 1, lv);
                leaf(sink, td, x, g + y, z - 1, lv);
            }
        }
    }

    /** olivera: petita i platejada */
    public static void olive(Sink sink, TownData td, int x, int g, int z, int h) {
        trunk(sink, x, g, z, 2, Blocks.OAK_LOG.getDefaultState());
        blob(sink, td, x, g + 3, z, 2.2, 1.5, Pal.leaves(Blocks.AZALEA_LEAVES), h);
    }

    private static void palm(Sink sink, TownData td, int x, int g, int z, int h) {
        int ht = 6 + h % 3;
        trunk(sink, x, g, z, ht, Blocks.JUNGLE_LOG.getDefaultState());
        BlockState lv = Pal.leaves(Blocks.JUNGLE_LEAVES);
        int y = g + ht + 1;
        leaf(sink, td, x, y, z, lv);
        for (int i = 1; i <= 3; i++) {
            int yy = i == 3 ? y - 1 : y;
            leaf(sink, td, x + i, yy, z, lv);
            leaf(sink, td, x - i, yy, z, lv);
            leaf(sink, td, x, yy, z + i, lv);
            leaf(sink, td, x, yy, z - i, lv);
        }
    }
}
