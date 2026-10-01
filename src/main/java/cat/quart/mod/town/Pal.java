package cat.quart.mod.town;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalConnectingBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Utilitats de blocs i estats. */
public final class Pal {
    private static final Map<String, BlockState> CACHE = new ConcurrentHashMap<>();

    public static final BlockState AIR = Blocks.AIR.getDefaultState();
    public static final BlockState STONE = Blocks.STONE.getDefaultState();
    public static final BlockState DIRT = Blocks.DIRT.getDefaultState();
    public static final BlockState GRASS = Blocks.GRASS_BLOCK.getDefaultState();
    public static final BlockState WATER = Blocks.WATER.getDefaultState();

    private Pal() {
    }

    public static BlockState of(String id) {
        return CACHE.computeIfAbsent(id, k -> {
            Block b = Registries.BLOCK.get(Identifier.of(k));
            return b == Blocks.AIR && !k.endsWith("air") ? Blocks.STONE.getDefaultState() : b.getDefaultState();
        });
    }

    public static BlockState leaves(Block b) {
        return b.getDefaultState().with(LeavesBlock.PERSISTENT, true);
    }

    public static BlockState slab(Block b, SlabType t) {
        return b.getDefaultState().with(SlabBlock.TYPE, t);
    }

    public static BlockState stairs(Block b, Direction facing) {
        return b.getDefaultState().with(StairsBlock.FACING, facing);
    }

    /** panell/barrots/tanca connectats en les direccions indicades */
    public static BlockState connect(Block b, boolean n, boolean e, boolean s, boolean w) {
        BlockState st = b.getDefaultState();
        if (st.contains(HorizontalConnectingBlock.NORTH)) {
            st = st.with(HorizontalConnectingBlock.NORTH, n).with(HorizontalConnectingBlock.EAST, e)
                    .with(HorizontalConnectingBlock.SOUTH, s).with(HorizontalConnectingBlock.WEST, w);
        }
        return st;
    }

    public static BlockState door(Block b, Direction facing, boolean upper) {
        return b.getDefaultState().with(DoorBlock.FACING, facing)
                .with(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }

    /** Famílies de teulada: bloc sencer i llosa */
    public static BlockState[] roofFamily(String name) {
        return switch (name) {
            case "granite" -> new BlockState[]{Blocks.POLISHED_GRANITE.getDefaultState(), slab(Blocks.POLISHED_GRANITE_SLAB, SlabType.BOTTOM)};
            case "mud_bricks" -> new BlockState[]{Blocks.MUD_BRICKS.getDefaultState(), slab(Blocks.MUD_BRICK_SLAB, SlabType.BOTTOM)};
            case "acacia" -> new BlockState[]{Blocks.ACACIA_PLANKS.getDefaultState(), slab(Blocks.ACACIA_SLAB, SlabType.BOTTOM)};
            case "deepslate_tiles" -> new BlockState[]{Blocks.DEEPSLATE_TILES.getDefaultState(), slab(Blocks.DEEPSLATE_TILE_SLAB, SlabType.BOTTOM)};
            case "spruce" -> new BlockState[]{Blocks.SPRUCE_PLANKS.getDefaultState(), slab(Blocks.SPRUCE_SLAB, SlabType.BOTTOM)};
            case "dark_oak" -> new BlockState[]{Blocks.DARK_OAK_PLANKS.getDefaultState(), slab(Blocks.DARK_OAK_SLAB, SlabType.BOTTOM)};
            case "red_sandstone" -> new BlockState[]{Blocks.CUT_RED_SANDSTONE.getDefaultState(), slab(Blocks.CUT_RED_SANDSTONE_SLAB, SlabType.BOTTOM)};
            case "terracotta" -> new BlockState[]{Blocks.TERRACOTTA.getDefaultState(), slab(Blocks.GRANITE_SLAB, SlabType.BOTTOM)};
            default -> new BlockState[]{Blocks.BRICKS.getDefaultState(), slab(Blocks.BRICK_SLAB, SlabType.BOTTOM)};
        };
    }

    public static int hash(int x, int z, int salt) {
        int h = x * 0x27d4eb2d ^ z * 0x165667b1 ^ salt * 0x5bd1e995;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return h & 0x7fffffff;
    }
}
