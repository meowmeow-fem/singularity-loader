package net.singularitymc.keys;

import net.minecraft.client.Minecraft;

/** The two keys. They only work while playing with no window open (the mouse is "grabbed" by the game then). */
public final class DashboardKeys {
    /** GLFW key codes. */
    private static final int RIGHT_SHIFT = 344;
    private static final int F12 = 301;

    private DashboardKeys() {}

    public static void pressed(Minecraft minecraft, int key) {
        if (key != RIGHT_SHIFT && key != F12) {
            return;
        }
        if (minecraft.player == null || !minecraft.mouseHandler.isMouseGrabbed()) {
            return; // in a menu, in chat, or not in a world
        }
        minecraft.player.connection.sendCommand(key == RIGHT_SHIFT ? "dashboard" : "anticheat gui");
    }
}
