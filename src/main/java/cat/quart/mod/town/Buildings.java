package cat.quart.mod.town;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;

/** Construeix una columna d'edifici a partir del ràster (parets, finestres, pisos i teulada). */
public final class Buildings {
    private Buildings() {
    }

    private static final int[][] N4 = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final int[][] D4 = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    public static void column(Sink sink, TownData td, TownData.Building b, int x, int z) {
        int base = b.base;
        int fh = Math.max(2, b.floorHeight);
        int top = b.wallTop();
        BlockState wall = Pal.of(b.wall);
        BlockState accent = b.accent != null ? Pal.of(b.accent) : wall;

        // vores: direccions cap a fora
        int outCount = 0;
        int odx = 0, odz = 0;
        for (int[] o : N4) {
            if (td.buildingId(x + o[0], z + o[1]) != b.id) {
                outCount++;
                odx = o[0];
                odz = o[1];
            }
        }
        if (outCount == 0) {
            // vora només en diagonal (parets girades)
            for (int[] o : D4) {
                if (td.buildingId(x + o[0], z + o[1]) != b.id) {
                    outCount++;
                    odx = o[0];
                    odz = 0;
                }
            }
        }
        boolean edge = outCount > 0;
        int fl = td.flags(x, z);
        boolean corner = (fl & TownData.F_CORNER) != 0;
        if ((fl & TownData.F_HASDIR) != 0) {
            // orientació de la paret segons el polígon real
            int card = (fl >> 4) & 3;
            odx = card == 1 ? 1 : card == 3 ? -1 : 0;
            odz = card == 2 ? 1 : card == 0 ? -1 : 0;
        }

        // fonaments
        for (int y = base - 3; y < base; y++) sink.set(x, y, z, Pal.STONE);
        String rt = b.roofType;

        if (rt.equals("canopy")) {
            sink.set(x, base, z, Blocks.SMOOTH_STONE.getDefaultState());
            for (int y = base + 1; y < top; y++)
                sink.set(x, y, z, edge && Pal.hash(x, z, 1) % 4 == 0 ? Blocks.SPRUCE_FENCE.getDefaultState() : Pal.AIR);
            sink.set(x, top, z, Pal.slab(Blocks.SMOOTH_STONE_SLAB, SlabType.BOTTOM));
            return;
        }
        if (rt.equals("ruin")) {
            sink.set(x, base, z, Pal.GRASS);
            int hh = edge ? 1 + Pal.hash(x, z, 5) % (fh + 1) : 0;
            for (int y = base + 1; y <= base + hh; y++)
                sink.set(x, y, z, Pal.hash(x, y, z) % 3 == 0 ? Blocks.COBBLESTONE.getDefaultState() : wall);
            return;
        }

        sink.set(x, base, z, edge ? wall : Blocks.POLISHED_ANDESITE.getDefaultState());
        int fu = td.facadeU(x, z);
        int u = fu < TownData.FU_NONE ? fu : (odx != 0 ? z : x); // coordenada al llarg de la paret
        Direction out = TownGenerator.dirOf(odx, odz);
        boolean isFacade = b.facade.length > 0 && (fl & TownData.F_FACADE) != 0 && b.facadeWidth > 0;
        boolean door = (fl & TownData.F_DOOR) != 0;
        boolean vault = rt.equals("vault");

        for (int y = base + 1; y <= top; y++) {
            int rel = y - base;
            int k = rel / fh, r = rel % fh;
            BlockState st;
            if (!edge) {
                if (y == top) st = vault ? Pal.AIR : ceiling(b);
                else if (r == 0 && !vault) st = Blocks.SPRUCE_PLANKS.getDefaultState();
                else st = Pal.AIR;
                sink.set(x, y, z, st);
                continue;
            }
            // vora
            if (b.band.length == 2 && y >= top - 4 && y < top) {
                st = Pal.of(b.band[((u + y) & 1)]);
            } else if (r == 0 || y == top) {
                st = (isFacade && facadeChar(b, Math.max(0, k - 1), fu) == '#') || corner ? accent : wall;
            } else if (isFacade) {
                st = facadeBlock(sink, b, wall, accent, facadeChar(b, k, fu), x, y, z, r, fh, k, base, out);
            } else if (corner) {
                st = b.accent != null ? accent : wall;
            } else {
                st = wall;
                boolean winRow = fh <= 3 ? (r >= 1) : (r >= 2 && r <= fh - 1);
                if (k == 0 && door && r <= 2) {
                    st = Pal.door(Blocks.SPRUCE_DOOR, out, r == 2);
                } else if (winRow && Math.floorMod(u, 4) >= 2 && y < top) {
                    st = pane(Blocks.GLASS_PANE, odx);
                }
            }
            sink.set(x, y, z, st);
        }
        roof(sink, td, b, x, z, edge, top, wall);
    }

    private static BlockState ceiling(TownData.Building b) {
        if (b.roofType.equals("flat")) return roofFlat(b);
        return Blocks.SPRUCE_PLANKS.getDefaultState();
    }

    private static BlockState roofFlat(TownData.Building b) {
        String r = b.roof;
        int i = r.indexOf(':');
        if (r.startsWith("flat:") || r.startsWith("vault:")) return Pal.of(r.substring(i + 1));
        return Pal.roofFamily(r)[0];
    }

    private static BlockState pane(Block b, int odx) {
        return odx != 0 ? Pal.connect(b, true, false, true, false) : Pal.connect(b, false, true, false, true);
    }

    static char facadeChar(TownData.Building b, int k, int u) {
        String row = b.facade[Math.min(k, b.facade.length - 1)];
        if (row.isEmpty()) return 'W';
        int i = Math.min(row.length() - 1, u * row.length() / Math.max(1, b.facadeWidth));
        return row.charAt(i);
    }

    private static BlockState facadeBlock(Sink sink, TownData.Building b, BlockState wall, BlockState accent, char c,
                                          int x, int y, int z, int r, int fh, int k, int base, Direction out) {
        int odx = out.getOffsetX(), odz = out.getOffsetZ();
        boolean winRow = fh <= 3 ? r >= 1 : r >= 2;
        switch (c) {
            case 'X', '#':
                return accent;
            case 'w':
                return winRow ? pane(Blocks.GLASS_PANE, odx) : wall;
            case 'x':
                return winRow ? pane(Blocks.GLASS_PANE, odx) : accent;
            case 'S':
                return pane(Blocks.GLASS_PANE, odx);
            case 'P':
                return winRow ? Blocks.LIGHT_GRAY_CONCRETE.getDefaultState() : wall;
            case 'D':
                if (r <= 2) return Pal.door(Blocks.SPRUCE_DOOR, out, r == 2);
                return wall;
            case 'G':
                if (r <= 2 || (fh > 4 && r <= 3))
                    return wall.isOf(Blocks.WHITE_CONCRETE) ? Blocks.IRON_BLOCK.getDefaultState() : Blocks.SMOOTH_QUARTZ.getDefaultState();
                return wall;
            case 'B': {
                if (k >= 1) {
                    // balcó: llosa i barana a la columna de fora
                    int bx = x + odx, bz = z + odz;
                    if (r == 1 && TownData.get().buildingId(bx, bz) != b.id) {
                        sink.set(bx, y - 1, bz, Pal.slab(Blocks.SMOOTH_STONE_SLAB, SlabType.TOP));
                        sink.set(bx, y, bz, odx != 0 ? Pal.connect(Blocks.IRON_BARS, true, false, true, false)
                                : Pal.connect(Blocks.IRON_BARS, false, true, false, true));
                    }
                }
                if (r <= 2) return pane(Blocks.GLASS_PANE, odx);
                return wall;
            }
            default:
                return wall;
        }
    }

    private static void roof(Sink sink, TownData td, TownData.Building b, int x, int z, boolean edge, int top, BlockState wall) {
        String rt = b.roofType;
        if (rt.equals("flat")) {
            if (edge) sink.set(x, top + 1, z, wall);
            return;
        }
        int d = Math.max(1, td.roofDist(x, z));
        if (rt.equals("vault")) {
            BlockState m = roofFlat(b);
            int h = (int) Math.min(9, Math.floor(2.3 * Math.sqrt(d)));
            for (int y = top + 1; y <= top + h; y++) sink.set(x, y, z, m);
            if (!edge) sink.set(x, top, z, Pal.AIR);
            return;
        }
        // teulada a quatre vessants: puja mig bloc per cada bloc cap a dins
        BlockState[] fam = Pal.roofFamily(b.roof);
        int halves = Math.min(d, 9);
        int full = halves / 2;
        for (int i = 1; i <= full; i++) sink.set(x, top + i, z, fam[0]);
        if ((halves & 1) == 1) sink.set(x, top + full + 1, z, fam[1]);
    }
}
