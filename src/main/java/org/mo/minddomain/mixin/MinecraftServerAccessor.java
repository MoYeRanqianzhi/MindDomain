package org.mo.minddomain.mixin;

import java.util.Map;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * MinecraftServer Mixin Accessor
 *
 * <p>Provides access to private fields of {@link MinecraftServer},
 * used for dynamically creating and registering dimension instances at runtime.
 * The core mechanism of MindDomain relies on dynamically creating {@link ServerWorld} instances,
 * which requires access to the server's internal dimension map and world storage session.</p>
 *
 * @see org.mo.minddomain.dimension.DynamicWorldManager
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {

    /**
     * Gets the map of all dimension instances maintained by the server.
     *
     * <p>The return type is {@link Map}, but the underlying implementation is a {@code LinkedHashMap},
     * which can be safely cast to a mutable map to add dynamically created dimensions.</p>
     *
     * @return the mapping from dimension {@link RegistryKey} to {@link ServerWorld} instances
     */
    @Accessor("worlds")
    Map<RegistryKey<World>, ServerWorld> getWorlds();

    /**
     * Gets the world storage session.
     *
     * <p>{@link LevelStorage.Session} manages the disk read/write paths for world data.
     * When creating a new {@link ServerWorld}, this session must be provided
     * to determine the data storage location.</p>
     *
     * @return the current world's storage session instance
     */
    @Accessor("session")
    LevelStorage.Session getSession();
}
