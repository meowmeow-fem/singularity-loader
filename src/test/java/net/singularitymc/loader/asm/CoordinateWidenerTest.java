package net.singularitymc.loader.asm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.singularitymc.loader.asm.fixture.Driver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CoordinateWidenerTest {
    private static final String PKG = "net/singularitymc/loader/asm/fixture/";
    private static final List<String> FIXTURES = List.of("Positioned", "Point", "Moved", "Column", "Mutable", "Driver");
    private static CoordinateWidener.Result result;
    private static ClassLoader widened;

    @BeforeAll
    static void widen() throws IOException {
        Map<String, byte[]> in = new LinkedHashMap<>();
        for (String n : FIXTURES) in.put(PKG + n, bytes(PKG + n));
        CoordinateWidener w = new CoordinateWidener(in, CoordinateWidenerTest::outside);
        result = w.widen(List.of(PKG + "Point.x", PKG + "Point.z", PKG + "Column.x", PKG + "Column.z", PKG + "Mutable.x"));
        Map<String, byte[]> out = result.classes();
        widened = new ClassLoader(CoordinateWidenerTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] b = out.get(name.replace('.', '/'));
                    if (b == null) return super.loadClass(name, resolve);
                    Class<?> c = findLoadedClass(name);
                    return c != null ? c : defineClass(name, b, 0, b.length);
                }
            }
        };
    }

    private static byte[] outside(String name) {
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = CoordinateWidenerTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            return in.readAllBytes();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> runWidened() throws Exception {
        return (List<String>) widened.loadClass("net.singularitymc.loader.asm.fixture.Driver").getMethod("run").invoke(null);
    }

    @Test
    void scopedModeCarriesThroughCodeOutsideTheSet() throws Exception {
        Map<String, byte[]> in = new LinkedHashMap<>();
        for (String n : List.of("Locator", "Spot")) in.put(PKG + n, bytes(PKG + n));
        CoordinateWidener.Result r = new CoordinateWidener(in, CoordinateWidenerTest::outside, Map.of(), true,
            "net/singularitymc/loader/util/WidePacking").widen(List.of(PKG + "Spot.x"));
        assertEquals(List.of(), r.report().problems());
        Map<String, byte[]> out = new LinkedHashMap<>(r.classes());
        out.put(PKG + "OldLocator", bytes(PKG + "OldLocator")); // outside the set: the original class
        ClassLoader cl = new ClassLoader(CoordinateWidenerTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    byte[] b = out.get(name.replace('.', '/'));
                    if (b == null) return super.loadClass(name, resolve);
                    Class<?> c = findLoadedClass(name);
                    return c != null ? c : defineClass(name, b, 0, b.length);
                }
            }
        };
        Class<?> spot = cl.loadClass("net.singularitymc.loader.asm.fixture.Spot");
        Class<?> locator = cl.loadClass("net.singularitymc.loader.asm.fixture.Locator");
        Object far = spot.getConstructor(int.class).newInstance(0);
        spot.getField("x").setLong(far, 3_000_000_000L);
        Object near = spot.getConstructor(int.class).newInstance(5);
        Object old = cl.loadClass("net.singularitymc.loader.asm.fixture.OldLocator").getConstructor().newInstance();
        java.lang.reflect.Method through = java.util.Arrays.stream(spot.getMethods())
            .filter(m -> m.getName().equals("through") && m.getReturnType() == long.class).findFirst().orElseThrow();
        assertEquals(3_000_000_005L, through.invoke(far, near), "widened implementor");
        assertEquals(3_000_000_001L, through.invoke(far, old), "int-only implementor outside the set, carried back");
        assertTrue(java.util.Arrays.stream(locator.getMethods()).anyMatch(m -> m.getName().equals("locate") && m.getReturnType() == int.class
            && java.lang.reflect.Modifier.isAbstract(m.getModifiers())), "the int method stays abstract for outside implementors");
    }

    @Test
    void noProblemsReported() {
        assertEquals(List.of(), result.report().problems());
        assertTrue(result.report().bridges() >= 1, "Point::x needs a bridge");
    }

    @Test
    void theOriginalOverflows() {
        assertEquals("point (-1794967296, 1794967296)", Driver.run().get(0));
    }

    @Test
    void widenedCoordinatesAreRight() throws Exception {
        List<String> got = runWidened();
        List<String> want = List.of(
            "point (2500000000, -2500000000)",       // fields, constructor, offset
            "chunkX 156250000",                      // interface default method
            "moved 3000000000",                      // override + super call
            "interface 3000000000",                  // call through the interface
            "compare 1",                             // Integer.compare -> Long.compare (narrowing would say -1)
            "hash true",                             // hashCode keeps its JDK descriptor
            "equals true",
            "column Column[x=2100000000, z=0] 4200000005", // record toString (ObjectMethods) and Math.abs(long)
            "record equals true",
            "captured 2500000000",                   // lambda capture widened
            "method ref -1794967296",                // ToIntFunction must return an int: the bridge narrows
            "toString (2500000000, -2500000000)",    // method reference through a bridge-free path
            "mutable 3000000000",                    // x += d returning the new value (DUP_X1 over a long)
            "array 2 1");                            // array index narrowed back to int
        assertEquals(want, got);
    }

    @Test
    void descriptorsChangeOnlyWhereAllowed() throws Exception {
        Class<?> point = widened.loadClass("net.singularitymc.loader.asm.fixture.Point");
        assertEquals(long.class, point.getDeclaredField("x").getType());
        assertEquals(long.class, point.getMethod("x").getReturnType());
        assertEquals(int.class, point.getMethod("hashCode").getReturnType());
        Class<?> column = widened.loadClass("net.singularitymc.loader.asm.fixture.Column");
        assertEquals(long.class, column.getRecordComponents()[0].getType());
        Class<?> positioned = widened.loadClass("net.singularitymc.loader.asm.fixture.Positioned");
        assertEquals(long.class, positioned.getMethod("x").getReturnType());
        assertNotEquals(0, result.report().rewrittenInstructions());
    }
}
