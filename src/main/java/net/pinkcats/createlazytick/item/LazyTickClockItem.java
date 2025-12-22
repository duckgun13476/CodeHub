package net.pinkcats.createlazytick.item;

import net.minecraft.ChatFormatting;
import net.minecraft.MethodsReturnNonnullByDefault; // [新增]
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.pinkcats.createlazytick.helper.ILazyTickControl;

import javax.annotation.ParametersAreNonnullByDefault; // [新增]

@ParametersAreNonnullByDefault // [新增] 参数默认非空
@MethodsReturnNonnullByDefault // [新增] 返回值默认非空
public class LazyTickClockItem extends Item {

    public LazyTickClockItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        // 虽然加了非空注解，但为了稳健，判空是个好习惯，或者IDE会提示context.getPlayer()可能为空
        if (level.isClientSide || player == null) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);

        // 检查目标方块是否实现了我们的控制接口
        if (be instanceof ILazyTickControl control) {

            // 切换状态
            boolean currentStatus = control.lazytick$isForceDisabled();
            boolean newStatus = !currentStatus;

            String opName = player.getName().getString();

            // 设置新状态 (会自动触发发包同步)
            control.lazytick$setForceDisabled(newStatus, opName);

            // 发送反馈给玩家
            if (newStatus) {
                player.displayClientMessage(Component.literal("已强制取消该方块的懒加载优化！(全速运行)").withStyle(ChatFormatting.RED), true);
            } else {
                player.displayClientMessage(Component.literal("已恢复该方块的懒加载优化状态。").withStyle(ChatFormatting.GREEN), true);
            }

            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}