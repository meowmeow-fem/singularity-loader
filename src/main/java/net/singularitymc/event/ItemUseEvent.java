package net.singularitymc.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.singularitymc.api.event.CancellableEvent;

/** A player uses the item in their hand (right click in the air, eating, throwing, ...). Cancel it and nothing happens. */
public final class ItemUseEvent extends CancellableEvent {
    private final ServerPlayer player;
    private final ItemStack stack;
    private final InteractionHand hand;

    public ItemUseEvent(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        this.player = player;
        this.stack = stack;
        this.hand = hand;
    }

    public ServerPlayer getPlayer() { return player; }
    public ItemStack getStack() { return stack; }
    public InteractionHand getHand() { return hand; }
}
