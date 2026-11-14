package net.singularitymc.sapi.internal;

import java.util.ArrayList;
import java.util.List;

/** Splits the text after a command's name into words. */
public final class Arguments {
    private Arguments() {}

    /** Words separated by spaces; runs of spaces count as one. {@code ""} gives no words. */
    public static String[] split(String line) {
        List<String> out = new ArrayList<>();
        for (String w : line.trim().split(" +")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * For completion: like {@link #split}, but the last word is the one being typed, so a trailing space (or an
     * empty line) adds an empty last word.
     */
    public static String[] splitForCompletion(String line) {
        String[] words = split(line);
        if (line.isEmpty() || line.endsWith(" ")) {
            String[] more = java.util.Arrays.copyOf(words, words.length + 1);
            more[words.length] = "";
            return more;
        }
        return words;
    }
}
