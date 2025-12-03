package net.pinkcats.createlazytick.mixin.saw;

import com.simibubi.create.content.kinetics.saw.SawBlockEntity;
import com.simibubi.create.content.processing.recipe.ProcessingInventory;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.pinkcats.createlazytick.CreateLazyTick;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.manager.CircuitBreakerManager;
import net.pinkcats.createlazytick.manager.SawRecipeCacheManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(SawBlockEntity.class)
public class SawCacheMixin {

    //检查 (HEAD),决定是否进行优化和是否缓存
    @SuppressWarnings("rawtypes")
    @Inject(method = "getRecipes", at = @At("HEAD"), cancellable = true, remap = false)
    public void createLazyTick$checkCache(CallbackInfoReturnable<List> cir) {
        // 1. 检查熔断器
        if (!CircuitBreakerManager.isOptimizationAvailable()) return;

        // 2. 检查预热状态
        if (!SawRecipeCacheManager.isReady()) return;

        // 3. 获取方块实体
        SawBlockEntity self = (SawBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) return;

        try {
            // 4. 获取输入
            ItemStack input = getInventoryStackSafe(self);
            if (input == null || input.isEmpty()) return;

            // 5.查看是否在黑/白名单内,如果是和名单直接返回空配方并取消接下来的机动逻辑
            if (SawRecipeCacheManager.isItemUniversallyInvalid(level, input.getItem())) {
                cir.setReturnValue(List.of());
                cir.cancel();
                return;
            }

            // 6.获取过滤器状态
            FilteringBehaviour filtering = getFilteringBehaviourSafe(self);
            if (filtering == null) return;

            // 7.获取过滤器物品,检查过滤器是否真实存在
            ItemStack filterStack = filtering.getFilter();
            boolean isFilterActive = filtering.isActive() && filterStack != null && !filterStack.isEmpty();

            // 8.检查对应缓存
            List<Recipe<?>> cached = SawRecipeCacheManager.getFromCache(
                    input,
                    filterStack,
                    isFilterActive
            );

            // 9.缓存不为空,返回缓存配方,并取消机动后续逻辑
            if (cached != null) {
                cir.setReturnValue(cached);
                cir.cancel();
            }
            // 10.(隐性)缓存为空,继续执行机动逻辑,准备捕获 (RETURN)
        } catch (Exception e) {
            handleError("CheckCache:", e);
        }
    }

    //捕获 (RETURN)
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Inject(method = "getRecipes", at = @At("RETURN"), remap = false)
    public void createLazyTick$captureCache(CallbackInfoReturnable<List> cir) {
        //检查优化目前状态是否正确
        if (!CircuitBreakerManager.isOptimizationAvailable()) return;
        if (!SawRecipeCacheManager.isReady()) return;

        //获取方块实体
        SawBlockEntity self = (SawBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide()) return;

        try {
            //获取输入和过滤器状态
            ItemStack input = getInventoryStackSafe(self);
            if (input == null || input.isEmpty()) return;
            FilteringBehaviour filtering = getFilteringBehaviourSafe(self);
            if (filtering == null) return;

            //获取过滤器和检验...
            ItemStack filterStack = filtering.getFilter();
            boolean isFilterActive = filtering.isActive() && filterStack != null && !filterStack.isEmpty();

            //获取返回的配方值
            List<Recipe<?>> calculatedRecipes = (List<Recipe<?>>) cir.getReturnValue();

            //存入缓存
            SawRecipeCacheManager.putIntoCache(
                    input,
                    filterStack,
                    isFilterActive,
                    level,
                    calculatedRecipes
            );
        } catch (Exception e) {
            handleError("CaptureCache:", e);
        }
    }

    //异常处理
    @Unique
    private void handleError(String phase, Exception e) {
        CircuitBreakerManager.incrementFailure();
        if (CircuitBreakerManager.getCircuitBreakerFailureCount() <= 1 || Config.enable_debug_logging) {
            CreateLazyTick.LOGGER.error("An exception occurred in the saw optimization [{}]: {}"
                    , phase, e.getMessage());
        }
    }

    //安全获取加工物品栏内物品
    @Unique
    private ItemStack getInventoryStackSafe(SawBlockEntity saw) {
        try {
            SawBlockEntityAccessor accessor = (SawBlockEntityAccessor) saw;
            ProcessingInventory inventory = accessor.getInventory();
            if (inventory != null) {
                ItemStack stack = inventory.getStackInSlot(0);
                return stack != null ? stack : ItemStack.EMPTY;
            }
        } catch (Exception e) {
            return null;
        }
        return ItemStack.EMPTY;
    }

    //安全获取过滤器状态
    @Unique
    private FilteringBehaviour getFilteringBehaviourSafe(SawBlockEntity saw) {
        try {
            SawBlockEntityAccessor accessor = (SawBlockEntityAccessor) saw;
            return accessor.getFiltering();
        } catch (Exception e) {
            return null;
        }
    }
}