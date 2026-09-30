package cat.quart.mod.town;

/** Codis de superfície del ràster (han de coincidir amb tools/build_town.py). */
public final class Surf {
    public static final int GRASS = 0, FOREST = 1, FARMLAND = 2, ROAD = 3, MARKING = 4, SIDEWALK = 5, PATH = 6,
            TRACK = 7, CYCLEWAY = 8, PARKING = 9, PLAZA = 10, PARK = 11, TURF = 12, TURF_LINE = 13, COURT = 14,
            WATER = 15, POOL = 16, GARDEN = 17, SAND = 18, GRAVEL = 19, SHRUB = 20, BARE = 21, CEMETERY = 22,
            PLAZA_TREES = 23, STREAM = 24, MEADOW = 25, COURT_LINE = 26, ROAD_MAIN = 27;

    public static final int DECO_WALL = 1, DECO_HEDGE = 2, DECO_FENCE = 3, DECO_RETAINING = 4;

    private Surf() {
    }

    public static boolean isRoad(int s) {
        return s == ROAD || s == ROAD_MAIN || s == MARKING;
    }

    public static boolean isPaved(int s) {
        return isRoad(s) || s == SIDEWALK || s == CYCLEWAY || s == PARKING || s == PLAZA || s == COURT
                || s == COURT_LINE || s == TURF || s == TURF_LINE;
    }

    /** superfícies que es mantenen al marge de transició (els carrers continuen fora del poble) */
    public static boolean continuesInMargin(int s) {
        return isRoad(s) || s == PATH || s == TRACK || s == CYCLEWAY;
    }
}
