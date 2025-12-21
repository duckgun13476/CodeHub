package net.pinkcats.createlazytick.mixin;

import com.simibubi.create.content.fluids.spout.FillingBySpout;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.pinkcats.createlazytick.Config;
import net.pinkcats.createlazytick.helper.SpoutCacheKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.pinkcats.createlazytick.CreateLazyTick.IsServerReload;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@Mixin(value = FillingBySpout.class, remap = false)
public abstract class SpoutRecipeMixin {
    // 缓存定义
    @Unique
    private static final Map<Item, Boolean> createLazyTick$CAN_FILL_CACHE = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Item, Boolean> eldest) {
            return size() > Config.spout_cache_max;
        }
    });

    @Unique
    private static final Map<SpoutCacheKey, Integer> createLazyTick$AMOUNT_CACHE = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SpoutCacheKey, Integer> eldest) {
            return size() > Config.spout_cache_max;
        }
    });

    // 冷却时间戳
    @Unique
    private static long lazytick$cooldownEndTime = 0;

    @Inject(method = "canItemBeFilled", at = @At("HEAD"), cancellable = true)
    private static void createLazyTick$checkCanFillCache(Level world, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.enable_lazy_tick || !Config.enable_cache_spout) return;

        if (IsServerReload) {
            createLazyTick$CAN_FILL_CACHE.clear();
            createLazyTick$AMOUNT_CACHE.clear();

            // 使用全局缓存记录延迟 (global_cache_record_delay)
            long delayMillis = Config.global_cache_record_delay * 1000L;
            lazytick$cooldownEndTime = System.currentTimeMillis() + delayMillis;
        }

        // 检查是否处于重载冷却期
        if (System.currentTimeMillis() < lazytick$cooldownEndTime) {
            return;
        }

        if (stack.hasTag()) return;

        Item item = stack.getItem();
        if (createLazyTick$CAN_FILL_CACHE.containsKey(item)) {
            cir.setReturnValue(createLazyTick$CAN_FILL_CACHE.get(item));
            cir.cancel();
        }
    }

    @Inject(method = "canItemBeFilled", at = @At("RETURN"))
    private static void createLazyTick$captureCanFillCache(Level world, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (!Config.enable_lazy_tick || !Config.enable_cache_spout) return;

        if (System.currentTimeMillis() < lazytick$cooldownEndTime) return;

        if (stack.hasTag()) return;

        createLazyTick$CAN_FILL_CACHE.put(stack.getItem(), cir.getReturnValue());
    }

    @Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
    private static void createLazyTick$checkAmountCache(Level world, ItemStack stack, FluidStack availableFluid, CallbackInfoReturnable<Integer> cir) {
        if (!Config.enable_lazy_tick || !Config.enable_cache_spout) return;

        if (IsServerReload) {
            createLazyTick$CAN_FILL_CACHE.clear();
            createLazyTick$AMOUNT_CACHE.clear();
            long delayMillis = Config.global_cache_record_delay * 1000L;
            lazytick$cooldownEndTime = System.currentTimeMillis() + delayMillis;
        }

        if (System.currentTimeMillis() < lazytick$cooldownEndTime) {
            return;
        }

        if (stack.hasTag()) return;
        if (availableFluid.hasTag()) return;

        // 查询缓存
        SpoutCacheKey key = new SpoutCacheKey(stack.getItem(), availableFluid.getFluid());
        if (createLazyTick$AMOUNT_CACHE.containsKey(key)) {
            cir.setReturnValue(createLazyTick$AMOUNT_CACHE.get(key));
            cir.cancel();
        }
    }

    @Inject(method = "getRequiredAmountForItem", at = @At("RETURN"))
    private static void createLazyTick$captureAmountCache(Level world, ItemStack stack, FluidStack availableFluid, CallbackInfoReturnable<Integer> cir) {
        if (!Config.enable_lazy_tick || !Config.enable_cache_spout) return;

        if (System.currentTimeMillis() < lazytick$cooldownEndTime) return;

        if (stack.hasTag()) return;
        if (availableFluid.hasTag()) return;

        // 构建缓存(物品 + 流体 做键,流体数量做值)
        SpoutCacheKey key = new SpoutCacheKey(stack.getItem(), availableFluid.getFluid());
        createLazyTick$AMOUNT_CACHE.put(key, cir.getReturnValue());
    }
}