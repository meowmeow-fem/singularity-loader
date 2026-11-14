package net.singularitymc.loader.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.singularitymc.loader.util.Log;

/** {@code ServerEula.txt}: reads the answer, and asks the owner on the console when there is none yet. */
public final class EulaGate {
    static final String TEMPLATE = "/net/singularitymc/loader/server/ServerEula.default.txt";
    public static final String EULA_URL = "https://www.minecraft.net/en-us/eula";
    private static final Pattern LINE = Pattern.compile("(?mi)^\\s*eula\\s*=\\s*(\\S+)\\s*$");

    private EulaGate() {}

    /** True if the file says {@code eula=true}. A missing file or any other value counts as not accepted. */
    public static boolean isAccepted(Path file) {
        try {
            return Files.isRegularFile(file) && parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean parse(String text) {
        Matcher m = LINE.matcher(text);
        boolean accepted = false;
        while (m.find()) {
            accepted = m.group(1).equalsIgnoreCase("true"); // the last line wins
        }
        return accepted;
    }

    /** Writes {@code ServerEula.txt} from the template with the given answer (keeps the explanatory header). */
    public static void write(Path file, boolean accepted) throws IOException {
        String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : template();
        text = withAnswer(text, accepted);
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    static String withAnswer(String text, boolean accepted) {
        String value = "eula=" + accepted;
        Matcher m = LINE.matcher(text);
        if (m.find()) {
            return m.replaceAll(Matcher.quoteReplacement(value));
        }
        return text + (text.endsWith("\n") ? "" : "\n") + value + "\n";
    }

    /**
     * Makes sure there is an answer. If the file already says {@code true}, that is it. Otherwise the owner is asked
     * on the console; {@code yes} writes {@code eula=true}, anything else writes {@code eula=false}.
     *
     * @param in console input, or {@code null} when there is no way to ask
     * @return whether the EULA is accepted
     */
    public static boolean ensure(Path file, Log log, BufferedReader in) throws IOException {
        if (isAccepted(file)) {
            return true;
        }
        if (!Files.isRegularFile(file)) {
            write(file, false);
        }
        if (in == null) {
            log.error("The Minecraft EULA has not been accepted. Set eula=true in {} to accept it ({}).", file.getFileName(), EULA_URL);
            return false;
        }
        log.info("Would You Like To Accept The Minecraft Eula? Please Type Yes If You Wish To Otherwise Type No");
        System.out.print("Enter Your Answer Here: ");
        System.out.flush();
        String answer = in.readLine();
        System.out.println();
        boolean yes = answer != null && (answer.strip().equalsIgnoreCase("yes") || answer.strip().equalsIgnoreCase("y"));
        write(file, yes);
        return yes;
    }

    /** Console reader for {@link #ensure}. */
    public static BufferedReader console() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    private static String template() throws IOException {
        try (InputStream in = EulaGate.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                return "eula=false\n";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
