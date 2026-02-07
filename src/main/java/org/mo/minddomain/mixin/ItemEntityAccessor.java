package org.mo.minddomain.mixin;

import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * ItemEntity Mixin Accessor
 *
 * <p>Provides access to the {@code itemAge} private field of {@link ItemEntity},
 * used to prevent space ball drops from despawning.</p>
 *
 * <p>In vanilla Minecraft, item entities despawn when {@code itemAge >= 6000} (5 minutes).
 * By setting {@code itemAge} to a large negative value (e.g., {@link Integer#MIN_VALUE}),
 * the item effectively never despawns, as it would take billions of ticks to reach 6000.</p>
 *
 * <p>This is critical for space ball drops from player death — losing a space ball
 * due to despawn would mean permanent loss of the player's entire constructed space.</p>
 *
 * @see org.mo.minddomain.event.ModEvents
 */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {

    /**
     * Sets the item age to control despawn timing.
     *
     * <p>Setting to {@link Integer#MIN_VALUE} effectively prevents natural despawning,
     * as the age would need over 2 billion ticks (~3.4 years of continuous gameplay)
     * to reach the despawn threshold of 6000.</p>
     *
     * <p>Note: The getter {@code getItemAge()} is already public in {@link ItemEntity},
     * so only the setter needs to be exposed via Mixin Accessor.</p>
     *
     * @param age the new item age in ticks
     */
    @Accessor("itemAge")
    void setItemAge(int age);
}
