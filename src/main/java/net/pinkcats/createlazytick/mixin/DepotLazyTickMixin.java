package net.pinkcats.createlazytick.mixin;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.funnel.AbstractFunnelBlock;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.item.ItemHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.manager.ForcedActiveManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import static net.pinkcats.createlazytick.Config.depot_delay_max;

@Mixin(value = DepotBehaviour.class, remap = false)
public class DepotLazyTickMixin extends BlockEntityBehaviour {

    @Shadow Predicate<Direction> canFunnelsPullFrom;
    @Shadow ItemStackHandler processingOutputBuffer;
    @Shadow TransportedItemStack heldItem;
    @Shadow List<TransportedItemStack> incoming;
    @Shadow private boolean handleBeltFunnelOutput() { return false; }
    @Shadow TransportedItemStackHandlerBehaviour transportedHandler;
    @Shadow public static final BehaviourType<DepotBehaviour> TYPE = new BehaviourType<>();
    @Shadow protected boolean tick(TransportedItemStack heldItem) { return false; }
    @Shadow public BehaviourType<?> getType() { return TYPE; }

    @Unique private boolean lazytick$hasRegistered = false;
    @Unique private static final int GRACE_PERIOD = 5;
    @Unique private int createLazyTick$DepotDelayTick = 0;
    @Unique private int createLazyTick$CurrentDelayTick = 0;

    public DepotLazyTickMixin(SmartBlockEntity be) {
        super(be);
    }

    @Inject(method = "tick*",at=@At("HEAD" ),cancellable = true,remap = false)
    public void tick(CallbackInfo ci) {

        ILazyTickControl control = (this.blockEntity instanceof ILazyTickControl) ? (ILazyTickControl) this.blockEntity : null;

        if (!lazytick$hasRegistered) {
            if (control != null && control.lazytick$isForceDisabled() && this.getWorld() != null) {
                ForcedActiveManager.register(this.getWorld(), this.getPos());
            }
            lazytick$hasRegistered = true;
        }

        boolean forceDisabled = (control != null && control.lazytick$isForceDisabled());
        if (!Config.enable_lazy_tick || !Config.enable_lazy_depot || forceDisabled) {
            if (forceDisabled) {
                createLazyTick$DepotDelayTick = 0;
                createLazyTick$CurrentDelayTick = 0;
            }

            // 强制全速模式下，确保显示为活跃
            if (forceDisabled && !blockEntity.getLevel().isClientSide) {
                control.lazytick$setSyncedTier(1, 60);
            }
            return;
        }

        super.tick();
        Level world = blockEntity.getLevel();
        if (world == null) return;

        // 物品输入处理 (这部分逻辑通常两端都需要运行以保持平滑，且不容易出错)
        for (Iterator<TransportedItemStack> iterator = incoming.iterator(); iterator.hasNext();) {
            TransportedItemStack ts = iterator.next();
            boolean tick_res = tick(ts);
            //System.out.println(tick_res);

            if (!tick_res)
                continue;
            if (world.isClientSide && !blockEntity.isVirtual())
                continue;
            if (heldItem == null) {
                heldItem = ts;
            } else {
                if (!ItemHelper.canItemStackAmountsStack(heldItem.stack, ts.stack)) {
                    Vec3 vec = VecHelper.getCenterOf(blockEntity.getBlockPos());
                    Containers.dropItemStack(blockEntity.getLevel(), vec.x, vec.y + .5f, vec.z, ts.stack);
                } else {
                    heldItem.stack.grow(ts.stack.getCount());
                }
            }
            iterator.remove();
            blockEntity.notifyUpdate();
        }

        if (heldItem == null) {
            // 空闲 -> 活跃
            if (!world.isClientSide && control != null) control.lazytick$setSyncedTier(1, 60);
            ci.cancel();
            return;
        }
        if (!tick(heldItem)) {
            ci.cancel();
            return;
        }

        BlockPos pos = blockEntity.getBlockPos();

        // [服务端支配] 睡眠逻辑
        // 客户端不参与计算延迟，只负责接收状态
        if (createLazyTick$CurrentDelayTick > createLazyTick$DepotDelayTick) {
            createLazyTick$CurrentDelayTick = 0;
            // 醒来时不急着设为Active，防止闪烁，让下面的逻辑决定
        } else {
            createLazyTick$CurrentDelayTick = createLazyTick$CurrentDelayTick + 1;

            // 超过宽限期，进入懒加载状态
            if (createLazyTick$CurrentDelayTick > GRACE_PERIOD){
                // 服务端发送当前状态（使用总延迟DepotDelayTick，保持颜色稳定）
                if (!world.isClientSide && control != null) {
                    control.lazytick$setSyncedTier(createLazyTick$DepotDelayTick, Config.depot_delay_max);
                }

                // 服务端跳过逻辑以优化性能
                // 客户端**可以**跳过逻辑，因为位置没变，动画也是静止的
                if (!world.isClientSide) {
                    ci.cancel();
                }
                return;
            }
        }

        if (handleBeltFunnelOutput()) {
            ci.cancel();
            return;
        }

        BeltProcessingBehaviour processingBehaviour =
                BlockEntityBehaviour.get(world, pos.above(2), BeltProcessingBehaviour.TYPE);
        if (processingBehaviour == null) {
            ci.cancel();
            return;
        }
        if (!heldItem.locked && BeltProcessingBehaviour.isBlocked(world, pos)) {
            ci.cancel();
            return;
        }

        ItemStack previousItem = heldItem.stack;
        boolean wasLocked = heldItem.locked;

        // 只有服务端才应该实际处理物品逻辑，客户端仅做预测
        // 但 BeltProcessingBehaviour 内部通常包含客户端需要的粒子效果等
        // 这里我们主要防范的是上面的 handleBeltFunnelOutput
        BeltProcessingBehaviour.ProcessingResult result = wasLocked ? processingBehaviour.handleHeldItem(heldItem, transportedHandler)
                : processingBehaviour.handleReceivedItem(heldItem, transportedHandler);

        if (result == BeltProcessingBehaviour.ProcessingResult.PASS)
            if (createLazyTick$DepotDelayTick < depot_delay_max)
                createLazyTick$DepotDelayTick = createLazyTick$DepotDelayTick + Math.max(createLazyTick$CurrentDelayTick / 10, 1);

        if (result == BeltProcessingBehaviour.ProcessingResult.HOLD) {
            createLazyTick$DepotDelayTick = 0;
            createLazyTick$CurrentDelayTick = 0;
            // 正在处理 -> 活跃
            if (!world.isClientSide && control != null) control.lazytick$setSyncedTier(1, 60);
        }

        if (heldItem == null || result == BeltProcessingBehaviour.ProcessingResult.REMOVE) {
            heldItem = null;
            blockEntity.sendData();
            // 处理完毕 -> 活跃
            if (!world.isClientSide && control != null) control.lazytick$setSyncedTier(1, 60);

            ci.cancel();
            return;
        }
        heldItem.locked = result == BeltProcessingBehaviour.ProcessingResult.HOLD;
        if (heldItem.locked != wasLocked || !previousItem.equals(heldItem.stack, false)) {
            blockEntity.sendData();
        }
        ci.cancel();
    }

    @Inject(method = "handleBeltFunnelOutput",at=@At("HEAD" ),remap = false,cancellable = true)
    private void handleBeltFunnelOutput(CallbackInfoReturnable<Boolean> cir) {
        ILazyTickControl control = (this.blockEntity instanceof ILazyTickControl) ? (ILazyTickControl) this.blockEntity : null;
        Level world = blockEntity.getLevel();
        if (world == null) return;

        // [重要修复] 客户端去同步 (Client-side Desync) 的根源
        // 客户端由于不知道箱子已满，会错误地预测传输成功并删除手中物品。
        // 必须强制客户端返回 false，完全依赖服务端的数据包同步。
        if (world.isClientSide) {
            cir.setReturnValue(false);
            // 这里不 cancel，让它自然返回 false 即可，或者直接 cancel 也行，只要别跑下面的逻辑
            return;
        }

        BlockState funnel = getWorld().getBlockState(getPos().above());
        Direction funnelFacing = AbstractFunnelBlock.getFunnelFacing(funnel);
        if (funnelFacing == null || !canFunnelsPullFrom.test(funnelFacing.getOpposite())) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        for (int slot = 0; slot < processingOutputBuffer.getSlots(); slot++) {
            ItemStack previousItem = processingOutputBuffer.getStackInSlot(slot);
            if (previousItem.isEmpty())
                continue;

            ItemStack afterInsert = blockEntity.getBehaviour(DirectBeltInputBehaviour.TYPE)
                    .tryExportingToBeltFunnel(previousItem, null, false);

            if (afterInsert == null) {
                cir.setReturnValue(false);
                cir.cancel();
                return;
            }
            if (previousItem.getCount() != afterInsert.getCount()) {
                processingOutputBuffer.setStackInSlot(slot, afterInsert);
                blockEntity.notifyUpdate();
                // 成功输出 -> 活跃
                if (control != null) control.lazytick$setSyncedTier(1, 60);
                cir.setReturnValue(true);
                cir.cancel();
                return;
            }
        }

        ItemStack previousItem = heldItem.stack;
        ItemStack afterInsert = blockEntity.getBehaviour(DirectBeltInputBehaviour.TYPE)
                .tryExportingToBeltFunnel(previousItem, null, false);

        if (afterInsert == null) {
            cir.setReturnValue(false);
            cir.cancel();
            return;
        }

        if (afterInsert.getCount() != 0) {
            // 堵塞逻辑：增加延迟
            if (createLazyTick$DepotDelayTick < depot_delay_max+20) {
                createLazyTick$DepotDelayTick = createLazyTick$DepotDelayTick + Math.max(createLazyTick$CurrentDelayTick / 10, 1);
            }
            // 堵塞中 -> 保持显示当前延迟等级 (红色/黄色)
            if (control != null) {
                control.lazytick$setSyncedTier(createLazyTick$DepotDelayTick, Config.depot_delay_max);
            }
        } else {
            // 成功输出 -> 重置
            createLazyTick$DepotDelayTick = 0;
            createLazyTick$CurrentDelayTick = 0;
            // 成功 -> 活跃
            if (control != null) control.lazytick$setSyncedTier(1, 60);
        }

        //System.out.println(afterInsert);
        //System.out.println("previousItem Output:"+previousItem);

        if (previousItem.getCount() != afterInsert.getCount()) {
            if (afterInsert.isEmpty())
                heldItem = null;
            else
                heldItem.stack = afterInsert;
            blockEntity.notifyUpdate();
            cir.setReturnValue(true);
            cir.cancel();
            return;
        }

        cir.setReturnValue(false);
        cir.cancel();
    }

    @Unique
    public int getDepotDelayTick() {
        return createLazyTick$DepotDelayTick;
    }

    @Unique
    public int getCurrentDelayTick() {
        return createLazyTick$CurrentDelayTick;
    }
}