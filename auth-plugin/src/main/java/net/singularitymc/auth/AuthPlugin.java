package net.singularitymc.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.singularitymc.auth.AccountStore.Account;
import net.singularitymc.sapi.Singularity;
import net.singularitymc.sapi.command.CommandException;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.event.player.PlayerJoinEvent;
import net.singularitymc.sapi.event.player.PlayerQuitEvent;
import net.singularitymc.sapi.gui.Form;
import net.singularitymc.sapi.login.LoginDecision;
import net.singularitymc.sapi.plugin.JavaPlugin;
import net.singularitymc.sapi.text.Text;

/**
 * Singularity Auth: logins for a server that runs with {@code online_mode = false}.
 *
 * <p>Two kinds of players, told apart before they enter the world (see {@link Gate}):
 * <ul>
 * <li><b>Premium</b>: the name belongs to a paid Minecraft account. The client must prove that to Mojang's session
 *     servers, exactly as on an online-mode server. Nobody can pretend to be such a player, and they need no
 *     password.</li>
 * <li><b>Cracked</b>: everyone else. They register a password once and log in with it. Until they have, the
 *     server ignores everything their client sends except the login itself ({@code Player.restrict}).</li>
 * </ul>
 *
 * <p>Threads: commands, events and the timer run on the server thread and own {@link #pending} and
 * {@link #loggedIn}. The gate runs on network threads and only touches the thread-safe parts (the account store,
 * the throttle, the lookup, {@link #online}). Password hashing runs on two worker threads of its own, because one
 * hash takes a quarter of a second, and comes back to the server thread through the scheduler.
 */
public final class AuthPlugin extends JavaPlugin {

    /** What a player who has not logged in may still type. */
    private static final List<String> OPEN_COMMANDS = List.of("login", "l", "register", "reg");

    private static final class Waiting {
        long sinceTick;
        long nextReminderTick;
        boolean busy;
    }

    private AccountStore store;
    private Throttle throttle;
    private PremiumLookup lookup;
    private ThreadPoolExecutor hashers;
    private byte[] addressKey;

    private final Map<UUID, Waiting> pending = new HashMap<>();
    private final Set<UUID> loggedIn = new HashSet<>();
    /** Lower-case names of the players who are online, for the gate. */
    private final Set<String> online = ConcurrentHashMap.newKeySet();
    private long tick;

    // settings (volatile: the gate reads them from network threads)
    private volatile boolean protectPaidNames;
    private boolean gui;
    private int timeoutTicks;
    private int reminderTicks;
    private int minLength;
    private int iterations;
    private int accountsPerAddress;
    private long sessionMillis;

    @Override
    public void onEnable() {
        store = new AccountStore(dataFolder().resolve("accounts.tsv"));
        try {
            store.load();
            addressKey = loadKey(dataFolder().resolve("address.key"));
        } catch (IOException e) {
            // without the accounts nobody could be told apart: refuse to run half-working
            throw new IllegalStateException("Singularity Auth cannot read its files in " + dataFolder() + ": " + e.getMessage(), e);
        }
        throttle = new Throttle(5, 300_000);
        lookup = new PremiumLookup("", 4);
        readSettings();
        hashers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread t = new Thread(runnable, "Singularity Auth hashing");
            t.setDaemon(true);
            return t;
        });

        command("register").setExecutor(this::registerCommand);
        command("login").setExecutor(this::loginCommand);
        command("logout").setExecutor(this::logoutCommand);
        command("changepassword").setExecutor(this::changePasswordCommand);
        command("premium").setExecutor(this::premiumCommand);
        command("auth").setExecutor(this::adminCommand);
        command("auth").setCompleter((s, l, a) -> a.length == 1
                ? List.of("info", "unregister", "cracked", "forcelogin", "stats", "reload") : List.of());

        events().listen(PlayerJoinEvent.class, e -> onJoin(e.player()));
        events().listen(PlayerQuitEvent.class, e -> onQuit(e.player()));
        scheduler().runTimer(20, 20, this::everySecond);
        Singularity.setLoginGate(this::gate);

        // players who are already here (the plugin was enabled on a running server) start unauthenticated
        for (Player p : server().players()) {
            onJoin(p);
        }
        logger().info("Enabled: {} account(s), {} premium; paid names are {}", store.size(), store.premiumCount(),
                protectPaidNames ? "verified by Mojang" : "not checked (protect-paid-names = false)");
    }

    @Override
    public void onDisable() {
        Singularity.setLoginGate(null);
        // nobody may slip in unauthenticated because the plugin went away
        for (Player p : List.copyOf(server().players())) {
            if (pending.containsKey(p.uuid())) {
                p.kick("The login system is restarting. Please join again.");
            }
        }
        if (hashers != null) {
            hashers.shutdownNow();
        }
    }

    private void readSettings() {
        var c = config();
        gui = c.getBoolean("login-gui", true);
        timeoutTicks = Math.max(10, c.getInt("login-timeout-seconds", 60)) * 20;
        reminderTicks = Math.max(2, c.getInt("reminder-seconds", 8)) * 20;
        minLength = Math.max(6, c.getInt("password.min-length", 8));
        iterations = Math.max(100_000, c.getInt("password.iterations", 600_000));
        accountsPerAddress = Math.max(0, c.getInt("limits.accounts-per-address", 3));
        sessionMillis = Math.max(0, c.getLong("limits.session-minutes", 0)) * 60_000;
        protectPaidNames = c.getBoolean("premium.protect-paid-names", true);
        throttle.configure(c.getInt("limits.max-attempts", 5), c.getLong("limits.lock-seconds", 300) * 1000);
        lookup.configure(c.getString("premium.lookup-url",
                "https://api.minecraftservices.com/minecraft/profile/lookup/name/{name}"), c.getInt("premium.lookup-timeout-seconds", 4));
    }

    // -------------------------------------------------------------------------------------------- the gate

    /** Network thread. See {@link Gate} for the rules. */
    private CompletableFuture<LoginDecision> gate(String name, String address) {
        long now = System.currentTimeMillis();
        String key = AccountStore.key(name);
        LoginDecision decision = Gate.beforeLookup(name, store.get(name), online.contains(key),
                throttle.lockedFor("name:" + key, now), throttle.lockedFor("address:" + address, now), protectPaidNames);
        if (decision != null) {
            return CompletableFuture.completedFuture(decision);
        }
        return lookup.lookup(name).thenApply(Gate::afterLookup);
    }

    // ---------------------------------------------------------------------------------------- join and quit

    private void onJoin(Player player) {
        online.add(AccountStore.key(player.name()));
        Account account = store.get(player.name());
        if (player.isMojangVerified()) {
            if (account == null || !account.premium() || !account.uuid().equals(player.uuid().toString())) {
                long now = System.currentTimeMillis();
                Account premium = new Account(player.name(), true, player.uuid().toString(), account == null ? "" : account.hash(),
                        account == null ? now : account.registered(), now, addressHash(player.address()),
                        account == null ? addressHash(player.address()) : account.registeredAddress());
                save(premium);
            }
            loggedIn.add(player.uuid());
            player.sendMessage(message("premium", "&aVerified by Mojang as the owner of this account. No password needed."));
            return;
        }
        if (account != null && !account.premium() && sessionMillis > 0
                && System.currentTimeMillis() - account.lastLogin() < sessionMillis
                && account.lastAddress().equals(addressHash(player.address()))) {
            loggedIn.add(player.uuid());
            player.sendMessage(Text.colorize("&aWelcome back. You are still logged in from this address."));
            return;
        }
        startWaiting(player);
    }

    private void onQuit(Player player) {
        online.remove(AccountStore.key(player.name()));
        pending.remove(player.uuid());
        loggedIn.remove(player.uuid());
    }

    private void startWaiting(Player player) {
        player.restrict(OPEN_COMMANDS);
        Waiting w = new Waiting();
        w.sinceTick = tick;
        w.nextReminderTick = tick;
        pending.put(player.uuid(), w);
        if (gui) {
            // a moment later: the client is still loading the world when the join event runs
            scheduler().runLater(30, () -> showForm(player, null));
        }
    }

    private void everySecond() {
        tick += 20;
        for (Map.Entry<UUID, Waiting> entry : List.copyOf(pending.entrySet())) {
            Player p = server().player(entry.getKey()).orElse(null);
            Waiting w = entry.getValue();
            if (p == null) {
                pending.remove(entry.getKey());
            } else if (tick - w.sinceTick >= timeoutTicks) {
                pending.remove(entry.getKey());
                p.kick(config().getString("messages.timeout", "You took too long to log in."));
            } else if (tick >= w.nextReminderTick) {
                w.nextReminderTick = tick + reminderTicks;
                p.sendMessage(store.get(p.name()) == null
                        ? message("register", "&ePlease register: &f/register <password> <password again>")
                        : message("login", "&ePlease log in: &f/login <password>"));
            }
        }
        if (tick % 12_000 == 0) {
            throttle.prune(System.currentTimeMillis());
        }
    }

    // ------------------------------------------------------------------------------------------- the window

    private void showForm(Player player, String error) {
        if (!gui || !player.isOnline() || !pending.containsKey(player.uuid())) {
            return;
        }
        boolean registered = store.get(player.name()) != null;
        Form form = Form.create(registered ? "Log in" : "Create your account");
        if (error != null) {
            form.text(Text.colorize("&c" + error));
        }
        if (registered) {
            form.text("Welcome back, " + player.name() + ". Enter your password to play.")
                    .field("password", "Password", 64)
                    .button("login", "Log in");
        } else {
            form.text("Welcome, " + player.name() + ". Choose a password of at least " + minLength
                            + " characters. You will need it every time you join.")
                    .field("password", "Password", 64)
                    .field("again", "Password again", 64)
                    .button("register", "Create account");
        }
        form.text(Text.colorize("&7What you type here is visible on your screen."));
        form.onAnswer(answer -> {
            try {
                if (answer.button().equals("login")) {
                    login(answer.player(), answer.value("password"));
                } else {
                    register(answer.player(), answer.value("password"), answer.value("again"));
                }
            } catch (CommandException e) {
                showForm(answer.player(), e.getMessage());
            }
        });
        form.show(player);
    }

    // --------------------------------------------------------------------------------------------- commands

    private boolean registerCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (args.length != 2) {
            return false;
        }
        register(player, args[0], args[1]);
        return true;
    }

    private boolean loginCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (args.length == 0 && gui && pending.containsKey(player.uuid())) {
            showForm(player, null);
            return true;
        }
        if (args.length != 1) {
            return false;
        }
        login(player, args[0]);
        return true;
    }

    private boolean logoutCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (!loggedIn.remove(player.uuid())) {
            throw new CommandException("You are not logged in.");
        }
        if (player.isMojangVerified()) {
            loggedIn.add(player.uuid());
            throw new CommandException("Your account is verified by Mojang: there is nothing to log out of.");
        }
        Account account = store.get(player.name());
        if (account != null) {
            save(account.withLogin(0, "")); // ends a remembered session too
        }
        player.sendMessage(Text.colorize("&eLogged out."));
        startWaiting(player);
        return true;
    }

    private boolean changePasswordCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        if (args.length != 2) {
            return false;
        }
        requireLoggedIn(player);
        Account account = store.get(player.name());
        if (account == null || account.premium()) {
            throw new CommandException("Your account is verified by Mojang and has no password.");
        }
        String problem = Passwords.problem(args[1], player.name(), minLength);
        if (problem != null) {
            throw new CommandException(problem);
        }
        String oldPassword = args[0];
        String newPassword = args[1];
        work(player, () -> Passwords.verify(oldPassword, account.hash()) ? Passwords.hash(newPassword, iterations) : null, hash -> {
            if (hash == null) {
                player.sendMessage(message("wrong-password", "&cWrong password."));
            } else if (save(account.withHash(hash))) {
                player.sendMessage(Text.colorize("&aPassword changed."));
            }
        });
        return true;
    }

    private boolean premiumCommand(CommandSender sender, String label, String[] args) {
        Player player = player(sender);
        requireLoggedIn(player);
        Account account = store.get(player.name());
        if (player.isMojangVerified() || account == null || account.premium()) {
            throw new CommandException("This account is already verified by Mojang.");
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("confirm")) {
            player.sendMessage(Text.colorize("&eOnly do this if you bought Minecraft and \"" + player.name()
                    + "\" is the name of that account."));
            player.sendMessage(Text.colorize("&eFrom your next join the server asks Mojang to verify you, and you need no password."
                    + " Without the paid account you will not get in under this name any more."));
            player.sendMessage(Text.colorize("&eTo go ahead: &f/premium confirm"));
            return true;
        }
        lookup.lookup(player.name()).thenAccept(result -> scheduler().callSync(() -> {
            switch (result) {
                case PAID -> {
                    if (save(account.withPremium(true, ""))) {
                        player.sendMessage(Text.colorize("&aDone. From your next join Mojang verifies you."));
                        logger().info("{} switched the account to premium", player.name());
                    }
                }
                case FREE -> player.sendMessage(Text.colorize("&cMojang knows no paid account named " + player.name() + "."));
                case UNKNOWN -> player.sendMessage(Text.colorize("&cCould not reach Mojang. Try again later."));
            }
            return null;
        }));
        return true;
    }

    private boolean adminCommand(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && args.length == 1) {
            config().reload();
            readSettings();
            sender.sendMessage("Singularity Auth settings reloaded.");
            return true;
        }
        if (sub.equals("stats") && args.length == 1) {
            sender.sendMessage(store.size() + " account(s), " + store.premiumCount() + " premium; " + loggedIn.size()
                    + " player(s) logged in, " + pending.size() + " waiting to log in.");
            return true;
        }
        if (args.length != 2) {
            return false;
        }
        String name = args[1];
        Account account = store.get(name);
        switch (sub) {
            case "info" -> {
                if (account == null) {
                    throw new CommandException("There is no account named " + name + ".");
                }
                sender.sendMessage(account.name() + ": " + (account.premium() ? "premium (verified by Mojang)" : "password account")
                        + ", registered " + date(account.registered()) + ", last login " + date(account.lastLogin())
                        + (server().player(account.name()).isPresent() ? ", online now" : ""));
            }
            case "unregister" -> {
                if (account == null) {
                    throw new CommandException("There is no account named " + name + ".");
                }
                try {
                    store.remove(name);
                } catch (IOException e) {
                    throw new CommandException("Could not write the account file: " + e.getMessage());
                }
                server().player(account.name()).ifPresent(p -> p.kick("Your account was removed. Join again to register."));
                sender.sendMessage("Removed the account " + account.name() + ".");
                logger().info("{} removed the account {}", sender.name(), account.name());
            }
            case "cracked" -> {
                if (account == null || !account.premium()) {
                    throw new CommandException(name + " is not a premium account.");
                }
                try {
                    if (account.hash().isEmpty()) {
                        store.remove(name); // it never had a password: the name is free again
                    } else {
                        store.put(account.withPremium(false, ""));
                    }
                } catch (IOException e) {
                    throw new CommandException("Could not write the account file: " + e.getMessage());
                }
                sender.sendMessage(account.name() + (account.hash().isEmpty() ? " has no account any more (it had no password)."
                        : " is a password account again, with its old password."));
                logger().info("{} made {} a password account again", sender.name(), account.name());
            }
            case "forcelogin" -> {
                Player target = server().player(name).orElseThrow(() -> new CommandException(name + " is not online."));
                if (!pending.containsKey(target.uuid())) {
                    throw new CommandException(target.name() + " is already logged in.");
                }
                authenticate(target, null);
                sender.sendMessage("Logged " + target.name() + " in.");
                logger().info("{} logged {} in without a password", sender.name(), target.name());
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------------------------- login, register

    private void login(Player player, String password) {
        Waiting w = pending.get(player.uuid());
        if (w == null) {
            throw new CommandException("You are already logged in.");
        }
        Account account = store.get(player.name());
        if (account == null) {
            throw new CommandException("You have no account yet. Use /register <password> <password again>.");
        }
        if (w.busy) {
            throw new CommandException("Checking your password, one moment.");
        }
        String nameKey = "name:" + AccountStore.key(player.name());
        String addressKey = "address:" + player.address();
        w.busy = true;
        work(player, () -> {
            boolean right = Passwords.verify(password, account.hash());
            // a hash made with fewer rounds than wanted today is redone now, while the password is at hand
            String upgraded = right && Passwords.outdated(account.hash(), iterations) ? Passwords.hash(password, iterations) : "";
            return right ? upgraded : null;
        }, upgraded -> {
            w.busy = false;
            if (!pending.containsKey(player.uuid())) {
                return;
            }
            long now = System.currentTimeMillis();
            if (upgraded == null) {
                boolean locked = throttle.fail(nameKey, now) | throttle.fail(addressKey, now);
                logger().warn("Wrong password for {} from {}", player.name(), addressHash(player.address()).substring(0, 8));
                if (locked) {
                    pending.remove(player.uuid());
                    player.kick("Too many wrong passwords. Try again in "
                            + Gate.minutes(Math.max(throttle.lockedFor(nameKey, now), throttle.lockedFor(addressKey, now))) + ".");
                    return;
                }
                player.sendMessage(message("wrong-password", "&cWrong password."));
                showForm(player, "Wrong password.");
                return;
            }
            throttle.success(nameKey);
            throttle.success(addressKey);
            authenticate(player, upgraded.isEmpty() ? account : account.withHash(upgraded));
            player.sendMessage(message("logged-in", "&aLogged in. Welcome back!"));
        });
    }

    private void register(Player player, String password, String again) {
        Waiting w = pending.get(player.uuid());
        if (w == null) {
            throw new CommandException("You are already logged in.");
        }
        if (store.get(player.name()) != null) {
            throw new CommandException("This name already has an account. Use /login <password>.");
        }
        if (!password.equals(again)) {
            throw new CommandException("The two passwords are not the same.");
        }
        String problem = Passwords.problem(password, player.name(), minLength);
        if (problem != null) {
            throw new CommandException(problem);
        }
        String address = addressHash(player.address());
        if (accountsPerAddress > 0 && store.registeredFrom(address) >= accountsPerAddress) {
            throw new CommandException("Too many accounts were registered from your address.");
        }
        if (w.busy) {
            throw new CommandException("One moment.");
        }
        w.busy = true;
        work(player, () -> Passwords.hash(password, iterations), hash -> {
            w.busy = false;
            if (!pending.containsKey(player.uuid()) || store.get(player.name()) != null) {
                return;
            }
            long now = System.currentTimeMillis();
            Account account = new Account(player.name(), false, "", hash, now, now, address, address);
            if (save(account)) {
                authenticate(player, null);
                player.sendMessage(message("registered", "&aAccount created. You are logged in."));
                logger().info("{} registered", player.name());
            } else {
                player.sendMessage(Text.colorize("&cCould not save your account. Tell an administrator."));
            }
        });
    }

    /** The player is who they say: lets them play. {@code account}, if given, is saved with this login's time. */
    private void authenticate(Player player, Account account) {
        pending.remove(player.uuid());
        loggedIn.add(player.uuid());
        player.unrestrict();
        Form.close(player);
        Account current = account != null ? account : store.get(player.name());
        if (current != null) {
            save(current.withLogin(System.currentTimeMillis(), addressHash(player.address())));
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    /** Runs {@code slow} on a hashing thread and {@code then} with its result on the server thread. */
    private <T> void work(Player player, java.util.concurrent.Callable<T> slow, java.util.function.Consumer<T> then) {
        try {
            hashers.execute(() -> {
                T result;
                try {
                    result = slow.call();
                } catch (Exception e) {
                    logger().error("Password work failed", e);
                    scheduler().callSync(() -> {
                        Waiting w = pending.get(player.uuid());
                        if (w != null) {
                            w.busy = false;
                        }
                        player.sendMessage(Text.colorize("&cSomething went wrong. Try again."));
                        return null;
                    });
                    return;
                }
                scheduler().callSync(() -> {
                    if (player.isOnline()) {
                        then.accept(result);
                    }
                    return null;
                });
            });
        } catch (RejectedExecutionException e) {
            Waiting w = pending.get(player.uuid());
            if (w != null) {
                w.busy = false;
            }
            throw new CommandException("The server is busy checking logins. Try again in a few seconds.");
        }
    }

    private boolean save(Account account) {
        try {
            store.put(account);
            return true;
        } catch (IOException e) {
            logger().error("Could not write the account file", e);
            return false;
        }
    }

    private void requireLoggedIn(Player player) {
        if (!loggedIn.contains(player.uuid())) {
            throw new CommandException("Log in first.");
        }
    }

    private static Player player(CommandSender sender) {
        return sender.asPlayer().orElseThrow(() -> new CommandException("Only players have accounts. See /auth."));
    }

    private String message(String key, String fallback) {
        return Text.colorize(config().getString("messages." + key, fallback));
    }

    private static String date(long millis) {
        return millis <= 0 ? "never" : java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * Addresses are personal data, and the account file may leak: only a keyed hash of an address is kept. It
     * still tells "same address as last time" (sessions, the accounts-per-address limit), but not which address.
     */
    String addressHash(String address) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(addressKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] loadKey(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            byte[] key = HexFormat.of().parseHex(Files.readString(file).trim());
            if (key.length >= 16) {
                return key;
            }
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, HexFormat.of().formatHex(key));
        return key;
    }
}
