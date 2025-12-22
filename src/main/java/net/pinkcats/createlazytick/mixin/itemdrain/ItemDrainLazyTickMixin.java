package net.pinkcats.createlazytick.mixin.itemdrain;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.fluids.transfer.GenericItemEmptying;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.manager.ForcedActiveManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 分液池 (Item Drain) - [服务端权威版 / Server Authority Mode]
 * * 仅服务端计算冷却并更新状态。
 * * 客户端只负责接收数据和渲染，不进行逻辑预测。
 */
@Mixin(value = ItemDrainBlockEntity.class, remap = false)
public abstract class ItemDrainLazyTickMixin extends SmartBlockEntity {

    @Unique private int outputCooldown = 0;
    @Unique private int currentWaitTime = 1;
    @Unique private boolean lazytick$hasRegistered = false;

    // [新增] 活跃状态计时器：记录上次成功工作离现在过去了多久
    @Unique private int lazytick$activeTimer = 0;

    public ItemDrainLazyTickMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;tick()V", shift = At.Shift.AFTER), cancellable = true, remap = false)
    public void optimizedTick(CallbackInfo ci) {
        ILazyTickControl control = (this instanceof ILazyTickControl) ? (ILazyTickControl) this : null;

        if (!lazytick$hasRegistered) {
            if (control != null && control.lazytick$isForceDisabled() && this.level != null) {
                ForcedActiveManager.register(this.level, this.worldPosition);
            }
            lazytick$hasRegistered = true;
        }

        if (!Config.enable_lazy_tick || !Config.enable_lazy_item_drain) return;

        if (level == null) return;
        // 检查强制开关
        if (control != null && control.lazytick$isForceDisabled()) {
            outputCooldown = 0;
            currentWaitTime = 1;
            lazytick$activeTimer = 0;

            if (!level.isClientSide) control.lazytick$setSyncedTier(1, Config.item_drain_delay_max);
            return;
        }

        ItemDrainAccessor accessor = (ItemDrainAccessor) this;
        TransportedItemStack heldItem = accessor.getHeldItem();
        int processingTicks = accessor.getProcessingTicks();

        // 1. 空闲检查
        if (heldItem == null) {
            accessor.setProcessingTicks(0);
            outputCooldown = 0;
            currentWaitTime = 1;
            lazytick$activeTimer = 0; // 重置活跃计时
            // [服务端发包] 重置为活跃
            if (!level.isClientSide && control != null)
                control.lazytick$setSyncedTier(1, Config.item_drain_delay_max);

            ci.cancel();
            return;
        }

        // 2. 冷却跳过逻辑 (服务端权威)
        // 客户端不跳过，保证动画流畅
        if (!level.isClientSide && outputCooldown > 0) {
            outputCooldown--;
            ci.cancel();
            return;
        }

        // ==========================================
        // 下面是逻辑主体
        // ==========================================
        boolean onClient = level.isClientSide && !isVirtual();
        if (level == null) { ci.cancel(); return; }

        // --- 处理流体 ---
        if (processingTicks > 0) {
            heldItem.prevBeltPosition = .5f;
            boolean wasAtBeginning = processingTicks == ItemDrainBlockEntity.FILLING_TIME;
            if (!onClient || processingTicks < ItemDrainBlockEntity.FILLING_TIME) {
                processingTicks--;
                accessor.setProcessingTicks(processingTicks);
            }

            boolean continueResult = accessor.invokeContinueProcessing();

            if (!continueResult) {
                accessor.setProcessingTicks(0);
                notifyUpdate();
                ci.cancel(); return;
            }

            if (accessor.getProcessingTicks() == ItemDrainBlockEntity.FILLING_TIME) {
                // 堵塞！
                applyBackoff();
                ci.cancel(); return;
            } else {
                // 正常处理中 -> 标记活跃
                lazytick$markActive(control);
            }

            if (wasAtBeginning != (processingTicks == ItemDrainBlockEntity.FILLING_TIME)) this.sendData();
            ci.cancel(); return;
        }

        // --- 物品移动 ---
        heldItem.prevBeltPosition = heldItem.beltPosition;
        heldItem.prevSideOffset = heldItem.sideOffset;
        heldItem.beltPosition += 1 / 8f;

        if (heldItem.beltPosition > 1) {
            heldItem.beltPosition = 1;
            if (onClient) { ci.cancel(); return; } // 客户端不处理输出逻辑

            Direction side = heldItem.insertedFrom;
            ItemStack tryExportingToBeltFunnel = getBehaviour(DirectBeltInputBehaviour.TYPE)
                    .tryExportingToBeltFunnel(heldItem.stack, side.getOpposite(), false);

            if (tryExportingToBeltFunnel != null) {
                if (tryExportingToBeltFunnel.getCount() != heldItem.stack.getCount()) {
                    if (tryExportingToBeltFunnel.isEmpty()) accessor.setHeldItem(null);
                    else heldItem.stack = tryExportingToBeltFunnel;
                    notifyUpdate();

                    // [工作判定] 物品数量减少 = 成功工作
                    lazytick$markActive(control);

                    ci.cancel(); return;
                }
                if (!tryExportingToBeltFunnel.isEmpty()) {
                    // 输出堵塞
                    applyBackoff();
                    ci.cancel(); return;
                }
            }

            BlockPos nextPosition = worldPosition.relative(side);
            DirectBeltInputBehaviour directBeltInputBehaviour = BlockEntityBehaviour.get(level, nextPosition, DirectBeltInputBehaviour.TYPE);

            if (directBeltInputBehaviour == null) {
                if (!BlockHelper.hasBlockSolidSide(level.getBlockState(nextPosition), level, nextPosition, side.getOpposite())) {
                    // 抛出逻辑 (成功)
                    ItemStack ejected = heldItem.stack;
                    Vec3 outPos = VecHelper.getCenterOf(worldPosition).add(Vec3.atLowerCornerOf(side.getNormal()).scale(.75));
                    Vec3 outMotion = Vec3.atLowerCornerOf(side.getNormal()).scale(1 / 8f).add(0, 1 / 8f, 0);
                    outPos = outPos.add(outMotion.normalize());
                    ItemEntity entity = new ItemEntity(level, outPos.x, outPos.y + 6 / 16f, outPos.z, ejected);
                    entity.setDeltaMovement(outMotion);
                    entity.setDefaultPickUpDelay();
                    entity.hurtMarked = true;
                    level.addFreshEntity(entity);

                    accessor.setHeldItem(null);
                    notifyUpdate();

                    // [工作判定] 抛出成功 = 成功工作
                    lazytick$markActive(control);

                } else {
                    // 堵塞
                    applyBackoff();
                }
                ci.cancel(); return;
            }

            if (!directBeltInputBehaviour.canInsertFromSide(side)) {
                applyBackoff();
                ci.cancel(); return;
            }

            ItemStack returned = directBeltInputBehaviour.handleInsertion(heldItem.copy(), side, false);

            if (returned.isEmpty()) {
                if (level.getBlockEntity(nextPosition) instanceof ItemDrainBlockEntity)
                    award(AllAdvancements.CHAINED_DRAIN);
                accessor.setHeldItem(null);
                notifyUpdate();

                // [工作判定] 全部送出 = 成功工作
                lazytick$markActive(control);

                ci.cancel(); return;
            }

            if (returned.getCount() == heldItem.stack.getCount()) {
                applyBackoff();
                ci.cancel(); return;
            }

            if (returned.getCount() != heldItem.stack.getCount()) {
                heldItem.stack = returned;
                notifyUpdate();

                // [工作判定] 部分送出（数量减少） = 成功工作
                lazytick$markActive(control);

                ci.cancel(); return;
            }

            ci.cancel(); return;
        }

        if (heldItem.prevBeltPosition < .5f && heldItem.beltPosition >= .5f) {
            if (!GenericItemEmptying.canItemBeEmptied(level, heldItem.stack)) {
                ci.cancel(); return;
            }
            heldItem.beltPosition = .5f;
            if (onClient) { ci.cancel(); return; }
            accessor.setProcessingTicks(ItemDrainBlockEntity.FILLING_TIME);
            this.sendData();

            // [工作判定] 开始处理流体 = 成功工作
            lazytick$markActive(control);
        }

        ci.cancel();
    }

    /**
     * 标记机器为“活跃中”。
     * 这会赋予机器 20 tick (1秒) 的“免疫期”。
     * 在免疫期内，即使发生堵塞，也不会进入休眠状态，防止状态闪烁。
     */
    @Unique
    private void lazytick$markActive(ILazyTickControl control) {
        if (level == null || level.isClientSide) return;

        currentWaitTime = 1;
        outputCooldown = 0;
        lazytick$activeTimer = 20; // 1秒免疫期

        if (control != null)
            control.lazytick$setSyncedTier(1, Config.item_drain_delay_max);
    }

    @Unique
    private void applyBackoff() {
        if (level != null && !level.isClientSide) {

            // [逻辑完善] 如果处于活跃免疫期内，不进行退避，保持全速运行
            if (lazytick$activeTimer > 0) {
                lazytick$activeTimer--;
                outputCooldown = 0; // 不睡觉
                currentWaitTime = 1;

                // 强制显示活跃（绿色）
                if (this instanceof ILazyTickControl control) {
                    control.lazytick$setSyncedTier(1, Config.item_drain_delay_max);
                }
                return;
            }

            // 只有当免疫期结束且依然堵塞时，才开始休眠
            outputCooldown = currentWaitTime;
            int nextTime = currentWaitTime + 5;
            currentWaitTime = Math.min(Config.item_drain_delay_max, nextTime);

            if (this instanceof ILazyTickControl control) {
                control.lazytick$setSyncedTier(currentWaitTime, Config.item_drain_delay_max);
            }
        }
    }
}