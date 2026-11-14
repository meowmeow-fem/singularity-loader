package net.singularitymc.totemtp;

import net.singularitymc.api.entrypoint.ServerModInitializer;

/** Entrypoint. All the work happens in {@link net.singularitymc.totemtp.mixin.LivingEntityMixin}. */
public final class TotemTeleportMod implements ServerModInitializer {
    public static final String MOD_ID = "totemteleport";
    /** Teleport distance in blocks: a random distance between MIN and MAX in a random direction. */
    public static final int MIN_DISTANCE = 32;
    public static final int MAX_DISTANCE = 96;
    /** How many random spots are tried before giving up and staying where you are. */
    public static final int ATTEMPTS = 12;

    @Override
    public void onInitializeServer() {
        System.out.println("[Totem Teleport] Ready: a used-up totem now throws you " + MIN_DISTANCE + "-" + MAX_DISTANCE + " blocks away.");
    }
}
