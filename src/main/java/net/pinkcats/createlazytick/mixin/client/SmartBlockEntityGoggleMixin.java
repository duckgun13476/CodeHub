package net.pinkcats.createlazytick.mixin.client;

import com.simibubi.create.api.equipment.goggles.IHaveHoveringInformation;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.item.LazyTickClockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(value = SmartBlockEntity.class, remap = false)
public class SmartBlockEntityGoggleMixin implements IHaveHoveringInformation {

    @Override
    public boolean addToTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        if (!(mc.player.getMainHandItem().getItem() instanceof LazyTickClockItem)) return false;

        if ((Object) this instanceof ItemDrainBlockEntity) return false;

        if (this instanceof ILazyTickControl control) {

            // [UI修复] 防止图标遮挡
            if (tooltip.isEmpty()) {
                // 如果是第一行（置物台），加4个空格缩进，给左侧图标留位置
                tooltip.add(Component.literal("    LazyTick Status:").withStyle(ChatFormatting.GRAY));
            } else {
                // 如果不是第一行，先加个空行隔开
                tooltip.add(Component.literal("   "));
                tooltip.add(Component.literal("LazyTick Status:").withStyle(ChatFormatting.GRAY));
            }

            if (control.lazytick$isForceDisabled()) {
                tooltip.add(Component.literal(" [强制全速模式]").withStyle(ChatFormatting.RED));
                String op = control.lazytick$getOperatorName();
                if (!op.isEmpty()) {
                    tooltip.add(Component.literal(" 操作者: " + op).withStyle(ChatFormatting.DARK_GRAY));
                }
            } else {
                int maxTick = lazytick$getMaxTickFor((SmartBlockEntity) (Object) this);
                tooltip.add(control.lazytick$getSyncedTier().getDisplayComponent(maxTick));
            }

            return true;
        }

        return false;
    }

    @Unique
    private int lazytick$getMaxTickFor(SmartBlockEntity be) {
        if (be instanceof FunnelBlockEntity) return Config.funnel_delay_max;
        if (be instanceof DepotBlockEntity) return Config.depot_delay_max;
        return 60;
    }
}