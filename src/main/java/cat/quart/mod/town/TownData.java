package cat.quart.mod.town;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Dades del poble generades per tools/build_town.py: un ràster amb una columna per bloc
 * (1 bloc = 1 metre real) i la taula d'edificis i decoracions.
 */
public final class TownData {
    public static final int FU_NONE = 255;
    /** bits de la capa de vores */
    public static final int F_CORNER = 1, F_DOOR = 2, F_FACADE = 4, F_HASDIR = 8, F_GABLE_END = 64;

    private static volatile TownData instance;

    public final int x0, z0, w, h;
    public final int coreX0, coreZ0, coreX1, coreZ1;
    public final int margin;
    public final int spawnX, spawnY, spawnZ;

    private final short[] height;
    private final byte[] surface;
    private final short[] building;
    private final byte[] roofDist;
    private final byte[] facadeU;
    private final byte[] flags;
    private final byte[] deco;

    public final List<Building> buildings = new ArrayList<>();
    public final List<Decoration> decorations = new ArrayList<>();
    public final List<Fence> fences = new ArrayList<>();

    public static TownData get() {
        TownData d = instance;
        if (d == null) {
            synchronized (TownData.class) {
                d = instance;
                if (d == null) {
                    try {
                        d = instance = load();
                    } catch (IOException e) {
                        throw new RuntimeException("No s'han pogut carregar les dades del poble de Quart", e);
                    }
                }
            }
        }
        return d;
    }

    private static TownData load() throws IOException {
        try (InputStream raw = TownData.class.getResourceAsStream("/data/quartmod/town/raster.bin.gz");
             InputStream metaIn = TownData.class.getResourceAsStream("/data/quartmod/town/meta.json")) {
            if (raw == null || metaIn == null) throw new IOException("falten els fitxers de dades del poble");
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(raw, 1 << 16), 1 << 16));
            JsonObject meta = new Gson().fromJson(new InputStreamReader(metaIn, StandardCharsets.UTF_8), JsonObject.class);
            return new TownData(in, meta);
        }
    }

    private TownData(DataInputStream in, JsonObject meta) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'Q' || magic[1] != 'R' || magic[2] != 'T' || magic[3] != '1') throw new IOException("format incorrecte");
        int version = in.readInt();
        if (version != 2) throw new IOException("versió de dades no suportada: " + version);
        x0 = in.readInt();
        z0 = in.readInt();
        w = in.readInt();
        h = in.readInt();
        coreX0 = in.readInt();
        coreZ0 = in.readInt();
        coreX1 = in.readInt();
        coreZ1 = in.readInt();
        margin = coreX0 - x0;
        int n = w * h;
        height = new short[n];
        for (int i = 0; i < n; i++) height[i] = in.readShort();
        surface = new byte[n];
        in.readFully(surface);
        building = new short[n];
        for (int i = 0; i < n; i++) building[i] = in.readShort();
        roofDist = new byte[n];
        in.readFully(roofDist);
        facadeU = new byte[n];
        in.readFully(facadeU);
        flags = new byte[n];
        in.readFully(flags);
        deco = new byte[n];
        in.readFully(deco);

        JsonArray sp = meta.getAsJsonArray("spawn");
        spawnX = sp.get(0).getAsInt();
        spawnY = sp.get(1).getAsInt();
        spawnZ = sp.get(2).getAsInt();

        JsonArray bs = meta.getAsJsonArray("buildings");
        for (int i = 0; i < bs.size(); i++) {
            buildings.add(i == 0 ? null : Building.fromJson(i, bs.get(i).getAsJsonObject()));
        }
        // amplada de façana dels edificis destacats (si no ve a les metadades)
        for (int i = 0; i < n; i++) {
            int u = facadeU[i] & 0xFF;
            if ((flags[i] & F_FACADE) != 0 && u < FU_NONE) {
                Building b = buildings.get(building[i] & 0xFFFF);
                if (b != null && b.fwFromMeta == 0 && u + 1 > b.facadeWidth) b.facadeWidth = u + 1;
            }
        }
        if (meta.has("fences")) {
            for (JsonElement e : meta.getAsJsonArray("fences")) fences.add(new Fence(e.getAsJsonObject()));
        }
        for (JsonElement e : meta.getAsJsonArray("decorations")) {
            decorations.add(Decoration.fromJson(e.getAsJsonObject()));
        }
    }

    // ------------------------------------------------------------------ consultes

    public boolean inRaster(int x, int z) {
        return x >= x0 && z >= z0 && x < x0 + w && z < z0 + h;
    }

    public boolean inCore(int x, int z) {
        return x >= coreX0 && z >= coreZ0 && x < coreX1 && z < coreZ1;
    }

    /** true si el chunk és completament dins del nucli (el nucli està alineat a chunks). */
    public boolean chunkInCore(int chunkX, int chunkZ) {
        return inCore(chunkX << 4, chunkZ << 4);
    }

    public boolean chunkTouchesRaster(int chunkX, int chunkZ, int extra) {
        int bx = chunkX << 4, bz = chunkZ << 4;
        return bx + 16 > x0 - extra && bz + 16 > z0 - extra && bx < x0 + w + extra && bz < z0 + h + extra;
    }

    /** 0 dins del nucli, 1 a la vora exterior del marge. */
    public double blend(int x, int z) {
        int dx = Math.max(Math.max(coreX0 - x, x - (coreX1 - 1)), 0);
        int dz = Math.max(Math.max(coreZ0 - z, z - (coreZ1 - 1)), 0);
        double d = Math.max(dx, dz);
        return Math.min(1.0, d / margin);
    }

    private int idx(int x, int z) {
        return (z - z0) * w + (x - x0);
    }

    public int height(int x, int z) {
        return height[idx(x, z)];
    }

    public int surface(int x, int z) {
        return surface[idx(x, z)] & 0xFF;
    }

    public int buildingId(int x, int z) {
        if (!inRaster(x, z)) return 0;
        return building[idx(x, z)] & 0xFFFF;
    }

    public Building building(int x, int z) {
        int id = buildingId(x, z);
        return id == 0 ? null : buildings.get(id);
    }

    public int roofDist(int x, int z) {
        return roofDist[idx(x, z)] & 0xFF;
    }

    public int facadeU(int x, int z) {
        return facadeU[idx(x, z)] & 0xFF;
    }

    public int flags(int x, int z) {
        return flags[idx(x, z)] & 0xFF;
    }

    public int deco(int x, int z) {
        return deco[idx(x, z)] & 0xFF;
    }

    /** superfície segura fora del ràster */
    public int surfaceOr(int x, int z, int def) {
        return inRaster(x, z) ? surface(x, z) : def;
    }

    // ------------------------------------------------------------------ tipus

    public static final class Building {
        public final int id;
        public final boolean skip;
        public final String wall, accent, roof, roofType;
        public final int floors, floorHeight, base;
        public final String landmark;
        public final String[] facade;
        public final String[] band;
        public final boolean repeat, eave;
        public final String plinth, rail, garage, shutter;
        public final int canopyFloor;
        public int facadeWidth;
        int fwFromMeta;

        private Building(int id, JsonObject o) {
            this.id = id;
            skip = o.has("skip");
            wall = str(o, "wall", "minecraft:white_terracotta");
            accent = str(o, "accent", null);
            roof = str(o, "roof", "bricks");
            roofType = str(o, "roof_type", "hipped");
            floors = o.has("floors") ? o.get("floors").getAsInt() : 2;
            floorHeight = o.has("fh") ? o.get("fh").getAsInt() : 3;
            base = o.has("base") ? o.get("base").getAsInt() : 64;
            landmark = str(o, "landmark", null);
            facade = arr(o, "facade");
            band = arr(o, "band");
            repeat = o.has("repeat") && o.get("repeat").getAsBoolean();
            eave = o.has("eave") && o.get("eave").getAsBoolean();
            plinth = str(o, "plinth", null);
            rail = str(o, "rail", "minecraft:iron_bars");
            garage = str(o, "garage", null);
            shutter = str(o, "shutter", "minecraft:white_concrete");
            canopyFloor = o.has("canopy_floor") ? o.get("canopy_floor").getAsInt() : -1;
            if (o.has("fw")) facadeWidth = fwFromMeta = o.get("fw").getAsInt();
        }

        static Building fromJson(int id, JsonObject o) {
            return new Building(id, o);
        }

        public int wallTop() {
            return base + floors * floorHeight;
        }

        private static String str(JsonObject o, String k, String def) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
        }

        private static String[] arr(JsonObject o, String k) {
            if (!o.has(k) || !o.get(k).isJsonArray()) return new String[0];
            JsonArray a = o.getAsJsonArray(k);
            String[] r = new String[a.size()];
            for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsString();
            return r;
        }
    }

    public static final class Fence {
        public final String base, top;

        Fence(JsonObject o) {
            base = o.has("base") ? o.get("base").getAsString() : "minecraft:bricks";
            top = o.has("top") && !o.get("top").isJsonNull() ? o.get("top").getAsString() : null;
        }
    }

    public static final class Decoration {
        public final String type;
        public final int x, y, z, yaw, count, length, height, width, depth;
        public final double hx, hz;
        public final String roof, wall;

        private Decoration(JsonObject o) {
            type = o.get("type").getAsString();
            x = o.get("x").getAsInt();
            y = o.get("y").getAsInt();
            z = o.get("z").getAsInt();
            yaw = o.has("yaw") ? o.get("yaw").getAsInt() : 0;
            count = o.has("count") ? o.get("count").getAsInt() : 1;
            length = o.has("length") ? o.get("length").getAsInt() : 20;
            height = o.has("height") ? o.get("height").getAsInt() : 20;
            width = o.has("width") ? o.get("width").getAsInt() : 3;
            depth = o.has("depth") ? o.get("depth").getAsInt() : 2;
            hx = o.has("hx") ? o.get("hx").getAsDouble() : 1;
            hz = o.has("hz") ? o.get("hz").getAsDouble() : 0;
            roof = o.has("roof") ? o.get("roof").getAsString() : "bricks";
            wall = o.has("wall") ? o.get("wall").getAsString() : "minecraft:bricks";
        }

        /** zona on no s'han de plantar arbres aleatoris */
        public boolean blocksTrees(int px, int pz) {
            if (type.equals("pocket_park")) {
                double ax = px - x, az = pz - z;
                double t = ax * hx + az * hz, n = Math.abs(-ax * hz + az * hx);
                return t > -3 && t < length + 3 && n < 8;
            }
            if (type.equals("goal") || type.equals("hoop") || type.equals("roof_chimney")) return false;
            int r = radius() - 2;
            return Math.abs(px - x) <= r && Math.abs(pz - z) <= r;
        }

        static Decoration fromJson(JsonObject o) {
            return new Decoration(o);
        }

        /** radi aproximat que ocupa, per saber si toca un chunk */
        public int radius() {
            return switch (type) {
                case "zipline" -> length + 4;
                case "pocket_park" -> length + 12;
                case "benches_plaza" -> 14;
                case "locomotive" -> 9;
                case "playground" -> 8;
                case "goal", "hoop" -> 5;
                default -> 6;
            };
        }
    }
}
