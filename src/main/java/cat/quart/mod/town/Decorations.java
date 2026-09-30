package cat.quart.mod.town;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChainBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.util.math.Direction;

/** Elements especials: la locomotora de l'estació, la xemeneia del museu, bancs, tirolina... */
public final class Decorations {
    private Decorations() {
    }

    public static void place(Sink sink, TownData td) {
        for (TownData.Decoration d : td.decorations) {
            int r = d.radius();
            if (d.x + r < sink.minX() || d.x - r >= sink.minX() + 16 || d.z + r < sink.minZ() || d.z - r >= sink.minZ() + 16)
                continue;
            Frame f = new Frame(sink, td, d.x, d.y, d.z, d.yaw);
            switch (d.type) {
                case "locomotive" -> locomotive(f);
                case "benches" -> benches(f, d.count, false);
                case "benches_plaza" -> benches(f, d.count, true);
                case "chimney" -> chimney(sink, d.x, d.y, d.z, d.height);
                case "sculpture" -> sculpture(f);
                case "zipline" -> zipline(f, d.length);
                case "playground" -> playground(f);
                case "goal" -> goal(f);
                case "hoop" -> hoop(f);
                default -> {
                }
            }
        }
    }

    /** Sistema de coordenades local: a = endavant (yaw), b = dreta. Gir arrodonit a 90 graus. */
    static final class Frame {
        final Sink sink;
        final TownData td;
        final int x, y, z;
        final int ux, uz, vx, vz;
        final Direction fwd, right;

        Frame(Sink sink, TownData td, int x, int y, int z, int yaw) {
            this.sink = sink;
            this.td = td;
            this.x = x;
            this.y = y;
            this.z = z;
            int r = Math.floorMod(Math.round(yaw / 90f), 4);
            int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            ux = dirs[r][0];
            uz = dirs[r][1];
            vx = dirs[(r + 1) % 4][0];
            vz = dirs[(r + 1) % 4][1];
            fwd = TownGenerator.dirOf(ux, uz);
            right = TownGenerator.dirOf(vx, vz);
        }

        int wx(int a, int b) {
            return x + a * ux + b * vx;
        }

        int wz(int a, int b) {
            return z + a * uz + b * vz;
        }

        void set(int a, int dy, int b, BlockState s) {
            sink.set(wx(a, b), y + dy, wz(a, b), s);
        }

        /** alçada del terra real en aquest punt */
        int ground(int a, int b) {
            int X = wx(a, b), Z = wz(a, b);
            return td.inRaster(X, Z) ? td.height(X, Z) : y;
        }

        void setG(int a, int dy, int b, BlockState s) {
            sink.set(wx(a, b), ground(a, b) + dy, wz(a, b), s);
        }
    }

    // ---------------------------------------------------------------- locomotora de vapor sobre pedestal
    private static void locomotive(Frame f) {
        BlockState brick = Blocks.BRICKS.getDefaultState();
        BlockState black = Blocks.BLACK_CONCRETE.getDefaultState();
        BlockState dark = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        BlockState red = Blocks.RED_CONCRETE.getDefaultState();
        // pedestal de maó
        for (int a = -1; a <= 10; a++)
            for (int b = -2; b <= 2; b++) {
                f.set(a, 1, b, brick);
                f.set(a, 0, b, brick);
            }
        // via
        for (int a = -1; a <= 10; a++) {
            f.set(a, 2, -1, Blocks.IRON_BLOCK.getDefaultState());
            f.set(a, 2, 1, Blocks.IRON_BLOCK.getDefaultState());
            f.set(a, 2, 0, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        // rodes vermelles
        for (int a : new int[]{1, 3, 5, 8}) {
            f.set(a, 3, -1, red);
            f.set(a, 3, 1, red);
        }
        // xassís
        for (int a = 0; a <= 9; a++) f.set(a, 3, 0, dark);
        // caldera
        for (int a = 0; a <= 6; a++)
            for (int b = -1; b <= 1; b++) {
                f.set(a, 4, b, black);
                if (b == 0 || a % 2 == 0) f.set(a, 5, b, black);
            }
        f.set(-1, 4, 0, dark);
        f.set(-1, 3, -1, Blocks.POLISHED_BLACKSTONE_BUTTON.getDefaultState());
        // xemeneia i cúpula
        f.set(1, 6, 0, black);
        f.set(1, 7, 0, black);
        f.set(4, 6, 0, Blocks.POLISHED_BLACKSTONE_SLAB.getDefaultState());
        // cabina
        for (int a = 7; a <= 9; a++)
            for (int b = -1; b <= 1; b++)
                for (int dy = 4; dy <= 6; dy++) {
                    boolean window = dy == 5 && (b != 0 || a == 8) && !(a == 8 && b == 0);
                    f.set(a, dy, b, window ? Blocks.BLACK_STAINED_GLASS_PANE.getDefaultState() : black);
                }
        for (int a = 6; a <= 10; a++)
            for (int b = -1; b <= 1; b++) f.set(a, 7, b, Pal.slab(Blocks.BLACKSTONE_SLAB, SlabType.BOTTOM));
    }

    // ---------------------------------------------------------------- bancs
    private static void benches(Frame f, int count, boolean plaza) {
        BlockState seat = Pal.stairs(Blocks.SPRUCE_STAIRS, f.right);
        for (int i = 0; i < count; i++) {
            int a = i * 4;
            if (plaza) {
                // zona pavimentada amb oliveres entre els bancs (la "zona dels bancs" del Local social)
                for (int aa = a - 2; aa <= a + 1; aa++)
                    for (int b = -2; b <= 2; b++) f.setG(aa, 0, b, ((aa + b) & 1) == 0
                            ? Blocks.WHITE_TERRACOTTA.getDefaultState() : Blocks.PINK_TERRACOTTA.getDefaultState());
            }
            f.setG(a, 1, 0, seat);
            f.setG(a - 1, 1, 0, seat);
            if (plaza && i < count - 1) {
                int X = f.wx(a + 2, -2), Z = f.wz(a + 2, -2);
                int g = f.ground(a + 2, -2);
                f.sink.set(X, g, Z, Blocks.COARSE_DIRT.getDefaultState());
                Trees.olive(f.sink, f.td, X, g, Z, Pal.hash(X, Z, 3));
            }
        }
        if (plaza) {
            // barana verda al costat del carrer
            for (int a = -3; a <= count * 4; a++) f.setG(a, 1, 3, fenceAlong(f));
        }
    }

    private static BlockState fenceAlong(Frame f) {
        // la barana va al llarg de la direcció "endavant"
        boolean alongX = f.ux != 0;
        return Pal.connect(Blocks.WARPED_FENCE, !alongX, alongX, !alongX, alongX);
    }

    // ---------------------------------------------------------------- xemeneia de la bòbila (Museu de la Terrissa)
    private static void chimney(Sink sink, int x, int y, int z, int height) {
        BlockState brick = Blocks.BRICKS.getDefaultState();
        BlockState dark = Blocks.MUD_BRICKS.getDefaultState();
        for (int dy = 0; dy <= height; dy++) {
            double r = dy < 3 ? 2.2 : 1.6 - 0.5 * dy / height;
            int ir = (int) Math.ceil(r);
            for (int dz = -ir; dz <= ir; dz++)
                for (int dx = -ir; dx <= ir; dx++) {
                    double dd = Math.sqrt(dx * dx + dz * dz);
                    if (dd <= r + 0.35) {
                        boolean shell = dd > r - 0.9;
                        BlockState s = shell ? (dy >= height - 1 ? dark : brick) : (dy >= height - 2 ? Pal.AIR : brick);
                        sink.set(x + dx, y + dy, z + dz, s);
                    }
                }
        }
    }

    // ---------------------------------------------------------------- escultura d'acer corten
    private static void sculpture(Frame f) {
        f.setG(0, 1, 0, Blocks.SMOOTH_STONE.getDefaultState());
        f.setG(1, 1, 0, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState rust = Blocks.TERRACOTTA.getDefaultState();
        BlockState rust2 = Blocks.BROWN_TERRACOTTA.getDefaultState();
        f.setG(0, 2, 0, rust);
        f.setG(0, 3, 0, rust2);
        f.setG(1, 3, 0, rust);
        f.setG(0, 4, 0, rust);
        f.setG(1, 4, 0, rust2);
        f.setG(1, 5, 0, rust);
    }

    // ---------------------------------------------------------------- tirolina del parc
    private static void zipline(Frame f, int length) {
        BlockState post = Blocks.SPRUCE_LOG.getDefaultState();
        int h0 = 6, h1 = 3;
        for (int dy = 1; dy <= h0; dy++) f.setG(0, dy, 0, post);
        for (int dy = 1; dy <= h1; dy++) f.setG(length, dy, 0, post);
        // plataforma de sortida
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++)
                if (a != 0 || b != 0) f.setG(a, 3, b, Blocks.SPRUCE_SLAB.getDefaultState());
        f.setG(-1, 1, 0, Pal.stairs(Blocks.SPRUCE_STAIRS, f.fwd));
        f.setG(-1, 2, 1, Pal.stairs(Blocks.SPRUCE_STAIRS, f.fwd));
        int g0 = f.ground(0, 0), g1 = f.ground(length, 0);
        int y0 = g0 + h0, y1 = g1 + h1;
        BlockState chain = Blocks.CHAIN.getDefaultState().with(ChainBlock.AXIS, f.ux != 0 ? Direction.Axis.X : Direction.Axis.Z);
        for (int a = 1; a < length; a++) {
            int yy = (int) Math.round(y0 + (y1 - y0) * (a / (double) length));
            f.sink.set(f.wx(a, 0), yy, f.wz(a, 0), chain);
        }
        // seient
        int a = length / 3;
        int yy = (int) Math.round(y0 + (y1 - y0) * (a / (double) length));
        f.sink.set(f.wx(a, 0), yy - 1, f.wz(a, 0), Blocks.CHAIN.getDefaultState());
        f.sink.set(f.wx(a, 0), yy - 2, f.wz(a, 0), Blocks.RED_CARPET.getDefaultState());
    }

    // ---------------------------------------------------------------- parc infantil
    private static void playground(Frame f) {
        for (int a = -5; a <= 5; a++)
            for (int b = -4; b <= 4; b++) f.setG(a, 0, b, Blocks.SAND.getDefaultState());
        // gronxador
        BlockState post = Blocks.SPRUCE_FENCE.getDefaultState();
        for (int dy = 1; dy <= 3; dy++) {
            f.setG(-4, dy, -3, post);
            f.setG(0, dy, -3, post);
        }
        for (int a = -4; a <= 0; a++) f.setG(a, 4, -3, Blocks.SPRUCE_LOG.getDefaultState().with(net.minecraft.block.PillarBlock.AXIS,
                f.ux != 0 ? Direction.Axis.X : Direction.Axis.Z));
        for (int a : new int[]{-3, -1}) {
            f.setG(a, 3, -3, Blocks.CHAIN.getDefaultState());
            f.setG(a, 2, -3, Blocks.CHAIN.getDefaultState());
            f.setG(a, 1, -3, Pal.slab(Blocks.SPRUCE_SLAB, SlabType.TOP));
        }
        // tobogan
        f.setG(2, 1, 2, Blocks.RED_CONCRETE.getDefaultState());
        f.setG(2, 2, 2, Blocks.RED_CONCRETE.getDefaultState());
        f.setG(2, 3, 2, Blocks.YELLOW_CONCRETE.getDefaultState());
        f.setG(3, 3, 2, Pal.slab(Blocks.SPRUCE_SLAB, SlabType.BOTTOM));
        f.setG(3, 2, 2, Pal.stairs(Blocks.CUT_COPPER_STAIRS, f.fwd.getOpposite()));
        f.setG(4, 1, 2, Pal.stairs(Blocks.CUT_COPPER_STAIRS, f.fwd.getOpposite()));
        f.setG(1, 1, 2, Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, f.fwd.getOpposite()));
        f.setG(1, 2, 2, Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, f.fwd.getOpposite()));
        f.setG(-3, 1, 3, Blocks.GREEN_CONCRETE.getDefaultState());
    }

    // ---------------------------------------------------------------- porteria de futbol
    private static void goal(Frame f) {
        BlockState w = Blocks.WHITE_CONCRETE.getDefaultState();
        int g = f.y;
        for (int a = -3; a <= 3; a++) f.sink.set(f.wx(a, 0), g + 3, f.wz(a, 0), w);
        for (int dy = 1; dy <= 2; dy++) {
            f.sink.set(f.wx(-3, 0), g + dy, f.wz(-3, 0), w);
            f.sink.set(f.wx(3, 0), g + dy, f.wz(3, 0), w);
        }
    }

    // ---------------------------------------------------------------- cistella de bàsquet
    private static void hoop(Frame f) {
        int g = f.y;
        for (int dy = 1; dy <= 3; dy++) f.sink.set(f.wx(-1, 0), g + dy, f.wz(-1, 0), Blocks.POLISHED_ANDESITE.getDefaultState());
        for (int b = -1; b <= 1; b++)
            for (int dy = 4; dy <= 5; dy++) f.sink.set(f.wx(0, b), g + dy, f.wz(0, b), Blocks.WHITE_CONCRETE.getDefaultState());
        f.sink.set(f.wx(-1, 0), g + 4, f.wz(-1, 0), Blocks.POLISHED_ANDESITE.getDefaultState());
        f.sink.set(f.wx(1, 0), g + 4, f.wz(1, 0), Blocks.ORANGE_STAINED_GLASS_PANE.getDefaultState());
    }
}
