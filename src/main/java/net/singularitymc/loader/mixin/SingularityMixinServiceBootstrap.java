package net.singularitymc.loader.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** Tells Mixin that {@link SingularityMixinService} is available. Registered via {@code META-INF/services}. */
public final class SingularityMixinServiceBootstrap implements IMixinServiceBootstrap {
    @Override
    public String getName() {
        return SingularityMixinService.NAME;
    }

    @Override
    public String getServiceClassName() {
        return SingularityMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
        // nothing to prepare: the game class loader is created before Mixin starts
    }
}
