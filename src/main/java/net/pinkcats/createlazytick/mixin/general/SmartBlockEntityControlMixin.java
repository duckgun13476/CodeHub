package net.pinkcats.createlazytick.mixin.general;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.helper.LazyTickTier;
import net.pinkcats.createlazytick.manager.ForcedActiveManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SmartBlockEntity.class)
public abstract class SmartBlockEntityControlMixin extends BlockEntity implements ILazyTickControl {

    @Unique private boolean lazytick$forceDisabled = false;
    @Unique private String lazytick$operatorName = "";
    @Unique private LazyTickTier lazytick$syncedTier = LazyTickTier.ACTIVE;

    public SmartBlockEntityControlMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ============================================================
    // [修复] 使用 Create 特有的生命周期方法，避免映射问题
    // ============================================================

    // 1. 注入到 initialize()：这是 Create 初始化行为的地方
    @Inject(method = "initialize", at = @At("RETURN"), remap = false)
    private void lazytick$onInit(CallbackInfo ci) {
        if (this.lazytick$forceDisabled && this.level != null) {
            ForcedActiveManager.register(this.level, this.worldPosition);
        }
    }

    // 2. 注入到 invalidate()：这是 SmartBlockEntity.setRemoved() 必定会调用的清理方法
    // 因为它是 SmartBlockEntity 自己的方法（不是 Override 的），所以 remap = false 是安全的
    @Inject(method = "invalidate", at = @At("HEAD"), remap = false)
    private void lazytick$onInvalidate(CallbackInfo ci) {
        if (this.level != null) {
            ForcedActiveManager.unregister(this.level, this.worldPosition);
        }
    }

    // ============================================================
    // 接口实现 (保持不变)
    // ============================================================

    @Override
    public boolean lazytick$isForceDisabled() { return this.lazytick$forceDisabled; }

    @Override
    public String lazytick$getOperatorName() { return this.lazytick$operatorName; }

    @Override
    public void lazytick$setForceDisabled(boolean forced, String operator) {
        if (this.lazytick$forceDisabled != forced) {
            this.lazytick$forceDisabled = forced;
            this.lazytick$operatorName = (operator == null) ? "" : operator.intern();
            if (this.level != null) {
                if (forced) ForcedActiveManager.register(this.level, this.worldPosition);
                else ForcedActiveManager.unregister(this.level, this.worldPosition);
            }
            this.setChanged();
            this.sendBlockUpdated();
        }
    }

    @Override
    public LazyTickTier lazytick$getSyncedTier() { return this.lazytick$syncedTier; }

    @Override
    public void lazytick$setSyncedTier(int currentTick, int maxTick) {
        LazyTickTier newTier = LazyTickTier.fromTicks(currentTick, maxTick);
        if (this.lazytick$syncedTier != newTier) {
            this.lazytick$syncedTier = newTier;
            this.setChanged();
            this.sendBlockUpdated();
        }
    }

    @Unique
    private void sendBlockUpdated() {
        if (this.level != null) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    @Inject(method = "write", at = @At("RETURN"), remap = false)
    private void lazytick$writeNBT(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (this.lazytick$forceDisabled) {
            tag.putBoolean("LazyTickForceDisabled", true);
            if (!this.lazytick$operatorName.isEmpty()) tag.putString("LazyTickOperator", this.lazytick$operatorName);
        }
        if (this.lazytick$syncedTier != LazyTickTier.ACTIVE) {
            tag.putInt("LazyTickTier", this.lazytick$syncedTier.ordinal());
        }
    }

    @Inject(method = "read", at = @At("RETURN"), remap = false)
    private void lazytick$readNBT(CompoundTag tag, boolean clientPacket, CallbackInfo ci) {
        if (tag.contains("LazyTickForceDisabled")) {
            this.lazytick$forceDisabled = tag.getBoolean("LazyTickForceDisabled");
            this.lazytick$operatorName = tag.getString("LazyTickOperator");
        } else {
            this.lazytick$forceDisabled = false;
            this.lazytick$operatorName = "";
        }
        if (tag.contains("LazyTickTier")) {
            int ordinal = tag.getInt("LazyTickTier");
            if (ordinal >= 0 && ordinal < LazyTickTier.values().length) {
                this.lazytick$syncedTier = LazyTickTier.values()[ordinal];
            }
        } else {
            this.lazytick$syncedTier = LazyTickTier.ACTIVE;
        }
    }
}