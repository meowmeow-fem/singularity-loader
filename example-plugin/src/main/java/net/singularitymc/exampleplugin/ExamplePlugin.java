package net.singularitymc.exampleplugin;

import java.util.List;
import net.singularitymc.sapi.command.CommandException;
import net.singularitymc.sapi.command.CommandSender;
import net.singularitymc.sapi.command.CommandSpec;
import net.singularitymc.sapi.entity.Player;
import net.singularitymc.sapi.event.player.PlayerJoinEvent;
import net.singularitymc.sapi.plugin.JavaPlugin;
import net.singularitymc.sapi.text.Text;
import net.singularitymc.sapi.world.BlockLocation;

/**
 * The class named by {@code main} in {@code plugin.toml}. The loader creates it, calls {@link #onLoad()} while the
 * server starts and {@link #onEnable()} once the worlds are loaded. Everything registered here is removed again when
 * the plugin is disabled.
 */
public final class ExamplePlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        // config.toml is copied from this jar to SingularityPlugins/exampleplugin/ the first time it is used
        logger().info("Loaded; greeting is '{}'", config().getString("greeting", "Hello"));
    }

    @Override
    public void onEnable() {
        // declared in plugin.toml: only the executor is set here
        command("hello").setExecutor(this::hello);
        command("hello").setCompleter((sender, label, args) ->
                args.length == 1 ? server().players().stream().map(Player::name).toList() : List.of());

        // registered in code (its permission node is declared in plugin.toml)
        commands().register(CommandSpec.of("whereami").description("Shows your exact block position")
                .permission("exampleplugin.whereami"), this::whereAmI);

        events().listen(PlayerJoinEvent.class, e -> e.player().sendMessage(Text.colorize(
                config().getString("join-message", "&aWelcome, {player}!").replace("{player}", e.player().name()))));

        long every = config().getLong("announce-every-ticks", 6000);
        if (every > 0) {
            scheduler().runTimer(every, every, () -> logger().info("{} player(s) online", server().players().size()));
        }
        logger().info("Enabled on Minecraft {} (max coordinate {})", server().minecraftVersion(), server().maxCoordinate());
    }

    @Override
    public void onDisable() {
        logger().info("Disabled");
    }

    private boolean hello(CommandSender sender, String label, String[] args) {
        if (args.length > 1) {
            return false; // shows the usage from plugin.toml
        }
        String who = args.length == 1 ? args[0] : sender.name();
        sender.sendMessage(Text.colorize(config().getString("greeting", "Hello") + "&r, " + who + "!"));
        return true;
    }

    private boolean whereAmI(CommandSender sender, String label, String[] args) {
        Player player = sender.asPlayer().orElseThrow(() -> new CommandException("Only players have a position."));
        // BlockLocation has 64-bit X/Z: exact even past 2,147,483,647
        BlockLocation at = player.blockLocation();
        sender.sendMessage("You are at " + at + " in " + player.world().id() + " (chunk " + at.chunk() + ")");
        return true;
    }
}
