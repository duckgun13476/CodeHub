package net.pinkcats.createlazytick.mixin;

import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlock;
import com.simibubi.create.content.kinetics.deployer.DeployerBlock;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.CreateLazyTick;
import net.pinkcats.createlazytick.bridge.Funnel;
import com.simibubi.create.api.equipment.goggles.IHaveHoveringInformation;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.logistics.funnel.*;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import com.simibubi.create.foundation.item.ItemHelper;
import net.createmod.catnip.math.BlockFace;
import net.createmod.catnip.animation.LerpedFloat;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// [新增] 导入接口与管理器
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.manager.ForcedActiveManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.simibubi.create.AllBlocks.DEPLOYER;
import static com.simibubi.create.AllBlocks.PORTABLE_STORAGE_INTERFACE;

@Mixin(value = FunnelBlockEntity.class, remap = false)
public class FunnelLazyTickMixin extends SmartBlockEntity implements IHaveHoveringInformation {

    @Shadow LerpedFloat flap;
    @Shadow private InvManipulationBehaviour invManipulation;
    @Shadow private ItemStack handleDirectBeltInput(TransportedItemStack stack, Direction side, boolean simulate) { return null; }
    @Shadow private boolean supportsAmountOnFilter() {return false;}
    @Shadow private FilteringBehaviour filtering;
    @Shadow private boolean supportsFiltering() {return false;}
    @Shadow private boolean supportsDirectBeltInput(Direction side) {return false;}
    @Shadow private int extractionCooldown;
    @Shadow private VersionedInventoryTrackerBehaviour invVersionTracker;

    // [新增] 惰性注册标记
    @Unique private boolean lazytick$hasRegistered = false;

    public FunnelLazyTickMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow public void flap(boolean inward) {}
    @Shadow private void activateExtractingBeltFunnel() {}
    @Shadow private void activateExtractor() {}
    @Shadow public int getAmountToExtract() {return 0;}
    @Shadow public ItemHelper.ExtractionCountMode getModeToExtract() {return null;}

    @Unique
    public Funnel.Mode determineCurrentMode() {
        BlockState state = getBlockState();
        if (!FunnelBlock.isFunnel(state)) return Funnel.Mode.INVALID;
        if (state.getOptionalValue(BlockStateProperties.POWERED).orElse(false)) return Funnel.Mode.PAUSED;
        if (state.getBlock() instanceof BeltFunnelBlock) {
            BeltFunnelBlock.Shape shape = state.getValue(BeltFunnelBlock.SHAPE);
            if (shape == BeltFunnelBlock.Shape.PULLING) return Funnel.Mode.TAKING_FROM_BELT;
            if (shape == BeltFunnelBlock.Shape.PUSHING) return Funnel.Mode.PUSHING_TO_BELT;
            BeltBlockEntity belt = BeltHelper.getSegmentBE(level, worldPosition.below());
            if (belt != null)
                return belt.getMovementFacing() == state.getValue(BeltFunnelBlock.HORIZONTAL_FACING)
                        ? Funnel.Mode.PUSHING_TO_BELT
                        : Funnel.Mode.TAKING_FROM_BELT;
            return Funnel.Mode.INVALID;
        }
        if (state.getBlock() instanceof FunnelBlock)
            return state.getValue(FunnelBlock.EXTRACTING) ? Funnel.Mode.EXTRACT : Funnel.Mode.COLLECT;
        return Funnel.Mode.INVALID;
    }

    @Unique
    private void createlazytick$startCooldown() {
        extractionCooldown = AllConfigs.server().logistics.defaultExtractionTimer.get() + ActualMultiCount;
        //System.out.println("extraction cooldown: " + extractionCooldown);
    }

    @Shadow public void onTransfer(ItemStack stack) {}
    @Unique private int ActualMultiCount = 0;

    @Inject(method = "tick" ,at=@At("HEAD" ),cancellable = true,remap = false)
    public void tick(CallbackInfo ci) {
        // [新增] 1. 获取控制接口与注册
        ILazyTickControl control = (this instanceof ILazyTickControl) ? (ILazyTickControl) this : null;
        if (!lazytick$hasRegistered) {
            if (control != null && control.lazytick$isForceDisabled() && this.level != null) {
                ForcedActiveManager.register(this.level, this.worldPosition);
            }
            lazytick$hasRegistered = true;
        }

        // [新增] 2. 强制活跃与配置检查
        boolean forceDisabled = (control != null && control.lazytick$isForceDisabled());
        if (!Config.enable_lazy_tick || Config.enable_belt_delay || forceDisabled) {
            if (level == null) return;
            // [同步] 强制全速模式下，通知客户端显示 Active
            if (forceDisabled && !level.isClientSide) {
                control.lazytick$setSyncedTier(1, Config.funnel_delay_max);
            }
            return;
        }

        flap.tickChaser();
        if (createlazytick$HasInterface){
            ActualMultiCount = 0;
        }

        if (level == null) return;

        if(!createlazytick$HasInterface){
            if (extractionCooldown > 0) {
                // [同步] 正在冷却中... (Inject Point A)
                // 只有服务端发包，确保权威性
                if (control != null && !level.isClientSide) {
                    int max = AllConfigs.server().logistics.defaultExtractionTimer.get() + ActualMultiCount;
                    // [修改] 稳定睡眠：发送 max 作为当前值，保持颜色稳定，不再发送倒计时
                    control.lazytick$setSyncedTier(max, max);
                }

                extractionCooldown--;
                ci.cancel();
                return;
            }
            createlazytick$startCooldown();
        }

        super.tick();

        Funnel.Mode mode = determineCurrentMode();

        if (level.isClientSide) {
            ci.cancel();
            return;
        }

        // Redstone resets the extraction cooldown
        if (mode == Funnel.Mode.PAUSED)
            extractionCooldown = 0;
        if (mode == Funnel.Mode.TAKING_FROM_BELT) {
            ci.cancel();
            return;
        }

        if (createlazytick$HasInterface){
            if (extractionCooldown > 0) {
                // [同步] 接口模式冷却 (Inject Point B)
                if (control != null && !level.isClientSide) {
                    int max = AllConfigs.server().logistics.defaultExtractionTimer.get() + ActualMultiCount;
                    // [修改] 稳定睡眠：同上
                    control.lazytick$setSyncedTier(max, max);
                }

                extractionCooldown--;
                ci.cancel();
                return;
            }
        }
        BlockState blockState = getBlockState();
        BlockPos blockPos = getBlockPos();
        createlazytick$HasInterface = IsMovingInterface(blockPos,blockState);

        if (mode == Funnel.Mode.PUSHING_TO_BELT)
            activateExtractingBeltFunnel();
        if (mode == Funnel.Mode.EXTRACT)
            activateExtractor();

        ci.cancel();
    }

    @Unique private Direction createlazytick$targetDirection = null;
    @Unique private boolean createlazytick$HasInterface = false;

    @Unique
    private boolean IsMovingInterface(BlockPos blockPos,BlockState blockState) {
        if (createlazytick$targetDirection == null) {
            if (level != null) {
                try {
                    Direction[] allDirections = {Direction.UP, Direction.DOWN,
                            Direction.NORTH, Direction.SOUTH,
                            Direction.EAST, Direction.WEST};

                    for (Direction dir : allDirections) {
                        Block block = level.getBlockState(blockPos.relative(dir)).getBlock();
                        // 找到目标方块：记录方向并返回true
                        if (block == PORTABLE_STORAGE_INTERFACE.get() || block == DEPLOYER.get()) {
                            createlazytick$targetDirection = dir;
                            return true;
                        }
                    }
                }
                catch(Exception e){
                    CreateLazyTick.LOGGER.error(e.getMessage());
                    return false;
                }
            }
        }
        else {
            if (level != null) {
                Block target = level.getBlockState(blockPos.relative(createlazytick$targetDirection)).getBlock();
                return target == PORTABLE_STORAGE_INTERFACE.get() || target == DEPLOYER.get();
            }
        }
        return false;
    }

    @Inject(method = "activateExtractingBeltFunnel" ,at=@At("HEAD" ),cancellable = true,remap = false)
    private void activateExtractingBeltFunnel(CallbackInfo ci) {
        // [新增] 获取控制接口
        ILazyTickControl control = (this instanceof ILazyTickControl) ? (ILazyTickControl) this : null;

        if (!Config.enable_lazy_tick || !Config.enable_lazy_funnel) {
            return;
        }

        if (level == null) return;

        if (invVersionTracker.stillWaiting(invManipulation)) {
            ci.cancel();
            return;
        }
        BlockState blockState = getBlockState();

        Direction facing = blockState.getValue(BeltFunnelBlock.HORIZONTAL_FACING);
        DirectBeltInputBehaviour inputBehaviour =
                BlockEntityBehaviour.get(level, worldPosition.below(), DirectBeltInputBehaviour.TYPE);

        if (inputBehaviour == null) { ci.cancel(); return; }
        if (!inputBehaviour.canInsertFromSide(facing)) { ci.cancel(); return; }
        if (inputBehaviour.isOccupied(facing)) { ci.cancel(); return; }

        int amountToExtract = getAmountToExtract();
        ItemHelper.ExtractionCountMode mode = getModeToExtract();
        MutableBoolean deniedByInsertion = new MutableBoolean(false);

        AtomicInteger extract_time = new AtomicInteger();
        // delay from this
        ItemStack stack = invManipulation.extract(mode, amountToExtract, s -> {
            extract_time.getAndIncrement();
            ItemStack handleInsertion = inputBehaviour.handleInsertion(s, facing, true);
            if (handleInsertion.isEmpty()) {
                return true;
            }
            deniedByInsertion.setTrue();
            return false;
        });

        if (stack.isEmpty()) {
            //System.out.println("stack is empty");
            if (ActualMultiCount < Config.funnel_delay_max) {
                ActualMultiCount = ActualMultiCount + 10;
            }
            createlazytick$startCooldown();

            // [同步] 提取失败，进入睡眠 (Inject Point C)
            if (control != null && !level.isClientSide) {
                int max = AllConfigs.server().logistics.defaultExtractionTimer.get() + ActualMultiCount;
                // [修改] 稳定睡眠：发送 max 保持状态稳定
                control.lazytick$setSyncedTier(max, max);
            }

            if (deniedByInsertion.isFalse())
                invVersionTracker.awaitNewVersion(invManipulation.getInventory());

            // 保留原有逻辑的 return，不做修改
            return;
        }

        if (ActualMultiCount >= 10) {
            ActualMultiCount = Math.max(0, ActualMultiCount - 30);
        }

        flap(false);
        onTransfer(stack);
        inputBehaviour.handleInsertion(stack, facing, false);
        createlazytick$startCooldown();

        // [同步] 提取成功 (Inject Point D)
        if (control != null && !level.isClientSide) {
            // [修改] 诚实唤醒：计算当前实际速度
            // 不再强行发送 1 (全速)，而是发送减少罚时后的真实状态
            int baseDelay = AllConfigs.server().logistics.defaultExtractionTimer.get();
            int currentTotalDelay = baseDelay + ActualMultiCount;

            // 如果罚时已清零，currentTotalDelay 会很小（如 8），显示为 Green
            // 如果罚时还剩很多，显示为 Yellow/Orange，真实反映机器状态
            control.lazytick$setSyncedTier(currentTotalDelay, Config.funnel_delay_max);
        }

        ci.cancel();
    }

    /**
     * @author PinkCats
     * @reason For Inject
     */
    @Overwrite
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        invManipulation =
                new InvManipulationBehaviour(this, (w, p, s) -> new BlockFace(p, AbstractFunnelBlock.getFunnelFacing(s)
                        .getOpposite()));
        behaviours.add(invManipulation);

        behaviours.add(invVersionTracker = new VersionedInventoryTrackerBehaviour(this));

        filtering = new FilteringBehaviour(this, new FunnelFilterSlotPositioning());
        filtering.showCountWhen(this::supportsAmountOnFilter);
        filtering.onlyActiveWhen(this::supportsFiltering);
        filtering.withCallback($ -> invVersionTracker.reset());
        behaviours.add(filtering);

        behaviours.add(new DirectBeltInputBehaviour(this).onlyInsertWhen(this::supportsDirectBeltInput)
                .setInsertionHandler(this::handleDirectBeltInput));
        registerAwardables(behaviours, AllAdvancements.FUNNEL);
    }
}