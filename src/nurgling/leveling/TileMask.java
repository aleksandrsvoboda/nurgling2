package nurgling.leveling;

/** One bit per tile of a 100x100 map grid, row by row. */
public final class TileMask {
    public static final int SIZE = 100;
    public static final int WORDS = ((SIZE * SIZE) + 63) / 64;

    private TileMask() {}

    public static long[] create() {
        return(new long[WORDS]);
    }

    public static boolean get(long[] m, int x, int y) {
        if((x < 0) || (y < 0) || (x >= SIZE) || (y >= SIZE))
            return(false);
        int i = (y * SIZE) + x;
        return((m[i >>> 6] & (1L << (i & 63))) != 0);
    }

    /** Sets or clears a tile in place; only for masks not yet published in a plan. */
    public static void put(long[] m, int x, int y, boolean on) {
        int i = (y * SIZE) + x;
        if(on)
            m[i >>> 6] |= (1L << (i & 63));
        else
            m[i >>> 6] &= ~(1L << (i & 63));
    }

    public static boolean empty(long[] m) {
        for(long l : m) {
            if(l != 0)
                return(false);
        }
        return(true);
    }

    public static int count(long[] m) {
        int n = 0;
        for(long l : m)
            n += Long.bitCount(l);
        return(n);
    }
}
