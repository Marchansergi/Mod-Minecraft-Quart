package cat.quart.mod.town;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

import java.util.EnumSet;

/** Genera el poble de Quart sobre els chunks que cauen dins del ràster. */
public final class TownGenerator {
    private TownGenerator() {
    }

    // =====================================================================================
    // 1) Terreny: s'executa just després de la superfície vanilla.
    // =====================================================================================
    public static void terrain(Chunk chunk, int seaLevel) {
        TownData td = TownData.get();
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        if (!td.chunkTouchesRaster(cx, cz, 0)) return;
        Sink sink = new Sink(chunk);
        int bottom = chunk.getBottomY();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = sink.minX() + lx, z = sink.minZ() + lz;
                if (!td.inRaster(x, z)) continue;
                double t = td.blend(x, z);
                if (t >= 1.0) continue;
                int wsurf = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG, lx, lz);
                int floor = chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR_WG, lx, lz);
                int g = td.height(x, z);
                int target;
                if (t <= 0) {
                    target = g;
                } else {
                    double s = t * t * (3 - 2 * t);
                    target = (int) Math.round(g * (1 - s) + floor * s);
                }
                // treu el que sobra per sobre
                for (int y = Math.max(target + 1, bottom); y <= Math.max(wsurf, target + 1); y++) {
                    BlockState cur = sink.get(x, y, z);
                    if (cur.isAir()) continue;
                    boolean keepWater = t > 0.5 && cur.getFluidState().isOf(Fluids.WATER) && y <= seaLevel;
                    if (!keepWater) sink.set(x, y, z, Pal.AIR);
                }
                // omple per sota
                int fillFrom = Math.max(bottom, Math.min(floor + 1, target - 12));
                for (int y = fillFrom; y <= target - 4; y++) {
                    BlockState cur = sink.get(x, y, z);
                    if (cur.isAir() || !cur.getFluidState().isEmpty()) sink.set(x, y, z, Pal.STONE);
                }
                if (t <= 0) {
                    paintGround(sink, td, x, z, target);
                } else {
                    int s = td.surface(x, z);
                    if (t < 0.6 && Surf.continuesInMargin(s)) {
                        paintGround(sink, td, x, z, target);
                    } else {
                        BlockState top = chunk.getBlockState(new net.minecraft.util.math.BlockPos(x, floor, z));
                        boolean natural = top.isOf(Blocks.GRASS_BLOCK) || top.isOf(Blocks.SAND) || top.isOf(Blocks.SNOW_BLOCK)
                                || top.isOf(Blocks.PODZOL) || top.isOf(Blocks.COARSE_DIRT) || top.isOf(Blocks.MYCELIUM)
                                || top.isOf(Blocks.RED_SAND) || top.isOf(Blocks.GRAVEL);
                        BlockState topState = natural ? top : Pal.GRASS;
                        BlockState under = topState.isOf(Blocks.SAND) ? Blocks.SANDSTONE.getDefaultState()
                                : topState.isOf(Blocks.GRAVEL) ? Pal.STONE : Pal.DIRT;
                        for (int y = target - 3; y < target; y++) sink.set(x, y, z, under);
                        sink.set(x, target, z, topState);
                    }
                }
            }
        }
    }

    // =====================================================================================
    // 2) Decoració: substitueix les features vanilla dins del nucli del poble.
    // =====================================================================================
    public static void decorate(Chunk chunk) {
        TownData td = TownData.get();
        int cx = chunk.getPos().x, cz = chunk.getPos().z;
        if (!td.chunkInCore(cx, cz)) return;
        Sink sink = new Sink(chunk);
        // columnes (+1 de vora per a balcons dels veïns)
        for (int z = sink.minZ() - 1; z <= sink.minZ() + 16; z++) {
            for (int x = sink.minX() - 1; x <= sink.minX() + 16; x++) {
                if (!td.inRaster(x, z)) continue;
                boolean inside = sink.contains(x, z);
                int bid = td.buildingId(x, z);
                if (bid != 0) {
                    TownData.Building b = td.buildings.get(bid);
                    if (b != null && !b.skip) {
                        Buildings.column(sink, td, b, x, z);
                        continue;
                    }
                }
                if (!inside) continue;
                int g = td.height(x, z);
                paintGround(sink, td, x, z, g);
                surfaceExtras(sink, td, x, z, g);
            }
        }
        Trees.place(sink, td);
        Decorations.place(sink, td);
        Heightmap.populateHeightmaps(chunk, EnumSet.of(Heightmap.Type.MOTION_BLOCKING, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Type.OCEAN_FLOOR, Heightmap.Type.WORLD_SURFACE));
    }

    // =====================================================================================
    // Terra (bloc superior i 3 de sota)
    // =====================================================================================
    static void paintGround(Sink sink, TownData td, int x, int z, int g) {
        int s = td.surface(x, z);
        BlockState top, sub = Pal.DIRT;
        switch (s) {
            case Surf.FOREST -> {
                int h = Pal.hash(x, z, 11) % 10;
                top = h < 2 ? Blocks.PODZOL.getDefaultState() : h < 3 ? Blocks.COARSE_DIRT.getDefaultState() : Pal.GRASS;
            }
            case Surf.FARMLAND -> {
                int f = field(x, z);
                top = f <= 1 ? Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7)
                        : f == 2 ? ((z & 1) == 0 ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.DIRT.getDefaultState()) : Pal.GRASS;
            }
            case Surf.ROAD, Surf.ROAD_MAIN -> {
                top = Pal.hash(x, z, 3) % 11 == 0 ? Blocks.GRAY_CONCRETE_POWDER.getDefaultState() : Blocks.GRAY_CONCRETE.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.MARKING, Surf.TURF_LINE, Surf.COURT_LINE -> {
                top = Blocks.WHITE_CONCRETE.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.SIDEWALK -> {
                if (nextToRoad(td, x, z)) top = Blocks.SMOOTH_STONE.getDefaultState();
                else top = ((x + z) & 1) == 0 ? Blocks.WHITE_TERRACOTTA.getDefaultState() : Blocks.PINK_TERRACOTTA.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.PATH -> top = Blocks.DIRT_PATH.getDefaultState();
            case Surf.TRACK -> top = Pal.hash(x, z, 5) % 3 == 0 ? Blocks.GRAVEL.getDefaultState() : Blocks.COARSE_DIRT.getDefaultState();
            case Surf.CYCLEWAY -> {
                top = Blocks.RED_TERRACOTTA.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.PARKING -> {
                top = Blocks.GRAY_CONCRETE.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.PLAZA -> {
                top = ((x >> 1) + (z >> 1) & 1) == 0 ? Blocks.POLISHED_ANDESITE.getDefaultState() : Blocks.SMOOTH_STONE.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.TURF -> {
                top = ((x + z) / 6 & 1) == 0 ? Blocks.GREEN_CONCRETE.getDefaultState() : Blocks.GREEN_CONCRETE_POWDER.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.COURT -> {
                top = Blocks.RED_TERRACOTTA.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.WATER -> {
                sink.set(x, g - 3, z, Blocks.CLAY.getDefaultState());
                sink.set(x, g - 2, z, Blocks.SAND.getDefaultState());
                sink.set(x, g - 1, z, Pal.WATER);
                sink.set(x, g, z, Pal.WATER);
                return;
            }
            case Surf.STREAM -> {
                sink.set(x, g - 3, z, Pal.DIRT);
                sink.set(x, g - 2, z, Blocks.GRAVEL.getDefaultState());
                sink.set(x, g - 1, z, Pal.WATER);
                sink.set(x, g, z, Pal.AIR);
                return;
            }
            case Surf.POOL -> {
                sink.set(x, g - 3, z, Blocks.LIGHT_BLUE_CONCRETE.getDefaultState());
                sink.set(x, g - 2, z, Pal.WATER);
                sink.set(x, g - 1, z, Pal.WATER);
                sink.set(x, g, z, Pal.WATER);
                return;
            }
            case Surf.SAND -> {
                top = Blocks.SAND.getDefaultState();
                sub = Blocks.SANDSTONE.getDefaultState();
            }
            case Surf.GRAVEL, Surf.CEMETERY -> {
                top = Blocks.GRAVEL.getDefaultState();
                sub = Pal.STONE;
            }
            case Surf.BARE -> top = Blocks.COARSE_DIRT.getDefaultState();
            case Surf.PLAZA_TREES -> {
                top = Pal.hash(x, z, 9) % 7 == 0 ? Blocks.COARSE_DIRT.getDefaultState() : Blocks.SAND.getDefaultState();
                sub = Blocks.SANDSTONE.getDefaultState();
            }
            default -> top = Pal.GRASS;
        }
        // vora de piscina
        if (s != Surf.POOL && isPoolAround(td, x, z)) top = Blocks.SMOOTH_QUARTZ.getDefaultState();
        for (int y = g - 3; y < g; y++) sink.set(x, y, z, sub);
        sink.set(x, g, z, top);
    }

    /** tipus de camp per zones: 0 blat madur, 1 blat verd, 2 llaurat, 3 herba */
    static int field(int x, int z) {
        return Pal.hash(Math.floorDiv(x, 45), Math.floorDiv(z, 60), 77) % 4;
    }

    static boolean nextToRoad(TownData td, int x, int z) {
        return Surf.isRoad(td.surfaceOr(x + 1, z, 0)) || Surf.isRoad(td.surfaceOr(x - 1, z, 0))
                || Surf.isRoad(td.surfaceOr(x, z + 1, 0)) || Surf.isRoad(td.surfaceOr(x, z - 1, 0));
    }

    static boolean isPoolAround(TownData td, int x, int z) {
        return td.surfaceOr(x + 1, z, 0) == Surf.POOL || td.surfaceOr(x - 1, z, 0) == Surf.POOL
                || td.surfaceOr(x, z + 1, 0) == Surf.POOL || td.surfaceOr(x, z - 1, 0) == Surf.POOL;
    }

    // =====================================================================================
    // Plantes, murs de jardí, fanals...
    // =====================================================================================
    private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET};

    static void surfaceExtras(Sink sink, TownData td, int x, int z, int g) {
        int s = td.surface(x, z);
        int h = Pal.hash(x, z, 21);
        int d = td.deco(x, z);
        int fi = td.facadeU(x, z);
        if (fi < td.fences.size() && !Surf.isRoad(s) && s != Surf.SIDEWALK) {
            landmarkFence(sink, td, td.fences.get(fi), fi, x, z, g);
            return;
        }
        if (d != 0 && s != Surf.POOL && !Surf.isRoad(s)) {
            barrier(sink, td, x, z, g, d);
            return;
        }
        switch (s) {
            case Surf.GRASS, Surf.PARK -> plants(sink, x, g, z, h, 9, 1);
            case Surf.MEADOW -> plants(sink, x, g, z, h, 28, 3);
            case Surf.FOREST -> {
                if (h % 100 < 10) sink.set(x, g + 1, z, Blocks.SHORT_GRASS.getDefaultState());
                else if (h % 100 < 13) sink.set(x, g + 1, z, Blocks.FERN.getDefaultState());
                else if (h % 100 < 16) sink.set(x, g + 1, z, Pal.leaves(Blocks.OAK_LEAVES));
            }
            case Surf.SHRUB -> {
                if (h % 100 < 14) {
                    sink.set(x, g + 1, z, Pal.leaves(Blocks.OAK_LEAVES));
                    if (h % 3 == 0) sink.set(x, g + 2, z, Pal.leaves(Blocks.OAK_LEAVES));
                } else plants(sink, x, g, z, h, 15, 1);
            }
            case Surf.FARMLAND -> {
                int f = field(x, z);
                if (f <= 1) sink.set(x, g + 1, z, Blocks.WHEAT.getDefaultState().with(CropBlock.AGE, f == 0 ? 7 : 2 + h % 3));
                else if (f == 3) plants(sink, x, g, z, h, 20, 1);
            }
            case Surf.GARDEN -> {
                if (gardenWall(td, x, z)) {
                    frontWall(sink, td, x, z, g);
                } else plants(sink, x, g, z, h, 5, 1);
            }
            case Surf.SIDEWALK -> {
                if (isLampSpot(td, x, z)) lamp(sink, x, g, z);
            }
            default -> {
            }
        }
    }

    private static void plants(Sink sink, int x, int g, int z, int h, int grassPct, int flowerPct) {
        int r = h % 100;
        if (r < flowerPct) sink.set(x, g + 1, z, FLOWERS[(h >>> 8) % FLOWERS.length].getDefaultState());
        else if (r < flowerPct + grassPct) sink.set(x, g + 1, z, Blocks.SHORT_GRASS.getDefaultState());
    }

    /** columna de jardí que toca la vorera o el carrer: hi va el mur de la parcel·la */
    private static boolean gardenWall(TownData td, int x, int z) {
        for (int[] o : N4) {
            int s = td.surfaceOr(x + o[0], z + o[1], Surf.GRASS);
            if ((s == Surf.SIDEWALK || Surf.isRoad(s)) && td.buildingId(x + o[0], z + o[1]) == 0) return true;
        }
        return false;
    }

    private static final int[][] N4 = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};

    private static void frontWall(Sink sink, TownData td, int x, int z, int g) {
        // material segons la casa més propera (determinista per zona)
        int k = Pal.hash(Math.floorDiv(x, 12), Math.floorDiv(z, 12), 31) % 5;
        BlockState base = switch (k) {
            case 0 -> Blocks.COBBLESTONE.getDefaultState();
            case 1 -> Blocks.BRICKS.getDefaultState();
            case 2 -> Blocks.SMOOTH_SANDSTONE.getDefaultState();
            case 3 -> Blocks.WHITE_TERRACOTTA.getDefaultState();
            default -> Blocks.STONE_BRICKS.getDefaultState();
        };
        sink.set(x, g + 1, z, base);
        // reixa/panell fosc a sobre, connectat amb els veïns del mur
        boolean n = isWallCol(td, x, z - 1), e = isWallCol(td, x + 1, z), s = isWallCol(td, x, z + 1), w = isWallCol(td, x - 1, z);
        sink.set(x, g + 2, z, Pal.connect(Blocks.IRON_BARS, n, e, s, w));
    }

    /** mur de parcel·la d'una casa destacada: sòcol de pedra/maó i panell fosc a sobre */
    private static void landmarkFence(Sink sink, TownData td, TownData.Fence f, int fi, int x, int z, int g) {
        sink.set(x, g + 1, z, Pal.of(f.base));
        if (f.top == null) return;
        BlockState top = Pal.of(f.top);
        Block tb = top.getBlock();
        if (top.contains(net.minecraft.block.HorizontalConnectingBlock.NORTH)) {
            boolean n = isFence(td, fi, x, z - 1), e = isFence(td, fi, x + 1, z), s = isFence(td, fi, x, z + 1), w = isFence(td, fi, x - 1, z);
            top = Pal.connect(tb, n, e, s, w);
        }
        // pilars de maó/pedra cada 3 blocs, panells entremig
        boolean pillar = Math.floorMod(x + z, 3) == 0;
        sink.set(x, g + 2, z, pillar ? Pal.of(f.base) : top);
    }

    private static boolean isFence(TownData td, int fi, int x, int z) {
        return td.inRaster(x, z) && td.buildingId(x, z) == 0 && td.facadeU(x, z) == fi;
    }

    private static boolean isWallCol(TownData td, int x, int z) {
        return td.inRaster(x, z) && td.surface(x, z) == Surf.GARDEN && td.buildingId(x, z) == 0 && td.deco(x, z) == 0
                && td.facadeU(x, z) >= td.fences.size() && gardenWall(td, x, z);
    }

    private static void barrier(Sink sink, TownData td, int x, int z, int g, int d) {
        switch (d) {
            case Surf.DECO_WALL -> {
                sink.set(x, g + 1, z, Blocks.STONE_BRICKS.getDefaultState());
                sink.set(x, g + 2, z, Blocks.STONE_BRICK_SLAB.getDefaultState());
            }
            case Surf.DECO_RETAINING -> sink.set(x, g + 1, z, Blocks.COBBLESTONE.getDefaultState());
            case Surf.DECO_HEDGE -> {
                sink.set(x, g + 1, z, Pal.leaves(Blocks.OAK_LEAVES));
                sink.set(x, g + 2, z, Pal.leaves(Blocks.OAK_LEAVES));
            }
            case Surf.DECO_FENCE -> {
                boolean n = td.inRaster(x, z - 1) && td.deco(x, z - 1) == d, e = td.inRaster(x + 1, z) && td.deco(x + 1, z) == d;
                boolean s = td.inRaster(x, z + 1) && td.deco(x, z + 1) == d, w = td.inRaster(x - 1, z) && td.deco(x - 1, z) == d;
                BlockState f = Pal.connect(Blocks.SPRUCE_FENCE, n, e, s, w);
                sink.set(x, g + 1, z, f);
            }
            default -> {
            }
        }
    }

    /** fanals cada ~20 m a la vora de la vorera: un per cel·la de 20x20 */
    private static boolean isLampSpot(TownData td, int x, int z) {
        if (!nextToRoad(td, x, z)) return false;
        int cx = Math.floorDiv(x, 20), cz = Math.floorDiv(z, 20);
        int best = Integer.MAX_VALUE, bx = 0, bz = 0;
        for (int zz = cz * 20; zz < cz * 20 + 20; zz++)
            for (int xx = cx * 20; xx < cx * 20 + 20; xx++) {
                if (!td.inRaster(xx, zz) || td.surface(xx, zz) != Surf.SIDEWALK || td.buildingId(xx, zz) != 0 || !nextToRoad(td, xx, zz))
                    continue;
                int hh = Pal.hash(xx, zz, 4242);
                if (hh < best) {
                    best = hh;
                    bx = xx;
                    bz = zz;
                }
            }
        return bx == x && bz == z;
    }

    private static void lamp(Sink sink, int x, int g, int z) {
        for (int y = 1; y <= 4; y++) sink.set(x, g + y, z, Blocks.ANDESITE_WALL.getDefaultState());
        sink.set(x, g + 5, z, Blocks.LANTERN.getDefaultState());
    }

    static BlockState slabTop(Block b) {
        return Pal.slab(b, SlabType.TOP);
    }

    static Direction dirOf(int dx, int dz) {
        if (dx > 0) return Direction.EAST;
        if (dx < 0) return Direction.WEST;
        if (dz > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }
}
