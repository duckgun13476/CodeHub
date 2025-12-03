package net.pinkcats.createlazytick;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = CreateLazyTick.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();


    private static final ForgeConfigSpec.BooleanValue ENABLE_LAZY_TICK = BUILDER
            .comment("Whether to enable lazy tick mixin")
            .define("enable_lazy_tick", true);
    //=================================================================================================


    // funnel
    private static final ForgeConfigSpec.BooleanValue ENABLE_LAZY_FUNNEL = BUILDER
            .comment("Whether to enable funnel lazy tick")
            .define("enable_lazy_funnel", true);
    private static final ForgeConfigSpec.IntValue FUNNEL_DELAY_MAX = BUILDER
            .comment("max delay tick if funnel is rest")
            .defineInRange("funnel_delay_max", 120, 20, Integer.MAX_VALUE);


    // chute
    private static final ForgeConfigSpec.BooleanValue ENABLE_LAZY_CHUTE = BUILDER
            .comment("Whether to enable chute lazy tick")
            .define("enable_lazy_chute", true);
    private static final ForgeConfigSpec.IntValue CHUTE_DELAY_MAX = BUILDER
            .comment("max delay tick if chute is rest")
            .defineInRange("chute_delay_max", 60, 20, Integer.MAX_VALUE);

    // depot
    private static final ForgeConfigSpec.BooleanValue ENABLE_LAZY_DEPOT = BUILDER
            .comment("Whether to enable depot lazy tick")
            .define("enable_lazy_depot", true);
    private static final ForgeConfigSpec.IntValue DEPOT_DELAY_MAX = BUILDER
            .comment("max delay tick if depot is rest")
            .defineInRange("depot_delay_max", 60, 20, Integer.MAX_VALUE);

    // saw
    public static final ForgeConfigSpec.BooleanValue ENABLE_SAW_OPTIMIZATION = BUILDER
            .comment("Whether to enable saw recipes cache")
            .define("enable_saw_optimization", true);

    public static final ForgeConfigSpec.BooleanValue ENABLE_DEBUG_LOGGING = BUILDER
            .comment("Whether to enable debug to show cache-stat")
            .define("enable_debug_logging", false);

    public static final ForgeConfigSpec.IntValue MAX_CACHE_SIZE = BUILDER
            .comment("The max size of cache")
            .defineInRange("max_cache_size", 1000, 100, 10000);





    //=================================================================================================

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean enable_lazy_tick;
    public static boolean enable_lazy_funnel;
    public static boolean enable_lazy_chute;
    public static boolean enable_lazy_depot;


    public static int funnel_delay_max;
    public static int chute_delay_max;
    public static int depot_delay_max;

    public static boolean enable_saw_optimization;
    public static boolean enable_debug_logging;
    public static int max_cache_size;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        enable_lazy_tick = ENABLE_LAZY_TICK.get();
        enable_lazy_funnel = ENABLE_LAZY_FUNNEL.get();
        enable_lazy_chute = ENABLE_LAZY_CHUTE.get();
        enable_lazy_depot = ENABLE_LAZY_DEPOT.get();

        funnel_delay_max = FUNNEL_DELAY_MAX.get();
        chute_delay_max = CHUTE_DELAY_MAX.get();
        depot_delay_max = DEPOT_DELAY_MAX.get();

        enable_saw_optimization = ENABLE_SAW_OPTIMIZATION.get();
        enable_debug_logging = ENABLE_DEBUG_LOGGING.get();
        max_cache_size = MAX_CACHE_SIZE.get();
    }
}
