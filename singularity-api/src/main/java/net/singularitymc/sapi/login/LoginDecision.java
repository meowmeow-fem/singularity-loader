package net.singularitymc.sapi.login;

/** What a {@link LoginGate} decided for one connecting client. */
public record LoginDecision(Kind kind, String message) {

    public enum Kind {
        /** Let the client in under the name it gave, unchecked (what an offline-mode server does for everyone). */
        OFFLINE,
        /**
         * The client must prove to Mojang's session servers that it owns this name, exactly as on an online-mode
         * server: the connection is encrypted and the player gets the account's real UUID. A client without a paid
         * account for the name cannot pass.
         */
        MOJANG,
        /** Refuse the connection; {@link LoginDecision#message} is shown to the client. */
        DENY
    }

    private static final LoginDecision OFFLINE = new LoginDecision(Kind.OFFLINE, "");
    private static final LoginDecision MOJANG = new LoginDecision(Kind.MOJANG, "");

    public static LoginDecision offline() {
        return OFFLINE;
    }

    public static LoginDecision mojang() {
        return MOJANG;
    }

    public static LoginDecision deny(String message) {
        return new LoginDecision(Kind.DENY, message == null ? "" : message);
    }
}
