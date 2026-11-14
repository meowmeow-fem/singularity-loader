package net.singularitymc.servermanager;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture of {@code /singularity}: a black hole with its glowing ring, drawn with one chat character. Minecraft's
 * chat font is not monospaced, so the picture uses the full block for every cell (the dark ones too): every cell is
 * exactly as wide as the next and the rows line up on any client.
 */
final class Banner {
    private Banner() {}

    /** One character per cell: a colour code (0-9, a-f) or '.' for the night around it. */
    private static final String[] ART = {
        "....55555....",
        "..5dd666dd5..",
        ".5d6eeeee6d5.",
        ".5d6e000e6d5.",
        ".5d6eeeee6d5.",
        "..5dd666dd5..",
        "....55555....",
    };

    /** The picture with {@code beside} written to the right of it, one text per row (fewer is fine). */
    static List<String> lines(List<String> beside) {
        List<String> out = new ArrayList<>();
        for (int row = 0; row < ART.length; row++) {
            StringBuilder line = new StringBuilder();
            char colour = 0;
            for (char cell : ART[row].toCharArray()) {
                char want = cell == '.' ? '0' : cell;
                if (want != colour) {
                    line.append('§').append(want);
                    colour = want;
                }
                line.append('█');
            }
            if (row < beside.size()) {
                line.append("§r  ").append(beside.get(row));
            }
            out.add(line.toString());
        }
        return out;
    }

    static int rows() {
        return ART.length;
    }
}
