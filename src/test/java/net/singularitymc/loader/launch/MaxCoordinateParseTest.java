package net.singularitymc.loader.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MaxCoordinateParseTest {
    @Test
    void readsDigitsWithStraySymbols() {
        assertEquals(4503599627370496L, SingularityLauncher.parseCoordinate("4503599627370496"));
        assertEquals(4503599627370496L, SingularityLauncher.parseCoordinate("• 4503599627370496"));
        assertEquals(140737488355328L, SingularityLauncher.parseCoordinate("\"140_737_488_355_328\""));
        assertEquals(30000000L, SingularityLauncher.parseCoordinate(" 30,000,000 "));
    }

    @Test
    void readsPowersOfTwo() {
        assertEquals(1L << 52, SingularityLauncher.parseCoordinate("2^52"));
        assertEquals(1L << 47, SingularityLauncher.parseCoordinate("\"2^47\""));
    }

    @Test
    void rejectsWhatIsNotANumber() {
        assertEquals(-1, SingularityLauncher.parseCoordinate("far"));
        assertEquals(-1, SingularityLauncher.parseCoordinate(""));
        assertEquals(-1, SingularityLauncher.parseCoordinate("3^5"));
        assertEquals(-1, SingularityLauncher.parseCoordinate("99999999999999999999"));
    }
}
