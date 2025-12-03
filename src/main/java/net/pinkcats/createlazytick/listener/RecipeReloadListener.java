package net.pinkcats.createlazytick.listener;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.pinkcats.createlazytick.CreateLazyTick;
import net.pinkcats.createlazytick.manager.SawRecipeCacheManager;
import net.pinkcats.createlazytick.manager.CircuitBreakerManager;
import org.jetbrains.annotations.NotNull;

@Mod.EventBusSubscriber(modid = CreateLazyTick.MODID)
public class RecipeReloadListener {

    // 1. 服务器启动
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        SawRecipeCacheManager.clearAllCaches();
        CircuitBreakerManager.resetCircuitBreaker();
        // 启动后预热半分钟，等待配方完全加载
        SawRecipeCacheManager.setWarmupTimer(30);
        CreateLazyTick.LOGGER.info("Clearing up all caches and initiating...");
        CreateLazyTick.LOGGER.info("Server is about to start,saw optimization will start after 30 sec" +
                " in order to wait for recipes to be fully loaded.");
    }

    // 2. /reload 或数据包重载
    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(@NotNull ResourceManager resourceManager) {
                SawRecipeCacheManager.clearAllCaches();
                // 重载后预热半分钟，等待配方完全加载
                SawRecipeCacheManager.setWarmupTimer(30);
                CreateLazyTick.LOGGER.info("Reloading detected,clear up all caches...");
                CreateLazyTick.LOGGER.info("Reloading detected,saw optimization will start after at 30 sec " +
                        "in order to wait for recipes to be fully loaded.");
            }
        });
    }

    // 3. 配置文件重载
    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(CreateLazyTick.MODID)) {
            // 配置重载一般较快，只清缓存，不需要长预热
            SawRecipeCacheManager.clearAllCaches();
            CircuitBreakerManager.resetCircuitBreaker();
            CreateLazyTick.LOGGER.info("Config reloaded,clear up all caches...");
        }
    }
}