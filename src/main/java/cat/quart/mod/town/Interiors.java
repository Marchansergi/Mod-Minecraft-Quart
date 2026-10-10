package cat.quart.mod.town;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeveledCauldronBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interiors dels edificis destacats: escales entre plantes, envans, portes i mobles segons l'ús real de cada
 * edifici (habitatge, punt d'informació de l'estació, museu de terrissa, local social, escola, pavelló, súper, botiga).
 * Cada pla es calcula una sola vegada a partir del ràster i és determinista, de manera que cada chunk n'escriu la
 * part que li toca.
 */
public final class Interiors {
    private Interiors() {
    }

    private static final Map<Integer, Plan> PLANS = new ConcurrentHashMap<>();

    /** bloc interior a (x, y, z) d'un edifici destacat, o null per deixar el que hi posaria Buildings */
    public static BlockState at(TownData td, TownData.Building b, int x, int y, int z) {
        if (b.interior == null || b.bbox == null) return null;
        Plan p = PLANS.computeIfAbsent(b.id, k -> new Plan(td, b));
        return p.blocks.get(Plan.k3(x, y, z));
    }

    // =====================================================================================================
    static final class Plan {
        final TownData td;
        final TownData.Building b;
        final Map<Long, BlockState> blocks = new HashMap<>();
        final Set<Long> inner = new HashSet<>();
        final Direction front, inward, right;
        final int fh, base, floors;

        static long k2(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        static int kx(long k) {
            return (int) (k >> 32);
        }

        static int kz(long k) {
            return (int) k;
        }

        static long k3(int x, int y, int z) {
            return (((long) x & 0x3FFFFFFL) << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((y + 1024) & 0xFFFL);
        }

        Plan(TownData td, TownData.Building b) {
            this.td = td;
            this.b = b;
            this.fh = Math.max(2, b.floorHeight);
            this.base = b.base;
            this.floors = b.floors;
            double th = Math.toRadians(b.facing);
            double fx = Math.cos(th), fz = Math.sin(th);
            front = Math.abs(fx) >= Math.abs(fz) ? (fx > 0 ? Direction.EAST : Direction.WEST) : (fz > 0 ? Direction.SOUTH : Direction.NORTH);
            inward = front.getOpposite();
            right = front.rotateYClockwise();
            try {
                make();
            } catch (RuntimeException e) {
                // un interior mal calculat no ha d'impedir generar el món
                blocks.clear();
            }
        }

        int a(long c) {
            return -(kx(c) * front.getOffsetX() + kz(c) * front.getOffsetZ());
        }

        int bb(long c) {
            return kx(c) * right.getOffsetX() + kz(c) * right.getOffsetZ();
        }

        long step(long c, Direction d, int n) {
            return k2(kx(c) + d.getOffsetX() * n, kz(c) + d.getOffsetZ() * n);
        }

        void put(long c, int y, BlockState s) {
            blocks.put(k3(kx(c), y, kz(c)), s);
        }

        boolean isEdge(int x, int z) {
            int id = b.id;
            for (Direction d : Direction.Type.HORIZONTAL)
                if (td.buildingId(x + d.getOffsetX(), z + d.getOffsetZ()) != id) return true;
            return td.buildingId(x + 1, z + 1) != id || td.buildingId(x - 1, z - 1) != id
                    || td.buildingId(x + 1, z - 1) != id || td.buildingId(x - 1, z + 1) != id;
        }

        int floorY(int k) {
            return base + k * fh;
        }

        // ---------------------------------------------------------------------------------------------
        void make() {
            int[] bx = b.bbox;
            List<long[]> doorCells = new ArrayList<>();
            for (int z = bx[1]; z <= bx[3]; z++)
                for (int x = bx[0]; x <= bx[2]; x++) {
                    if (td.buildingId(x, z) != b.id) continue;
                    if (!isEdge(x, z)) {
                        inner.add(k2(x, z));
                        continue;
                    }
                    int fl = td.flags(x, z);
                    char c = 'W';
                    if ((fl & TownData.F_FACADE) != 0 && b.facade.length > 0) c = Buildings.facadeChar(b, 0, td.facadeU(x, z));
                    if ((fl & TownData.F_DOOR) != 0 || c == 'D' || c == 'd' || c == 'G' || c == 'P' || c == 'S')
                        doorCells.add(new long[]{x, z});
                }
            if (inner.isEmpty()) return;
            // davant de les portes i garatges no hi va cap moble
            Set<Long> keepClear = new HashSet<>();
            for (long[] dc : doorCells)
                for (Direction d : Direction.Type.HORIZONTAL) {
                    long n1 = k2((int) dc[0] + d.getOffsetX(), (int) dc[1] + d.getOffsetZ());
                    if (inner.contains(n1)) {
                        keepClear.add(n1);
                        long n2 = step(n1, d, 1);
                        if (inner.contains(n2)) keepClear.add(n2);
                    }
                }

            // divisió en habitatges (cases en filera) o aules
            int nd = Math.max(1, b.interior.equals("school") ? b.rooms : b.dwellings);
            int bmin = Integer.MAX_VALUE, bmax = Integer.MIN_VALUE;
            for (long c : inner) {
                bmin = Math.min(bmin, bb(c));
                bmax = Math.max(bmax, bb(c));
            }
            int span = bmax - bmin + 1;
            Set<Integer> walls = new HashSet<>();
            for (int j = 1; j < nd; j++) walls.add(bmin + (int) Math.round(span * j / (double) nd));
            List<List<Long>> units = new ArrayList<>();
            for (int j = 0; j < nd; j++) units.add(new ArrayList<>());
            List<Long> partyWall = new ArrayList<>();
            for (long c : inner) {
                int v = bb(c);
                if (walls.contains(v)) {
                    partyWall.add(c);
                    continue;
                }
                int j = 0;
                for (int w : walls) if (v > w) j++;
                units.get(Math.min(j, nd - 1)).add(c);
            }
            BlockState pw = b.interior.equals("school") ? Blocks.WHITE_CONCRETE.getDefaultState() : Pal.of(b.wall);
            for (long c : partyWall)
                for (int y = base + 1; y < b.wallTop(); y++) put(c, y, pw);
            if (b.interior.equals("school")) {
                // porta a cada envà, a prop de la façana (passadís)
                for (int w : walls) {
                    Long best = null;
                    int bestA = Integer.MAX_VALUE;
                    for (long c : partyWall)
                        if (bb(c) == w && a(c) < bestA && !keepClear.contains(c) && inner.contains(step(c, inward, 1))) {
                            bestA = a(c);
                            best = c;
                        }
                    if (best != null) {
                        // la porta es fa a la cel·la de l'envà, a la segona fila (la primera és el passadís)
                        long dc = step(best, inward, 1);
                        put(dc, base + 1, Pal.door(Blocks.BIRCH_DOOR, right, false));
                        put(dc, base + 2, Pal.door(Blocks.BIRCH_DOOR, right, true));
                        for (int y = base + 3; y < b.wallTop(); y++) put(dc, y, pw);
                    }
                }
            }
            for (int j = 0; j < units.size(); j++) furnishUnit(units.get(j), keepClear, j);
        }

        // ---------------------------------------------------------------------------------------------
        void furnishUnit(List<Long> cells, Set<Long> keepClear, int unitIndex) {
            if (cells.isEmpty()) return;
            Set<Long> cellSet = new HashSet<>(cells);
            Map<Integer, Set<Long>> reserved = new HashMap<>();
            for (int k = 0; k < floors; k++) reserved.put(k, new HashSet<>());
            reserved.get(0).addAll(keepClear);
            Map<Integer, Set<Long>> holes = new HashMap<>();
            for (int k = 0; k <= floors; k++) holes.put(k, new HashSet<>());

            // ---- escales entre plantes
            if (floors > 1 && fh >= 3) {
                long[] run = findRun(cellSet);
                if (run != null) {
                    long start = run[0];
                    Direction up = Direction.byId((int) run[1]);
                    Direction side = Direction.byId((int) run[2]);
                    for (int k = 0; k < floors - 1; k++) {
                        int y0 = floorY(k);
                        for (int i = 0; i < fh; i++) {
                            long c = step(start, up, i);
                            reserved.get(k).add(c);
                            reserved.get(k).add(step(c, side, 1));
                            reserved.get(k + 1).add(c);
                            reserved.get(k + 1).add(step(c, side, 1));
                            if (i < fh - 1) {
                                for (int y = y0 + 1; y < y0 + 1 + i; y++) put(c, y, Blocks.SPRUCE_PLANKS.getDefaultState());
                                put(c, y0 + 1 + i, Pal.stairs(Blocks.SPRUCE_STAIRS, up));
                                for (int y = y0 + 2 + i; y < y0 + fh; y++) put(c, y, Pal.AIR);
                                put(c, y0 + fh, Pal.AIR); // forat al sostre
                                holes.get(k + 1).add(c);
                                // barana al pis de dalt
                                long rc = step(c, side, 1);
                                if (cellSet.contains(rc))
                                    put(rc, y0 + fh + 1, Pal.connect(Blocks.SPRUCE_FENCE,
                                            up.getAxis() == Direction.Axis.Z, up.getAxis() == Direction.Axis.X,
                                            up.getAxis() == Direction.Axis.Z, up.getAxis() == Direction.Axis.X));
                            }
                        }
                        long before = step(start, up, -1);
                        reserved.get(k).add(before);
                        if (cellSet.contains(before))
                            put(before, y0 + fh + 1, Pal.connect(Blocks.SPRUCE_FENCE,
                                    up.getAxis() == Direction.Axis.X, up.getAxis() == Direction.Axis.Z,
                                    up.getAxis() == Direction.Axis.X, up.getAxis() == Direction.Axis.Z));
                    }
                }
            }

            // ---- plantes i habitacions
            for (int k = 0; k < floors; k++) {
                List<String> types = roomTypes(k, cells.size());
                int amin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE;
                for (long c : cells) {
                    amin = Math.min(amin, a(c));
                    amax = Math.max(amax, a(c));
                }
                int depth = amax - amin + 1;
                List<List<Long>> rooms = new ArrayList<>();
                if (types.size() >= 2 && depth >= 8) {
                    int aMid = amin + depth / 2;
                    List<Long> fr = new ArrayList<>(), bk = new ArrayList<>(), wall = new ArrayList<>();
                    for (long c : cells) {
                        int aa = a(c);
                        if (aa == aMid && !reserved.get(k).contains(c) && !holes.get(k).contains(c)) wall.add(c);
                        else if (aa <= aMid) fr.add(c);
                        else bk.add(c);
                    }
                    BlockState pw = Blocks.WHITE_CONCRETE.getDefaultState();
                    int y0 = floorY(k);
                    wall.sort(Comparator.comparingInt(this::bb));
                    for (long c : wall) for (int y = y0 + 1; y < y0 + fh; y++) put(c, y, pw);
                    // porta al mig de l'envà
                    if (!wall.isEmpty()) {
                        long dc = wall.get(wall.size() / 2);
                        put(dc, y0 + 1, Pal.door(Blocks.OAK_DOOR, inward, false));
                        put(dc, y0 + 2, Pal.door(Blocks.OAK_DOOR, inward, true));
                        reserved.get(k).add(step(dc, inward, 1));
                        reserved.get(k).add(step(dc, front, 1));
                    }
                    rooms.add(fr);
                    rooms.add(bk);
                } else {
                    rooms.add(new ArrayList<>(cells));
                    types = List.of(types.size() >= 2 && types.get(0).equals("living") ? "living_kitchen" : types.get(0));
                }
                for (int i = 0; i < rooms.size(); i++) {
                    Room r = new Room(this, rooms.get(i), floorY(k), types.get(Math.min(i, types.size() - 1)),
                            reserved.get(k), holes.get(k), holes.get(k + 1), unitIndex * 31 + k * 7 + i);
                    r.furnish();
                }
            }
        }

        /** tipus d'habitacions de cada planta (de davant cap enrere) */
        List<String> roomTypes(int k, int area) {
            String t = b.interior;
            switch (t) {
                case "info_point":
                    return List.of("info_point");
                case "museum":
                    return k == 0 ? List.of("museum") : List.of("workshop");
                case "social":
                    return List.of("social");
                case "gym":
                    return List.of("gym");
                case "school":
                    return List.of("classroom");
                case "supermarket":
                    if (k == 0) return List.of("supermarket");
                    break;
                case "shop_flats":
                    if (k == 0) return List.of("shop_" + (b.shop != null ? b.shop : "general"));
                    break;
                default:
                    break;
            }
            int hk = k - (t.equals("supermarket") || t.equals("shop_flats") ? 1 : 0);
            if (floors == 1) return List.of("living", "bedroom");
            if (hk <= 0 && !(t.equals("supermarket") || t.equals("shop_flats"))) return List.of("living", "kitchen");
            if (t.equals("supermarket") || t.equals("shop_flats")) {
                return hk == 0 ? List.of("living", "kitchen") : List.of("bedroom", "bath");
            }
            if (hk == 1) return area >= 60 ? List.of("bedroom", "kids") : List.of("bedroom", "bath");
            return List.of("office", "bath");
        }

        /** busca un tram recte de fh cel·les lliures (amb una fila al costat) per a l'escala, com més al fons millor */
        long[] findRun(Set<Long> cells) {
            List<Long> sorted = new ArrayList<>(cells);
            sorted.sort(Comparator.comparingInt((Long c) -> -a(c)).thenComparingInt(this::bb));
            Direction[][] options = {{right, front}, {right.getOpposite(), front}, {inward, right}, {inward, right.getOpposite()}};
            for (Direction[] o : options) {
                for (long c : sorted) {
                    boolean ok = cells.contains(step(c, o[0], -1));
                    for (int i = 0; i < fh && ok; i++) {
                        long ci = step(c, o[0], i);
                        if (!cells.contains(ci) || !cells.contains(step(ci, o[1], 1))) ok = false;
                    }
                    if (ok) return new long[]{c, o[0].getId(), o[1].getId()};
                }
            }
            return null;
        }
    }

    // =====================================================================================================
    /** una habitació en una planta: posa el terra, la llum i els mobles */
    static final class Room {
        final Plan p;
        final List<Long> cells;
        final Set<Long> set;
        final int fy, ceilY, seed;
        final String type;
        final Set<Long> used = new HashSet<>();
        final Set<Long> holesBelow, holesAbove;

        Room(Plan p, List<Long> cells, int fy, String type, Set<Long> reserved, Set<Long> holesHere, Set<Long> holesAbove, int seed) {
            this.p = p;
            this.cells = new ArrayList<>(cells);
            this.cells.sort(Comparator.comparingInt(p::a).thenComparingInt(p::bb));
            this.set = new HashSet<>(cells);
            this.fy = fy;
            this.ceilY = fy + p.fh;
            this.type = type;
            this.seed = seed;
            this.used.addAll(reserved);
            this.used.addAll(holesHere);
            this.holesBelow = holesHere;
            this.holesAbove = holesAbove;
        }

        int head() {
            return p.fh - 1; // files d'aire
        }

        boolean free(long c) {
            return set.contains(c) && !used.contains(c);
        }

        void put(long c, int dy, BlockState s) {
            p.put(c, fy + dy, s);
        }

        /** cel·les de paret: (cel·la, direcció cap a la paret) */
        List<Object[]> wallSlots() {
            List<Object[]> out = new ArrayList<>();
            for (long c : cells)
                for (Direction d : Direction.Type.HORIZONTAL)
                    if (!set.contains(p.step(c, d, 1))) out.add(new Object[]{c, d});
            return out;
        }

        long center() {
            double sa = 0, sb = 0;
            for (long c : cells) {
                sa += Plan.kx(c);
                sb += Plan.kz(c);
            }
            double mx = sa / cells.size(), mz = sb / cells.size();
            long best = cells.get(0);
            double bd = 1e9;
            for (long c : cells) {
                if (used.contains(c)) continue;
                double d = Math.pow(Plan.kx(c) - mx, 2) + Math.pow(Plan.kz(c) - mz, 2);
                if (d < bd) {
                    bd = d;
                    best = c;
                }
            }
            return best;
        }

        // -------------------------------------------------------------------------------- terra i llum
        void floorAndLight(BlockState a, BlockState b) {
            for (long c : cells) {
                if (holesBelow.contains(c)) continue;
                if (p.blocks.containsKey(Plan.k3(Plan.kx(c), fy, Plan.kz(c)))) continue;
                boolean chk = ((Plan.kx(c) + Plan.kz(c)) & 1) == 0;
                put(c, 0, chk ? a : b);
            }
            light();
        }

        void light() {
            // llums al sostre: un punt de llum cada ~25 m2
            int n = Math.max(1, cells.size() / 25);
            List<Long> spots = new ArrayList<>();
            long c0 = center();
            spots.add(c0);
            if (n > 1) {
                List<Long> sorted = new ArrayList<>(cells);
                sorted.sort(Comparator.comparingInt(c -> Plan.kx(c) * 31 + Plan.kz(c) * 17 + seed));
                for (int i = 0; i < sorted.size() && spots.size() < n; i += Math.max(1, sorted.size() / n)) spots.add(sorted.get(i));
            }
            for (long c : spots) {
                if (holesAbove.contains(c)) continue;
                if (type.equals("gym")) {
                    // la volta del pavelló no té sostre pla: focus penjats
                    put(c, head(), Blocks.SEA_LANTERN.getDefaultState());
                } else if (p.fh >= 4 && !used.contains(c)) {
                    put(c, head(), Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
                } else {
                    // sostre baix: llum encastat al sostre
                    put(c, p.fh, Blocks.PEARLESCENT_FROGLIGHT.getDefaultState());
                }
            }
        }

        // -------------------------------------------------------------------------------- mobles bàsics
        /** posa una pila de blocs contra la paret; retorna si s'ha pogut */
        boolean along(Object[] slot, BlockState... stack) {
            long c = (Long) slot[0];
            if (!free(c) || stack.length > head()) return false;
            for (int i = 0; i < stack.length; i++) put(c, 1 + i, stack[i]);
            used.add(c);
            return true;
        }

        /** recorre les parets i va col·locant els mobles de la llista, un per cel·la, deixant un forat entre grups */
        void lineWalls(List<BlockState[]> items, int gapEvery) {
            List<Object[]> slots = wallSlots();
            int i = 0, n = 0;
            for (Object[] s : slots) {
                if (i >= items.size()) break;
                if (along(s, orient(items.get(i), (Direction) s[1]))) {
                    i++;
                    n++;
                    if (gapEvery > 0 && n % gapEvery == 0) used.add(p.step((Long) s[0], ((Direction) s[1]).rotateYClockwise(), 1));
                }
            }
        }

        /** orienta els blocs direccionals cap a l'interior de l'habitació */
        BlockState[] orient(BlockState[] st, Direction toWall) {
            BlockState[] out = new BlockState[st.length];
            for (int i = 0; i < st.length; i++) {
                BlockState s = st[i];
                if (s.contains(Properties.HORIZONTAL_FACING)) {
                    // cadires i sofàs: respatller a la paret; forns, barrils...: obertura cap a dins
                    boolean seat = s.getBlock() instanceof net.minecraft.block.StairsBlock;
                    s = s.with(Properties.HORIZONTAL_FACING, seat ? toWall : toWall.getOpposite());
                }
                out[i] = s;
            }
            return out;
        }

        boolean bed(Block bed) {
            for (Object[] s : wallSlots()) {
                long head = (Long) s[0];
                Direction toWall = (Direction) s[1];
                long foot = p.step(head, toWall, -1);
                if (!free(head) || !free(foot)) continue;
                put(head, 1, bed.getDefaultState().with(Properties.HORIZONTAL_FACING, toWall).with(Properties.BED_PART, BedPart.HEAD));
                put(foot, 1, bed.getDefaultState().with(Properties.HORIZONTAL_FACING, toWall).with(Properties.BED_PART, BedPart.FOOT));
                used.add(head);
                used.add(foot);
                // tauleta de nit al costat del capçal
                long ns = p.step(head, toWall.rotateYClockwise(), 1);
                if (free(ns)) {
                    put(ns, 1, Blocks.SPRUCE_PLANKS.getDefaultState());
                    if (head() >= 2) put(ns, 2, Blocks.LANTERN.getDefaultState());
                    used.add(ns);
                }
                return true;
            }
            return false;
        }

        /** taula amb cadires al voltant */
        void table(long c, BlockState top, int chairs) {
            if (!free(c)) return;
            put(c, 1, top);
            used.add(c);
            int n = 0;
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (n >= chairs) break;
                long ch = p.step(c, d, 1);
                if (!free(ch)) continue;
                put(ch, 1, Pal.stairs(Blocks.OAK_STAIRS, d));
                used.add(ch);
                n++;
            }
        }

        void rug(long c, Block carpet, int ra, int rb) {
            for (int i = -ra; i <= ra; i++)
                for (int j = -rb; j <= rb; j++) {
                    long r = Plan.k2(Plan.kx(c) + i, Plan.kz(c) + j);
                    if (free(r)) put(r, 1, carpet.getDefaultState());
                }
        }

        BlockState pot(int i) {
            Block[] pots = {Blocks.POTTED_FERN, Blocks.POTTED_RED_TULIP, Blocks.POTTED_CACTUS, Blocks.POTTED_AZALEA_BUSH, Blocks.POTTED_POPPY};
            return pots[Math.floorMod(i + seed, pots.length)].getDefaultState();
        }

        static BlockState up(Block b) {
            return b.getDefaultState().contains(Properties.FACING) ? b.getDefaultState().with(Properties.FACING, Direction.UP) : b.getDefaultState();
        }

        static BlockState water() {
            return Blocks.WATER_CAULDRON.getDefaultState().with(LeveledCauldronBlock.LEVEL, 3);
        }

        static BlockState slabTop(Block b) {
            return b.getDefaultState().with(Properties.SLAB_TYPE, SlabType.TOP);
        }

        static BlockState trapdoorTop(Block b) {
            return b.getDefaultState().with(Properties.BLOCK_HALF, BlockHalf.TOP);
        }

        // -------------------------------------------------------------------------------- tipus d'habitació
        void furnish() {
            BlockState oak = Blocks.OAK_PLANKS.getDefaultState();
            BlockState spruceSlab = Blocks.SPRUCE_SLAB.getDefaultState();
            switch (type) {
                case "living", "living_kitchen" -> {
                    floorAndLight(oak, Blocks.BIRCH_PLANKS.getDefaultState());
                    long c = center();
                    rug(c, Blocks.LIGHT_GRAY_CARPET, 1, 1);
                    List<BlockState[]> items = new ArrayList<>();
                    BlockState sofa = Pal.stairs(Blocks.DARK_OAK_STAIRS, Direction.NORTH);
                    items.add(new BlockState[]{sofa});
                    items.add(new BlockState[]{sofa});
                    items.add(new BlockState[]{sofa});
                    items.add(new BlockState[]{Blocks.DARK_OAK_SLAB.getDefaultState()});
                    items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
                    items.add(new BlockState[]{pot(0)});
                    items.add(new BlockState[]{Blocks.SPRUCE_PLANKS.getDefaultState(), Blocks.BLACK_STAINED_GLASS_PANE.getDefaultState()});
                    items.add(new BlockState[]{Pal.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH)});
                    if (type.equals("living_kitchen")) {
                        items.add(new BlockState[]{Blocks.IRON_BLOCK.getDefaultState(), Blocks.IRON_BLOCK.getDefaultState()});
                        items.add(new BlockState[]{up(Blocks.BARREL)});
                        items.add(new BlockState[]{water()});
                        items.add(new BlockState[]{Blocks.SMOKER.getDefaultState()});
                    }
                    items.add(new BlockState[]{pot(2)});
                    lineWalls(items, 3);
                    table(p.step(c, p.right, 2), Blocks.OAK_FENCE.getDefaultState(), 2);
                }
                case "kitchen" -> {
                    floorAndLight(Blocks.POLISHED_DIORITE.getDefaultState(), Blocks.SMOOTH_STONE.getDefaultState());
                    List<BlockState[]> items = new ArrayList<>();
                    items.add(new BlockState[]{Blocks.IRON_BLOCK.getDefaultState(), Blocks.IRON_BLOCK.getDefaultState()}); // nevera
                    items.add(new BlockState[]{up(Blocks.BARREL)});
                    items.add(new BlockState[]{water()});                                     // pica
                    items.add(new BlockState[]{Blocks.SMOKER.getDefaultState()});            // forn
                    items.add(new BlockState[]{up(Blocks.BARREL)});
                    items.add(new BlockState[]{Blocks.FURNACE.getDefaultState()});
                    items.add(new BlockState[]{up(Blocks.BARREL)});
                    items.add(new BlockState[]{Blocks.CRAFTING_TABLE.getDefaultState()});
                    lineWalls(items, 0);
                    long c = center();
                    table(c, Blocks.OAK_FENCE.getDefaultState(), 4);
                    if (head() >= 2 && p.blocks.containsKey(Plan.k3(Plan.kx(c), fy + 1, Plan.kz(c))))
                        put(c, 2, Blocks.POTTED_RED_TULIP.getDefaultState());
                }
                case "bedroom", "kids" -> {
                    floorAndLight(oak, oak);
                    bed(type.equals("kids") ? (seed % 2 == 0 ? Blocks.LIGHT_BLUE_BED : Blocks.LIME_BED) : Blocks.WHITE_BED);
                    if (type.equals("bedroom") && cells.size() > 20) bed(Blocks.WHITE_BED);
                    List<BlockState[]> items = new ArrayList<>();
                    items.add(new BlockState[]{Blocks.DARK_OAK_PLANKS.getDefaultState(), Blocks.DARK_OAK_PLANKS.getDefaultState()}); // armari
                    items.add(new BlockState[]{Blocks.DARK_OAK_PLANKS.getDefaultState(), Blocks.DARK_OAK_PLANKS.getDefaultState()});
                    items.add(new BlockState[]{Blocks.CHEST.getDefaultState()});
                    items.add(new BlockState[]{spruceSlab});                                  // escriptori
                    items.add(new BlockState[]{pot(1)});
                    if (type.equals("kids")) items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState()});
                    lineWalls(items, 2);
                    rug(center(), type.equals("kids") ? Blocks.YELLOW_CARPET : Blocks.BROWN_CARPET, 1, 0);
                }
                case "bath" -> {
                    floorAndLight(Blocks.WHITE_CONCRETE.getDefaultState(), Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState());
                    List<BlockState[]> items = new ArrayList<>();
                    items.add(new BlockState[]{water()});                                     // banyera
                    items.add(new BlockState[]{water()});
                    items.add(new BlockState[]{Blocks.QUARTZ_SLAB.getDefaultState()});
                    items.add(new BlockState[]{water(), Blocks.GLASS_PANE.getDefaultState()}); // lavabo i mirall
                    items.add(new BlockState[]{Blocks.HOPPER.getDefaultState()});            // vàter
                    lineWalls(items, 0);
                }
                case "office" -> {
                    floorAndLight(oak, oak);
                    List<BlockState[]> items = new ArrayList<>();
                    items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
                    items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
                    items.add(new BlockState[]{spruceSlab});
                    items.add(new BlockState[]{Pal.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH)});
                    items.add(new BlockState[]{pot(3)});
                    lineWalls(items, 0);
                    rug(center(), Blocks.GREEN_CARPET, 1, 1);
                }
                case "info_point" -> infoPoint();
                case "museum" -> museum();
                case "workshop" -> workshop();
                case "social" -> social();
                case "classroom" -> classroom();
                case "gym" -> gym();
                case "supermarket" -> supermarket();
                default -> {
                    if (type.startsWith("shop")) shop();
                    else floorAndLight(oak, oak);
                }
            }
        }

        /** Estació: punt d'informació juvenil (cartell "Punt d'Informació Juvenil" a la foto de la façana) */
        void infoPoint() {
            floorAndLight(Blocks.POLISHED_ANDESITE.getDefaultState(), Blocks.SMOOTH_STONE.getDefaultState());
            List<BlockState[]> items = new ArrayList<>();
            items.add(new BlockState[]{up(Blocks.BARREL), Blocks.SMOOTH_STONE_SLAB.getDefaultState()}); // taulell
            items.add(new BlockState[]{up(Blocks.BARREL)});
            items.add(new BlockState[]{up(Blocks.BARREL)});
            items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()}); // fullets
            items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState()});
            items.add(new BlockState[]{pot(0)});
            for (int i = 0; i < 5; i++) items.add(new BlockState[]{Pal.stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH)}); // bancs
            items.add(new BlockState[]{Blocks.LECTERN.getDefaultState()});
            items.add(new BlockState[]{pot(1)});
            lineWalls(items, 0);
            table(center(), Blocks.SPRUCE_FENCE.getDefaultState(), 4);
        }

        /** Museu de la Terrissa: peces de terrissa en peanyes, vitrines, forn i torn */
        void museum() {
            floorAndLight(Blocks.TERRACOTTA.getDefaultState(), Blocks.RED_TERRACOTTA.getDefaultState());
            // peanyes al mig amb gerres (decorated pot) cada 3 blocs
            for (long c : cells) {
                int a = p.a(c), b = p.bb(c);
                if (Math.floorMod(a, 3) != 1 || Math.floorMod(b, 3) != 1 || !free(c)) continue;
                boolean nearWall = false;
                for (Direction d : Direction.Type.HORIZONTAL) if (!set.contains(p.step(c, d, 1))) nearWall = true;
                if (nearWall) continue;
                put(c, 1, Blocks.POLISHED_ANDESITE.getDefaultState());
                if (head() >= 2) put(c, 2, Blocks.DECORATED_POT.getDefaultState());
                if (head() >= 3) put(c, 3, Blocks.GLASS.getDefaultState());
                used.add(c);
            }
            List<BlockState[]> items = new ArrayList<>();
            BlockState shelf = slabTop(Blocks.SPRUCE_SLAB);
            Block[] glazed = {Blocks.ORANGE_GLAZED_TERRACOTTA, Blocks.BROWN_GLAZED_TERRACOTTA, Blocks.YELLOW_GLAZED_TERRACOTTA,
                    Blocks.WHITE_GLAZED_TERRACOTTA, Blocks.GREEN_GLAZED_TERRACOTTA};
            for (int i = 0; i < 24; i++) {
                switch (i % 6) {
                    case 0 -> items.add(new BlockState[]{Blocks.DECORATED_POT.getDefaultState()});
                    case 1 -> items.add(new BlockState[]{shelf, Blocks.FLOWER_POT.getDefaultState()});
                    case 2 -> items.add(new BlockState[]{glazed[(i / 6) % glazed.length].getDefaultState()});
                    case 3 -> items.add(new BlockState[]{shelf, Blocks.DECORATED_POT.getDefaultState()});
                    case 4 -> items.add(new BlockState[]{Blocks.CLAY.getDefaultState()});
                    default -> items.add(new BlockState[]{Blocks.TERRACOTTA.getDefaultState(), Blocks.FLOWER_POT.getDefaultState()});
                }
            }
            lineWalls(items, 2);
        }

        /** planta de dalt del museu: taller de terrissa (torn, forn, argila) */
        void workshop() {
            floorAndLight(Blocks.SPRUCE_PLANKS.getDefaultState(), Blocks.SPRUCE_PLANKS.getDefaultState());
            List<BlockState[]> items = new ArrayList<>();
            items.add(new BlockState[]{Blocks.BLAST_FURNACE.getDefaultState()}); // forn de coure
            items.add(new BlockState[]{Blocks.BRICKS.getDefaultState(), Blocks.BRICKS.getDefaultState()});
            items.add(new BlockState[]{Blocks.STONECUTTER.getDefaultState()});   // torn
            items.add(new BlockState[]{Blocks.CLAY.getDefaultState()});
            items.add(new BlockState[]{Blocks.CLAY.getDefaultState()});
            items.add(new BlockState[]{water()});
            items.add(new BlockState[]{Blocks.DECORATED_POT.getDefaultState()});
            items.add(new BlockState[]{Blocks.DECORATED_POT.getDefaultState()});
            items.add(new BlockState[]{Blocks.CRAFTING_TABLE.getDefaultState()});
            items.add(new BlockState[]{Blocks.CHEST.getDefaultState()});
            lineWalls(items, 3);
            table(center(), Blocks.SPRUCE_FENCE.getDefaultState(), 2);
        }

        /** Local social: barra, taules i cadires */
        void social() {
            floorAndLight(Blocks.TERRACOTTA.getDefaultState(), Blocks.WHITE_TERRACOTTA.getDefaultState());
            List<BlockState[]> items = new ArrayList<>();
            for (int i = 0; i < 6; i++) items.add(new BlockState[]{up(Blocks.BARREL), Blocks.SPRUCE_SLAB.getDefaultState()}); // barra
            items.add(new BlockState[]{Blocks.JUKEBOX.getDefaultState()});
            items.add(new BlockState[]{Blocks.TARGET.getDefaultState()});           // dards
            items.add(new BlockState[]{pot(2)});
            items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
            items.add(new BlockState[]{pot(4)});
            lineWalls(items, 0);
            // taules en quadrícula
            for (long c : cells) {
                int a = p.a(c), b = p.bb(c);
                if (Math.floorMod(a, 4) == 2 && Math.floorMod(b, 4) == 2) table(c, Blocks.SPRUCE_FENCE.getDefaultState(), 4);
            }
        }

        /** aula: pissarra, taula del mestre i pupitres mirant a la pissarra */
        void classroom() {
            floorAndLight(Blocks.SMOOTH_STONE.getDefaultState(), Blocks.POLISHED_DIORITE.getDefaultState());
            // la pissarra a la paret més llarga del fons
            Map<Direction, Integer> count = new HashMap<>();
            for (Object[] s : wallSlots()) count.merge((Direction) s[1], 1, Integer::sum);
            Direction board = p.right;
            int best = -1;
            for (Map.Entry<Direction, Integer> e : count.entrySet()) if (e.getValue() > best) {
                best = e.getValue();
                board = e.getKey();
            }
            final Direction bd = board;
            List<Long> boardCells = new ArrayList<>();
            for (Object[] s : wallSlots()) if (s[1] == bd) boardCells.add((Long) s[0]);
            boardCells.sort(Comparator.comparingInt(c -> Plan.kx(c) * bd.rotateYClockwise().getOffsetX() + Plan.kz(c) * bd.rotateYClockwise().getOffsetZ()));
            int mid = boardCells.size() / 2;
            for (int i = Math.max(0, mid - 2); i <= Math.min(boardCells.size() - 1, mid + 2); i++) {
                long c = boardCells.get(i);
                if (head() >= 3) {
                    put(c, 2, Blocks.GREEN_TERRACOTTA.getDefaultState());
                    put(c, 3, Blocks.GREEN_TERRACOTTA.getDefaultState());
                }
            }
            if (!boardCells.isEmpty()) {
                long t = p.step(boardCells.get(mid), bd, -2);
                if (free(t)) {
                    put(t, 1, Blocks.SPRUCE_PLANKS.getDefaultState());
                    used.add(t);
                    long ch = p.step(t, bd, 1);
                    if (free(ch)) {
                        put(ch, 1, Pal.stairs(Blocks.SPRUCE_STAIRS, bd));
                        used.add(ch);
                    }
                }
            }
            // pupitres: taula + cadira darrere, mirant a la pissarra
            for (long c : cells) {
                int along = Plan.kx(c) * bd.getOffsetX() + Plan.kz(c) * bd.getOffsetZ();
                int across = Plan.kx(c) * bd.rotateYClockwise().getOffsetX() + Plan.kz(c) * bd.rotateYClockwise().getOffsetZ();
                if (Math.floorMod(along, 3) != 0 || Math.floorMod(across, 2) != 0) continue;
                long chair = p.step(c, bd, -1);
                boolean wallNear = false;
                for (Direction d : Direction.Type.HORIZONTAL) if (!set.contains(p.step(c, d, 1)) || !set.contains(p.step(chair, d, 1))) wallNear = true;
                if (wallNear || !free(c) || !free(chair)) continue;
                // no massa a prop de la pissarra
                boolean nearBoard = false;
                for (int k = 1; k <= 3; k++) if (!set.contains(p.step(c, bd, k))) nearBoard = true;
                if (nearBoard) continue;
                put(c, 1, slabTop(Blocks.BIRCH_SLAB));
                put(chair, 1, Pal.stairs(Blocks.OAK_STAIRS, bd.getOpposite()));
                used.add(c);
                used.add(chair);
            }
            List<BlockState[]> items = new ArrayList<>();
            items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
            items.add(new BlockState[]{Blocks.BOOKSHELF.getDefaultState(), Blocks.BOOKSHELF.getDefaultState()});
            items.add(new BlockState[]{Blocks.LECTERN.getDefaultState()});
            items.add(new BlockState[]{pot(0)});
            items.add(new BlockState[]{Blocks.CHEST.getDefaultState()});
            lineWalls(items, 0);
            // murals de colors a les parets (treballs dels alumnes)
            Block[] wool = {Blocks.YELLOW_WOOL, Blocks.CYAN_WOOL, Blocks.LIME_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL};
            int i = 0;
            for (Object[] s : wallSlots()) {
                long c = (Long) s[0];
                if (s[1] == bd || head() < 3 || (i++ % 4) != 0) continue;
                if (!p.blocks.containsKey(Plan.k3(Plan.kx(c), fy + 3, Plan.kz(c)))) put(c, 3, wool[(i / 4) % wool.length].getDefaultState());
            }
        }

        /** pavelló: pista amb línies, cistelles als extrems i grades en un costat */
        void gym() {
            int amin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE, bmin = Integer.MAX_VALUE, bmax = Integer.MIN_VALUE;
            for (long c : cells) {
                amin = Math.min(amin, p.a(c));
                amax = Math.max(amax, p.a(c));
                bmin = Math.min(bmin, p.bb(c));
                bmax = Math.max(bmax, p.bb(c));
            }
            boolean longB = (bmax - bmin) >= (amax - amin);
            BlockState court = Blocks.LIGHT_BLUE_TERRACOTTA.getDefaultState();
            BlockState line = Blocks.WHITE_CONCRETE.getDefaultState();
            BlockState out = Blocks.GREEN_TERRACOTTA.getDefaultState();
            int la0 = amin + 2, la1 = amax - 2, lb0 = bmin + 2, lb1 = bmax - 2;
            int midL = longB ? (lb0 + lb1) / 2 : (la0 + la1) / 2;
            for (long c : cells) {
                int a = p.a(c), b = p.bb(c);
                boolean in = a >= la0 && a <= la1 && b >= lb0 && b <= lb1;
                boolean edge = in && (a == la0 || a == la1 || b == lb0 || b == lb1);
                boolean mid = in && (longB ? b == midL : a == midL);
                put(c, 0, !in ? out : (edge || mid) ? line : court);
            }
            // grades al llarg d'un costat llarg (fora de la pista)
            for (long c : cells) {
                int a = p.a(c), b = p.bb(c);
                int d = longB ? (amax - a) : (bmax - b);
                if (d < 0 || d > 1) continue;
                Direction face = longB ? p.front : p.right.getOpposite();
                for (int y = 1; y <= d; y++) put(c, y, Blocks.SPRUCE_PLANKS.getDefaultState());
                put(c, d + 1, Pal.stairs(Blocks.SPRUCE_STAIRS, face.getOpposite()));
                used.add(c);
            }
            // cistelles als dos extrems
            for (int end = 0; end < 2; end++) {
                Long target = null;
                for (long c : cells) {
                    int a = p.a(c), b = p.bb(c);
                    boolean ok = longB ? (b == (end == 0 ? bmin : bmax) && a == (amin + amax) / 2)
                            : (a == (end == 0 ? amin : amax) && b == (bmin + bmax) / 2);
                    if (ok) target = c;
                }
                if (target == null || head() < 6) continue;
                Direction into = longB ? (end == 0 ? p.right : p.right.getOpposite()) : (end == 0 ? p.inward : p.front);
                for (int s = -1; s <= 1; s++) {
                    long c = p.step(target, into.rotateYClockwise(), s);
                    put(c, 5, Blocks.WHITE_CONCRETE.getDefaultState());
                    put(c, 6, Blocks.WHITE_CONCRETE.getDefaultState());
                }
                put(p.step(target, into, 1), 5, Blocks.ORANGE_STAINED_GLASS_PANE.getDefaultState());
            }
            light();
        }

        /** El teu Súper: passadissos de prestatges, neveres al fons, fruita i caixa a l'entrada */
        void supermarket() {
            floorAndLight(Blocks.SMOOTH_STONE.getDefaultState(), Blocks.WHITE_TERRACOTTA.getDefaultState());
            int amin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE;
            for (long c : cells) {
                amin = Math.min(amin, p.a(c));
                amax = Math.max(amax, p.a(c));
            }
            // neveres a la paret del fons
            for (Object[] s : wallSlots()) {
                long c = (Long) s[0];
                if (p.a(c) == amax && free(c)) {
                    put(c, 1, Blocks.WHITE_CONCRETE.getDefaultState());
                    if (head() >= 2) put(c, 2, Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState());
                    used.add(c);
                }
            }
            // prestatges en files perpendiculars a la façana, deixant l'entrada lliure
            Block[] goods = {Blocks.BOOKSHELF, Blocks.BARREL, Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.BOOKSHELF};
            for (long c : cells) {
                int a = p.a(c), b = p.bb(c);
                if (a < amin + 4 || a > amax - 2 || Math.floorMod(b, 3) != 0 || !free(c)) continue;
                boolean side = false;
                for (Direction d : Direction.Type.HORIZONTAL) if (!set.contains(p.step(c, d, 1))) side = true;
                if (side) continue;
                put(c, 1, up(Blocks.BARREL));
                if (head() >= 2) put(c, 2, goods[Math.floorMod(a * 7 + b, goods.length)].getDefaultState());
                used.add(c);
            }
            // caixa a prop de l'entrada
            Long cc = null;
            for (long c : cells) if (p.a(c) == amin + 2 && free(c)) {
                cc = c;
                break;
            }
            if (cc != null) {
                put(cc, 1, Blocks.SMOOTH_STONE.getDefaultState());
                if (head() >= 2) put(cc, 2, Blocks.STONECUTTER.getDefaultState());
                used.add(cc);
                long ch = p.step(cc, p.right, 1);
                if (free(ch)) {
                    put(ch, 1, Pal.stairs(Blocks.SPRUCE_STAIRS, p.right));
                    used.add(ch);
                }
            }
        }

        /** botiga de la planta baixa (El Cuc de Seda: teixits) */
        void shop() {
            floorAndLight(Blocks.OAK_PLANKS.getDefaultState(), Blocks.OAK_PLANKS.getDefaultState());
            Block[] wool = {Blocks.RED_WOOL, Blocks.WHITE_WOOL, Blocks.PINK_WOOL, Blocks.LIGHT_BLUE_WOOL, Blocks.YELLOW_WOOL,
                    Blocks.LIME_WOOL, Blocks.PURPLE_WOOL, Blocks.ORANGE_WOOL};
            List<BlockState[]> items = new ArrayList<>();
            items.add(new BlockState[]{up(Blocks.BARREL), Blocks.SMOOTH_STONE_SLAB.getDefaultState()}); // taulell
            items.add(new BlockState[]{up(Blocks.BARREL), Blocks.SMOOTH_STONE_SLAB.getDefaultState()});
            items.add(new BlockState[]{Blocks.LOOM.getDefaultState()});
            for (int i = 0; i < 10; i++)
                items.add(new BlockState[]{wool[i % wool.length].getDefaultState(), wool[(i + 3) % wool.length].getDefaultState()});
            items.add(new BlockState[]{pot(1)});
            lineWalls(items, 0);
            rug(center(), Blocks.RED_CARPET, 1, 0);
        }
    }

}
