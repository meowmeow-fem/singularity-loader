package net.singularitymc.sapi.plugin;

public enum PluginState {
    /** Found and read; not loaded yet. */
    DISCOVERED,
    /** {@code onLoad} has run. */
    LOADED,
    /** {@code onEnable} has run. */
    ENABLED,
    /** {@code onDisable} has run (server stopping). */
    DISABLED,
    /** Could not be loaded or enabled; see {@link PluginInfo#error()}. */
    FAILED
}
