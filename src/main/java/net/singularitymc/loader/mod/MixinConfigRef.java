package net.singularitymc.loader.mod;

import net.singularitymc.api.mod.ModEnvironment;

/**
 * A Mixin config declared by a mod.
 *
 * @param config      resource name of the config, e.g. {@code examplemod.mixins.json}
 * @param environment where it should be applied
 */
public record MixinConfigRef(String config, ModEnvironment environment) {}
