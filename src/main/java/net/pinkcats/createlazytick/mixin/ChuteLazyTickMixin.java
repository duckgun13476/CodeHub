package net.pinkcats.createlazytick.mixin;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.logistics.chute.ChuteBlockEntity;
import com.simibubi.create.foundation.advancement.AllAdvancements;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.manager.ForcedActiveManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ChuteBlockEntity.class, remap = false)
public class ChuteLazyTickMixin extends SmartBlockEntity implements IHaveGoggleInformation {

    @Shadow
    boolean canPickUpItems;

    @Shadow
    VersionedInventoryTrackerBehaviour invVersionTracker;

    @Shadow
    public boolean canDirectlyInsertCached() {return false;}

    @Shadow
    private boolean canDirectlyInsert() {return false;}

    @Shadow
    private void handleInputFromBelow() {}

    @Shadow
    public float getItemMotion() {return 0;}

    @Shadow
    private void spawnParticles(float itemMotion) {}

    @Shadow
    private void tickAirStreams(float itemSpeed) {}

    @Shadow
    ItemStack item;

    @Shadow
    private void handleInputFromAbove() {}

    @Shadow
    net.createmod.catnip.animation.LerpedFloat itemPosition;

    @Shadow
    private boolean handleDownwardOutput(boolean simulate) {return false;}

    @Shadow
    private boolean handleUpwardOutput(boolean simulate) {return false;}

    public ChuteLazyTickMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);

    }

    @Unique
    int chuteTick = 0;
    @Unique
    int CurrentDelayTick = 1;
    @Unique
    boolean mistake = false;
    @Unique
    boolean lazytick$hasRegistered = false; // [新增] 注册标记

    @Unique
    private void LazyTickChute(boolean CanDownload){
        // [服务端权威] 逻辑限制在服务端
        if (level == null) return;
        if (!level.isClientSide){
            //if (!level.isClientSide) {
            // System.out.println("Chute"+CurrentDelayTick + "  " + chuteTick);
            //}

            // 获取控制接口
            ILazyTickControl control = (this instanceof ILazyTickControl) ? (ILazyTickControl) this : null;

            if (CanDownload){
                CurrentDelayTick=1;
                mistake = false;

                // [同步] 成功传输 -> 活跃
                if (control != null) control.lazytick$setSyncedTier(1, Config.chute_delay_max);
            }
            else {
                if (CurrentDelayTick < Config.chute_delay_max) {
                    if (mistake){
                        if (CurrentDelayTick <10){
                            CurrentDelayTick=CurrentDelayTick+1;
                        }else if (10 < CurrentDelayTick && CurrentDelayTick < 30){
                            CurrentDelayTick=CurrentDelayTick+2;
                        }else if (30 < CurrentDelayTick && CurrentDelayTick < 60){
                            CurrentDelayTick=CurrentDelayTick+3;
                        }else {
                            CurrentDelayTick=CurrentDelayTick+5;
                        }

                    }

                    if (CurrentDelayTick ==1) {
                        mistake = true;
                    }
                }

                // [同步] 堵塞/延迟增加 -> 发送状态 (保持颜色稳定)
                if (control != null) {
                    control.lazytick$setSyncedTier(CurrentDelayTick, Config.chute_delay_max);
                }
            }
        }
    }


    @Inject(method = "tick" ,at=@At("HEAD" ),cancellable = true,remap = false)
    public void tick(CallbackInfo ci) {
        // [新增] 注册与控制逻辑
        ILazyTickControl control = (this instanceof ILazyTickControl) ? (ILazyTickControl) this : null;
        if (!lazytick$hasRegistered) {
            if (control != null && control.lazytick$isForceDisabled() && this.level != null) {
                ForcedActiveManager.register(this.level, this.worldPosition);
            }
            lazytick$hasRegistered = true;
        }

        if (level == null) return;

        boolean forceDisabled = (control != null && control.lazytick$isForceDisabled());
        if (!Config.enable_lazy_tick || !Config.enable_lazy_chute || forceDisabled) {
            if (forceDisabled) {
                chuteTick = 0;          // 当前累计 tick 清零
                CurrentDelayTick = 1;   // 目标延迟重置为 1 (全速)
                mistake = false;        // 重置堵塞检测标志位
            }

            // 强制全速下显示活跃
            if (forceDisabled && !level.isClientSide) {
                control.lazytick$setSyncedTier(1, Config.chute_delay_max);
            }
            return;
        }

        super.tick();

        if (!level.isClientSide)
            canPickUpItems = canDirectlyInsert();

        boolean clientSide = level.isClientSide && !isVirtual();
        float itemMotion = getItemMotion();
        if (itemMotion != 0 && level != null && level.isClientSide)
            spawnParticles(itemMotion);
        tickAirStreams(itemMotion);

        if (item.isEmpty() && !clientSide) {
            if (itemMotion < 0)
                handleInputFromAbove();
            if (itemMotion > 0)
                handleInputFromBelow();
            ci.cancel();
            return;
        }

        float nextOffset = itemPosition.getValue() + itemMotion;

        if (level == null) return;
        if(!level.isClientSide){
            chuteTick ++;
            //System.out.println("chuteTick: "+chuteTick+"|"+CurrentDelayTick);
            if (chuteTick <CurrentDelayTick){
                // [同步] 睡眠期间维持显示状态
                if (control != null) {
                    control.lazytick$setSyncedTier(CurrentDelayTick, Config.chute_delay_max);
                }
                ci.cancel();
                return;
            }
            chuteTick = 0;
            //System.out.println("Run tick");
        }

        if (itemMotion < 0) {
            if (nextOffset < .5f) {

                boolean CanSimulateInput = handleDownwardOutput(true);

                LazyTickChute(CanSimulateInput);


                if  (!CanSimulateInput) {
                    nextOffset = .5f;
                }

                else if (nextOffset < 0) {
                    boolean CanActualInput = handleDownwardOutput(clientSide);
                    LazyTickChute(CanActualInput);



                    nextOffset = itemPosition.getValue();
                }
            }
        } else if (itemMotion > 0) {
            if (nextOffset > .5f) {
                if (!handleUpwardOutput(true))
                    nextOffset = .5f;
                else if (nextOffset > 1) {
                    handleUpwardOutput(clientSide);
                    nextOffset = itemPosition.getValue();
                }
            }
        }

        itemPosition.setValue(nextOffset);
        ci.cancel();
    }


    /**
     * @author PinkCats
     * @reason For Inject
     */
    @Overwrite
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(this).onlyInsertWhen((d) -> canDirectlyInsertCached()));
        behaviours.add(invVersionTracker = new VersionedInventoryTrackerBehaviour(this));
        registerAwardables(behaviours, AllAdvancements.CHUTE);
    }
}