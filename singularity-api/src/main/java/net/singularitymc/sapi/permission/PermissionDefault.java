package net.singularitymc.sapi.permission;

import java.util.Locale;

/** Who has a permission node when no {@link PermissionProvider} decides. */
public enum PermissionDefault {
    /** Everyone. */
    TRUE,
    /** Nobody (unless a provider grants it). */
    FALSE,
    /** Operators. */
    OP,
    /** Everyone who is not an operator. */
    NOT_OP;

    /** {@code true}, {@code false}, {@code op}, {@code not_op} / {@code not op} (case-insensitive). */
    public static PermissionDefault parse(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT).replace(' ', '_')) {
            case "true", "everyone", "all" -> TRUE;
            case "false", "nobody", "none" -> FALSE;
            case "op", "ops", "operator" -> OP;
            case "not_op", "!op", "notop" -> NOT_OP;
            default -> throw new IllegalArgumentException("'" + text + "' is not a permission default (true, false, op, not_op)");
        };
    }

    public boolean test(boolean op) {
        return switch (this) {
            case TRUE -> true;
            case FALSE -> false;
            case OP -> op;
            case NOT_OP -> !op;
        };
    }
}
