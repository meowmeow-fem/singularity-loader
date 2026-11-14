# Events API (mod side)

Status: **experimental**. The API may still change until it is declared stable.

These are the loader's events, with Minecraft types, for mods. Since 4.0 the Singularity API mod posts matching events
without Minecraft types (`net.singularitymc.sapi.event...`) right after them, for plugins and for mods that use the API;
see [SINGULARITY_API.md](SINGULARITY_API.md#events-loader-events-and-api-events) and [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md#7-events).

Mods register listeners on the one `EventBus`. The loader's patches post the events.

```java
import net.singularitymc.api.event.EventBus;
import net.singularitymc.api.event.EventPriority;
import net.singularitymc.event.*;

public final class MyMod implements ServerModInitializer {
    @Override
    public void onInitializeServer() {
        // stop anyone breaking bedrock
        EventBus.register(BlockBreakEvent.class, e -> {
            if (e.getState().is(net.minecraft.world.level.block.Blocks.BEDROCK)) e.setCancelled(true);
        });
        // watch joins
        EventBus.register(PlayerJoinEvent.class, EventPriority.MONITOR, e ->
                System.out.println(e.getPlayer().getName().getString() + " joined"));
    }
}
```

| Event | Fires | Cancellable |
|---|---|---|
| `BlockBreakEvent` (player, level, pos, state) | before a player breaks a block | yes |
| `BlockPlaceEvent` (player or `null`, level, pos, stack) | before a block item is placed | yes |
| `ItemUseEvent` (player, stack, hand) | before a player uses the item in hand | yes |
| `EntityDamageEvent` (entity, level, source, amount) | before a living entity takes damage (amount is read-only) | yes |
| `CommandRegistrationEvent` (dispatcher, registryAccess, environment) | whenever the command tree is built (startup and `/reload`), after vanilla commands | no |
| `PlayerJoinEvent` (player) | after a player joined | no |
| `PlayerLeaveEvent` (player) | when a player leaves | no |

Rules
- Priorities run `LOWEST` to `HIGHEST`, then `MONITOR` (sees everything, observe only).
- Cancelled events are not passed on, except to `MONITOR` or listeners registered with `receiveCancelled = true`.
- A listener that throws is logged; the server and the other listeners carry on.
- Listeners run on the server thread. Keep them fast.
- Events carry Minecraft objects (Mojang names). Code that uses them must be compiled against the game, as `totem-teleport-mod` is. `EventBus` itself has no Minecraft types.
- Every hook is optional (`require = 0`): if a Minecraft version changes a method, that one event silently never fires.
