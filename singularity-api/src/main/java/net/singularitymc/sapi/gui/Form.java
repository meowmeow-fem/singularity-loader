package net.singularitymc.sapi.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.internal.Forms;

/**
 * A window with text, text fields and buttons that a player fills in (since API 4.4). It is one of the game's own
 * dialogs, so every client of Minecraft 26.1 or later shows it without a mod. Use it on the server thread.
 *
 * <pre>{@code
 * Form.create("Log in")
 *     .text("Welcome back. Enter your password.")
 *     .field("password", "Password", 64)
 *     .button("ok", "Log in")
 *     .onAnswer(answer -> check(answer.player(), answer.value("password")))
 *     .show(player);
 * }</pre>
 *
 * A player has at most one form open; showing another replaces it. An answer is accepted once, only from the
 * player the form was shown to, and its texts are cut to the fields' lengths: the client is not trusted. The game
 * has no field that hides what is typed, so a password is visible on the player's own screen while they type it.
 */
public final class Form {
    private static final Pattern KEY = Pattern.compile("[a-z0-9_]{1,32}");

    /** A line of text to type into. */
    public record Field(String key, String label, int maxLength) {}

    public record Button(String id, String label) {}

    /** What a player sent back. */
    public record Answer(Player player, String button, Map<String, String> values) {
        /** The text of a field, {@code ""} if the client left it out. */
        public String value(String key) {
            return values.getOrDefault(key, "");
        }
    }

    private final String title;
    private final List<String> text = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<Button> buttons = new ArrayList<>();
    private boolean closable = true;
    private Consumer<Answer> onAnswer = answer -> {};

    private Form(String title) {
        this.title = title;
    }

    public static Form create(String title) {
        return new Form(title == null ? "" : title);
    }

    /** Adds a paragraph above the fields; {@code §} colour codes work. */
    public Form text(String paragraph) {
        text.add(paragraph);
        return this;
    }

    /** Adds a text field; {@code key} (a-z, 0-9, _) names it in the answer. */
    public Form field(String key, String label, int maxLength) {
        if (!KEY.matcher(key).matches() || key.startsWith("sapi_")) {
            throw new IllegalArgumentException("'" + key + "' is not a field key (a-z, 0-9, _; not starting with sapi_)");
        }
        fields.add(new Field(key, label, Math.max(1, Math.min(256, maxLength))));
        return this;
    }

    public Form button(String id, String label) {
        buttons.add(new Button(id, label));
        return this;
    }

    /** Whether Escape closes the form (default: yes). */
    public Form closable(boolean closable) {
        this.closable = closable;
        return this;
    }

    /** Runs on the server thread when the player presses a button. */
    public Form onAnswer(Consumer<Answer> handler) {
        this.onAnswer = handler;
        return this;
    }

    /** Shows the form to a player. A form without a button gets one, "OK". */
    public void show(Player player) {
        if (buttons.isEmpty()) {
            buttons.add(new Button("ok", "OK"));
        }
        Forms.show(this, player);
    }

    /** Closes whatever form the player has open. */
    public static void close(Player player) {
        Forms.close(player);
    }

    public String title() {
        return title;
    }

    public List<String> text() {
        return List.copyOf(text);
    }

    public List<Field> fields() {
        return List.copyOf(fields);
    }

    public List<Button> buttons() {
        return List.copyOf(buttons);
    }

    public boolean closable() {
        return closable;
    }

    public Consumer<Answer> handler() {
        return onAnswer;
    }
}
