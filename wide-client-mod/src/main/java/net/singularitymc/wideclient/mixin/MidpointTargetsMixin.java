package net.singularitymc.wideclient.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Empty on purpose: it only makes Mixin hand these classes to {@code WideClientMixinPlugin.postApply}, which rewrites
 * their two-coordinate midpoints to 64-bit math when the client allows more than 2^30 blocks (Fabric has no general
 * class transformer). These are the classes whose int midpoints are coordinates in 26.3 (structure centres,
 * mineshafts, jigsaw structures, ruined portals, structure spacing); the server rewrites every class instead.
 */
@Mixin(targets = {
    "net.minecraft.world.level.levelgen.structure.BoundingBox",
    "net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement",
    "net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces$MineShaftPiece",
    "net.minecraft.world.level.levelgen.structure.structures.RuinedPortalPiece",
    "net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType"
})
abstract class MidpointTargetsMixin {
}
