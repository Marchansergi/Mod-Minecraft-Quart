package cat.quart.mod.town;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/**
 * Arbres mediterranis: pi pinyer, alzina, plàtan, xiprer, olivera i palmera.
 * Dins del nucli es col·loquen on hi ha arbres de veritat (detectats a l'ortofoto PNOA), amb la mida de copa real.
 */
public final class Trees {
    private Trees() {
    }

    /** radi màxim de copa + marge, per saber quins arbres toquen un chunk */
    static final int REACH = 9;

    public static final int STONE_PINE = 0, HOLM_OAK = 1, PLANE = 2, CYPRESS = 3, OLIVE = 4, PALM = 5;

    public static void place(Sink sink, TownData td) {
        // arbres reals de l'ortofoto
        for (TownData.Tree t : td.treesNear(sink.minX() >> 4, sink.minZ() >> 4)) {
            if (td.buildingId(t.x(), t.z()) != 0 || nearDecoration(td, t.x(), t.z())) continue;
            grow(sink, td, t.kind(), t.x(), td.height(t.x(), t.z()), t.z(), t.radius(), Pal.hash(t.x(), t.z(), 1234));
        }
        // places amb arbrat que l'ortofoto no distingeix bé (copes petites sobre sauló)
        for (int z = sink.minZ() - 4; z < sink.minZ() + 20; z++) {
            for (int x = sink.minX() - 4; x < sink.minX() + 20; x++) {
                if (!td.inCore(x, z) || td.surface(x, z) != Surf.PLAZA_TREES || td.buildingId(x, z) != 0) continue;
                int h = Pal.hash(x, z, 1234);
                if (h % 70 != 0 || nearBuilding(td, x, z) || nearDecoration(td, x, z)) continue;
                grow(sink, td, PLANE, x, td.height(x, z), z, 2.5, h);
            }
        }
    }

    static void grow(Sink sink, TownData td, int kind, int x, int g, int z, double r, int h) {
        switch (kind) {
            case STONE_PINE -> stonePine(sink, td, x, g, z, r, h);
            case HOLM_OAK -> holmOak(sink, td, x, g, z, r, h);
            case PLANE -> planeTree(sink, td, x, g, z, r, h);
            case CYPRESS -> cypress(sink, td, x, g, z, h);
            case OLIVE -> olive(sink, td, x, g, z, h);
            default -> palm(sink, td, x, g, z, h);
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
    private static void stonePine(Sink sink, TownData td, int x, int g, int z, double r, int h) {
        int ht = (int) Math.round(5 + r * 1.2) + h % 2;
        BlockState log = Blocks.SPRUCE_LOG.getDefaultState();
        trunk(sink, x, g, z, ht, log);
        BlockState lv = Pal.leaves(Blocks.OAK_LEAVES);
        blob(sink, td, x, g + ht + 1, z, Math.max(2.0, r), 1.2 + r * 0.12, lv, h);
        int ox = ((h >>> 3) & 1) == 0 ? 1 : -1;
        sink.set(x + ox, g + ht, z, log);
    }

    /** alzina: tronc curt, copa rodona i fosca */
    private static void holmOak(Sink sink, TownData td, int x, int g, int z, double r, int h) {
        int ht = (int) Math.round(1.5 + r * 0.6);
        trunk(sink, x, g, z, ht + 1, Blocks.DARK_OAK_LOG.getDefaultState());
        blob(sink, td, x, g + ht + (int) Math.ceil(r * 0.7), z, Math.max(1.5, r), Math.max(1.5, r * 0.8),
                Pal.leaves(Blocks.DARK_OAK_LEAVES), h);
    }

    /** plàtan de plaça */
    static void planeTree(Sink sink, TownData td, int x, int g, int z, double r, int h) {
        int ht = (int) Math.round(3 + r * 0.8);
        trunk(sink, x, g, z, ht + 2, Blocks.BIRCH_LOG.getDefaultState());
        blob(sink, td, x, g + ht + 2, z, Math.max(2.0, r), Math.max(1.8, r * 0.8), Pal.leaves(Blocks.OAK_LEAVES), h);
    }

    /** compatibilitat amb la decoració (parc de bancs) */
    static void planeTree(Sink sink, TownData td, int x, int g, int z, int h) {
        planeTree(sink, td, x, g, z, 3.0, h);
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
