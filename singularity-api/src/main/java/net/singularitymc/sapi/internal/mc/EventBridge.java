package net.singularitymc.sapi.internal.mc;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.singularitymc.api.event.EventBus;
import net.singularitymc.api.event.EventPriority;
import net.singularitymc.event.BlockBreakEvent;
import net.singularitymc.event.BlockPlaceEvent;
import net.singularitymc.event.EntityDamageEvent;
import net.singularitymc.event.PlayerJoinEvent;
import net.singularitymc.event.PlayerLeaveEvent;
import net.singularitymc.sapi.event.player.PlayerQuitEvent;

/**
 * Posts the API events after the loader's events (which carry Minecraft types): the bridge listens at
 * {@link EventPriority#HIGHEST}, so mod listeners of the loader event run first; if one of them cancelled it, the
 * API event is not posted. Cancelling the API event cancels the loader event, and so the action.
 */
final class EventBridge {
    private EventBridge() {}

    static void install() {
        EventBus.register(PlayerJoinEvent.class, EventPriority.HIGHEST,
                e -> EventBus.post(new net.singularitymc.sapi.event.player.PlayerJoinEvent(PlayerImpl.of(e.getPlayer()))));
        EventBus.register(PlayerLeaveEvent.class, EventPriority.HIGHEST, e -> {
            try {
                EventBus.post(new PlayerQuitEvent(PlayerImpl.of(e.getPlayer())));
            } finally {
                // login state is per session: a later player with this UUID starts unverified and unrestricted
                net.singularitymc.sapi.internal.Logins.forget(e.getPlayer().getUUID());
                FormImpl.forget(e.getPlayer().getUUID());
            }
        });
        EventBus.register(BlockBreakEvent.class, EventPriority.HIGHEST, e -> {
            var api = new net.singularitymc.sapi.event.block.BlockBreakEvent(PlayerImpl.of(e.getPlayer()), WorldImpl.of(e.getLevel()),
                    Coords.of(e.getPos()), BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).toString());
            if (EventBus.post(api).isCancelled()) {
                e.setCancelled(true);
            }
        });
        EventBus.register(BlockPlaceEvent.class, EventPriority.HIGHEST, e -> {
            var player = e.getPlayer() instanceof ServerPlayer sp ? PlayerImpl.of(sp) : null;
            var api = new net.singularitymc.sapi.event.block.BlockPlaceEvent(player, WorldImpl.of(e.getLevel()), Coords.of(e.getPos()),
                    BuiltInRegistries.ITEM.getKey(e.getStack().getItem()).toString());
            if (EventBus.post(api).isCancelled()) {
                e.setCancelled(true);
            }
        });
        EventBus.register(EntityDamageEvent.class, EventPriority.HIGHEST, e -> {
            if (e.getEntity() instanceof ServerPlayer hurt && net.singularitymc.sapi.internal.Logins.isRestricted(hurt.getUUID())) {
                e.setCancelled(true); // a restricted player cannot defend themselves
                return;
            }
            String type = e.getSource().typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse(e.getSource().getMsgId());
            var api = new net.singularitymc.sapi.event.entity.EntityDamageEvent(EntityImpl.of(e.getEntity()), type, e.getAmount());
            if (EventBus.post(api).isCancelled()) {
                e.setCancelled(true);
            }
        });
    }
}
