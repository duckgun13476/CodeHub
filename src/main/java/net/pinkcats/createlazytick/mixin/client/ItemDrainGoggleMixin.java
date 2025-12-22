package net.pinkcats.createlazytick.mixin.client;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.pinkcats.createlazytick.helper.ILazyTickControl;
import net.pinkcats.createlazytick.item.LazyTickClockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 专门针对 Item Drain (分液池) 的显示拦截。
 * 原因是 ItemDrain 重写了 addToGoggleTooltip 且逻辑比较封闭，
 * 通用的 GoggleOverlayMixin 有时无法正确切入。
 */
@Mixin(value = ItemDrainBlockEntity.class, remap = false)
public class ItemDrainGoggleMixin {

    @Inject(method = "addToGoggleTooltip", at = @At("RETURN"), cancellable = true)
    private void lazytick$appendDrainInfo(List<Component> tooltip, boolean isPlayerSneaking, CallbackInfoReturnable<Boolean> cir) {
        // 1. 基础检查：客户端、玩家、手持时钟
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof LazyTickClockItem)) return;

        // 2. 类型转换与数据获取
        // 这里的 this 指的是 ItemDrainBlockEntity 实例
        // 因为我们在之前的 ItemDrainLazyTickMixin 里强制实现了接口，所以这里强转是安全的
        if (this instanceof ILazyTickControl control) {

            // 3. 追加信息
            // 即使 tooltip 原本是空的（分液池没水），我们加上去后它就不空了，Create 就会渲染出来
            if (!tooltip.isEmpty()) {
                tooltip.add(Component.literal("   "));
            }

            tooltip.add(Component.literal("LazyTick Status:").withStyle(ChatFormatting.GRAY));

            if (control.lazytick$isForceDisabled()) {
                tooltip.add(Component.literal(" [强制全速模式]").withStyle(ChatFormatting.RED));
                String op = control.lazytick$getOperatorName();
                if (!op.isEmpty()) {
                    tooltip.add(Component.literal(" 操作者: " + op).withStyle(ChatFormatting.DARK_GRAY));
                }
            } else {
                // 传入默认最大值用于显示，或者从 Config 读取
                tooltip.add(control.lazytick$getSyncedTier().getDisplayComponent(60));
            }

            // 4. 强制返回 true
            // 告诉 Create：“我有信息要显示，请画出那个灰色的框框”
            cir.setReturnValue(true);
        }
    }
}