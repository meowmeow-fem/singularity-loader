package poc;

/** Uses Pos the way game code uses BlockPos. Prints the same lines before and after widening. */
public class Walk {
    public static void main(String[] args) {
        Pos start = new Pos(2_000_000_000, 64, -2_000_000_000);
        Pos far = start.offset(500_000_000, 0, -500_000_000);
        System.out.println("far      = " + far);
        System.out.println("chunk    = " + far.chunkX() + ", " + far.chunkZ());
        int[] column = new int[16];
        for (int i = 0; i < 40; i++) {
            Pos p = far.offset(i, 0, 0);
            column[p.localX()]++;
        }
        System.out.println("column   = " + column[0] + " " + column[15]);
        System.out.println("distance = " + far.distManhattan(start));
        System.out.println("past 2^31 = " + far.isFartherThan(Integer.MAX_VALUE));
        long sum = 0;
        for (int s = 0; s < 3; s++) {
            sum += far.offset(s * 1_000_000_000, 0, 0).getX();
        }
        System.out.println("sum of X = " + sum);
    }
}
