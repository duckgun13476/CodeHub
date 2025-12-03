package net.pinkcats.createlazytick.mixin.saw;

import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingInventory;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SawBlockEntity.class)
public interface SawBlockEntityAccessor {
    @Accessor(remap = false)
    ProcessingInventory getInventory();

    @Accessor(remap = false)
    FilteringBehaviour getFiltering();
}