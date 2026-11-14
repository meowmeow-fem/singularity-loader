package net.singularitymc.loader.patches.wide;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import net.singularitymc.compat.GameShims;
import net.singularitymc.loader.util.WidePacking;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stage 2 of the extended world size (see docs/COORDINATES.md). Vanilla selects sections by key range
 * ({@code subSet(asLong(x, 0, z), asLong(x, -1, z) + 1)}), which assumes Y is the low bits and that a key range holds
 * only one X. With wide packing the same X/Z fields are shared by 4096 columns, so the ranges are taken over the raw
 * fields and every key is checked against its decoded position.
 *
 * <p>{@code consumer.accept} returns a different type in 26.1-26.2 than in 26.3, so it is called through
 * {@link GameShims#acceptAborts}.
 */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionStorageWideMixin<T extends EntityAccess> {
    @Shadow @Final private Long2ObjectMap<EntitySection<T>> sections;
    @Shadow @Final private LongSortedSet sectionIds;

    /**
     * @author Singularity Loader
     * @reason key ranges of the vanilla layout; see the class comment
     */
    @Overwrite
    public void forEachAccessibleNonEmptySection(AABB box, AbortableIterationConsumer<EntitySection<T>> consumer) {
        int minX = SectionPos.posToSectionCoord(box.minX - 2.0);
        int minY = WidePacking.clampSectionY(SectionPos.posToSectionCoord(box.minY - 4.0));
        int minZ = SectionPos.posToSectionCoord(box.minZ - 2.0);
        int maxX = SectionPos.posToSectionCoord(box.maxX + 2.0);
        int maxY = WidePacking.clampSectionY(SectionPos.posToSectionCoord(box.maxY + 0.0));
        int maxZ = SectionPos.posToSectionCoord(box.maxZ + 2.0);
        for (int x = minX; x <= maxX; x++) {
            if (!WidePacking.sectionRanges(x)) {
                // 64-bit sections (experimental_long_positions) have escape-coded keys: look each one up.
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = minY; y <= maxY; y++) {
                        EntitySection<T> section = this.sections.get(SectionPos.asLong(x, y, z));
                        if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && GameShims.acceptAborts(consumer, section)) {
                            return;
                        }
                    }
                }
                continue;
            }
            LongIterator it = Singularity$keys(WidePacking.sectionSlabStart(x), WidePacking.sectionSlabEnd(x)).iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                int y = SectionPos.y(key);
                int z = SectionPos.z(key);
                if (y < minY || y > maxY || z < minZ || z > maxZ || SectionPos.x(key) != x) {
                    continue;
                }
                EntitySection<T> section = this.sections.get(key);
                if (section != null && !section.isEmpty() && section.getStatus().isAccessible() && GameShims.acceptAborts(consumer, section)) {
                    return;
                }
            }
        }
    }

    /**
     * @author Singularity Loader
     * @reason key ranges of the vanilla layout; returns a copy holding only this column's sections
     */
    @Overwrite
    private LongSortedSet getChunkSections(int x, int z) {
        LongSortedSet result = new LongAVLTreeSet();
        if (!WidePacking.sectionRanges(x)) {
            for (int y = -128; y <= 127; y++) {
                long key = SectionPos.asLong(x, y, z);
                if (this.sectionIds.contains(key)) {
                    result.add(key);
                }
            }
            return result;
        }
        LongIterator it = Singularity$keys(WidePacking.sectionColumnStart(x, z), WidePacking.sectionColumnEnd(x, z)).iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (SectionPos.x(key) == x && SectionPos.z(key) == z) {
                result.add(key);
            }
        }
        return result;
    }

    @Unique
    private LongSortedSet Singularity$keys(long first, long last) {
        return last == Long.MAX_VALUE ? this.sectionIds.tailSet(first) : this.sectionIds.subSet(first, last + 1);
    }
}
