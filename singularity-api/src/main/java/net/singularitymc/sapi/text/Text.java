package net.singularitymc.sapi.text;

/** Colour and format codes for messages ({@code &a} green, {@code &l} bold, {@code &r} reset, ...). */
public final class Text {
    private static final String CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private Text() {}

    /** Turns {@code &x} codes into Minecraft's {@code §x} codes. {@code &&} gives a plain {@code &}. */
    public static String colorize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char n = text.charAt(i + 1);
                if (n == '&') {
                    out.append('&');
                    i++;
                    continue;
                }
                if (CODES.indexOf(n) >= 0) {
                    out.append('§').append(Character.toLowerCase(n));
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Removes {@code §x} codes. */
    public static String strip(String text) {
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
}
