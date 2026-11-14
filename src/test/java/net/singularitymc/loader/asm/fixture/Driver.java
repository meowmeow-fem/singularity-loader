package net.singularitymc.loader.asm.fixture;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Test fixture: uses the other fixtures the way game code uses positions; its output is compared before/after. */
public final class Driver {
    private Driver() {}

    public static List<String> run() {
        List<String> out = new ArrayList<>();
        Point p = new Point(2_000_000_000, -2_000_000_000).offset(500_000_000, -500_000_000);
        out.add("point " + p);
        out.add("chunkX " + p.chunkX());
        Moved m = new Moved(2_000_000_000, 0).shift(1_000_000_000);
        out.add("moved " + m.x());
        Positioned asInterface = m;
        out.add("interface " + asInterface.x());
        out.add("compare " + p.compareTo(new Point(0, 0)));
        Point same = new Point(2_000_000_000, -2_000_000_000).offset(500_000_000, -500_000_000);
        out.add("hash " + (p.hashCode() == same.hashCode()));
        out.add("equals " + p.equals(same));
        Column c = new Column(2_100_000_000, 0);
        Column d = new Column(-2_100_000_000, 5);
        out.add("column " + c + " " + c.distance(d));
        out.add("record equals " + c.equals(new Column(2_100_000_000, 0)));
        int px = p.x();
        Supplier<String> captured = () -> "captured " + px;
        out.add(captured.get());
        ToIntFunction<Point> methodRef = Point::x;
        out.add("method ref " + methodRef.applyAsInt(p));
        Function<Point, String> describe = Point::toString;
        out.add("toString " + describe.apply(p));
        Mutable mu = new Mutable();
        mu.setX(2_000_000_000);
        out.add("mutable " + mu.addX(1_000_000_000));
        int[] counts = new int[16];
        for (int i = 0; i < 20; i++) {
            counts[p.offset(i, 0).x() & 15]++;
        }
        out.add("array " + counts[0] + " " + counts[15]);
        return out;
    }
}
