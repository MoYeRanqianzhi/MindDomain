package org.mo.minddomain.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ServerWorld Mixin Accessor
 *
 * <p>Provides access to the private {@code entityManager} field of {@link ServerWorld},
 * used by the space swap engine to force entity loading in dynamically created dimensions.</p>
 *
 * <p>In Minecraft 1.21+, entity loading is handled asynchronously by {@link ServerEntityManager},
 * separate from chunk block data loading. When a dimension has no players (and thus no chunk tickets
 * at entity-ticking level), entity sections are unloaded and entities are saved to disk.
 * During a space swap, we need to force-load these entities before querying them with
 * {@code getOtherEntities()}.</p>
 *
 * <p>The entity manager exposes methods like {@code tick()} and {@code flush()} that process
 * pending entity loading operations, which is essential for ensuring all entities in the
 * swap area are available before the bidirectional exchange.</p>
 *
 * @see org.mo.minddomain.swap.SpaceSwapManager
 */
@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {

    /**
     * Gets the entity manager for this world.
     *
     * <p>The {@link ServerEntityManager} manages entity tracking, loading, and unloading
     * for all entities in the world. It maintains a spatial index (via entity tracking sections)
     * that is used by {@code getOtherEntities()} queries.</p>
     *
     * <p>Key methods available on the returned entity manager:</p>
     * <ul>
     *   <li>{@code tick()} - processes completed entity load operations from async IO</li>
     *   <li>{@code flush()} - forces all pending entity operations to complete</li>
     *   <li>{@code loadChunks()} - processes pending entity chunk loads</li>
     * </ul>
     *
     * @return the entity manager instance for this world
     */
    @Accessor("entityManager")
    ServerEntityManager<Entity> getEntityManager();
}
