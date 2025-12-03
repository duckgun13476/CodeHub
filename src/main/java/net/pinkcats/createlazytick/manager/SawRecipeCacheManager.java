package net.pinkcats.createlazytick.manager;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import net.pinkcats.createlazytick.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class SawRecipeCacheManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("CreateLazyTick");

    private static volatile long optimizationReadyTime = 0;

    private static long lastStatsPrintTime = 0;
    private static final long STATS_PRINT_INTERVAL = 60000; // 60秒打印一次统计

    //使用黑白名单来存储方块情况,黑名单对应该方块完全没有相关配方,白名单对应该方块有配方,但可能没有被调用
    private static final Set<Item> NO_RECIPE_ITEM_TYPES = ConcurrentHashMap.newKeySet();
    private static final Set<Item> KNOWN_VALID_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Object SCAN_LOCK = new Object();

    //常规缓存
    private static final Map<CacheKey, List<Recipe<?>>> GLOBAL_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(512, 0.75f, true) {
                //重写方法,以适应环境(移除指定最老缓存)
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, List<Recipe<?>>> eldest) {
                    boolean remove = size() > Config.max_cache_size;
                    if (remove) EVICTION_COUNT.incrementAndGet();
                    return remove;
                }
            });

    //debug输出统计数据用
    private static final AtomicInteger GLOBAL_RECIPE_VERSION = new AtomicInteger(0);
    private static final AtomicLong HIT_COUNT = new AtomicLong(0);
    private static final AtomicLong MISS_COUNT = new AtomicLong(0);
    private static final AtomicLong EVICTION_COUNT = new AtomicLong(0);

    private SawRecipeCacheManager() {}

    //设置暖机时间(雾)
    public static void setWarmupTimer(int seconds) {
        optimizationReadyTime = System.currentTimeMillis() + (seconds * 1000L);
        LOGGER.info("Saw optimization will be paused in {} sec for recipes to load completely...", seconds);
    }

    //检查是否已经(暖机)就绪
    public static boolean isReady() {
        return System.currentTimeMillis() >= optimizationReadyTime;
    }

    //检查物品是否在黑名单内,以及检查物品是否有配方(没有则丢进黑名单)
    public static boolean isItemUniversallyInvalid(Level level, Item item) {
        if (!isReady()) return false;

        if (NO_RECIPE_ITEM_TYPES.contains(item)) return true;
        if (KNOWN_VALID_ITEMS.contains(item)) return false;

        synchronized (SCAN_LOCK) {
            if (NO_RECIPE_ITEM_TYPES.contains(item)) return true;
            if (KNOWN_VALID_ITEMS.contains(item)) return false;

            boolean hasRecipe = scanForAnyRecipe(level, item);

            if (hasRecipe) {
                KNOWN_VALID_ITEMS.add(item);
                return false;
            } else {
                NO_RECIPE_ITEM_TYPES.add(item);
                //仅首次加入黑名单时打印一条日志
                if (Config.enable_debug_logging) {
                    LOGGER.debug("No recipes item detected,joining blacklist: {}", ForgeRegistries.ITEMS.getKey(item));
                }
                return true;
            }
        }
    }

    //获取缓存
    public static List<Recipe<?>> getFromCache(ItemStack input, ItemStack filter, boolean filterActive) {
        if (!isReady()) return null;
        if (input == null || input.isEmpty()) return null;

        //尝试打印统计信息(内部检查 Debug 开关和时间间隔)
        tryPrintStats();

        CacheKey key = new CacheKey(input, filter, filterActive, GLOBAL_RECIPE_VERSION.get());
        //equals重写在此处被隐性调用
        List<Recipe<?>> cached = GLOBAL_CACHE.get(key);

        if (cached != null) {
            HIT_COUNT.incrementAndGet();
            return cached;
        }

        MISS_COUNT.incrementAndGet();
        return null;
    }

    //收录缓存
    public static void putIntoCache(ItemStack input, ItemStack filter, boolean filterActive,
                                    Level level, List<Recipe<?>> results) {
        if (!isReady()) return;
        if (input == null || input.isEmpty()) return;

        //如果缓存结果不为空则复制并收录到list
        List<Recipe<?>> immutableResult = (results != null && !results.isEmpty())
                ? List.copyOf(results) : List.of();

        CacheKey key = new CacheKey(input, filter, filterActive, GLOBAL_RECIPE_VERSION.get());
        GLOBAL_CACHE.put(key, immutableResult);
    }

    //打印调试信息
    private static void tryPrintStats() {
        if (!Config.enable_debug_logging) return;

        long now = System.currentTimeMillis();
        if (now - lastStatsPrintTime > STATS_PRINT_INTERVAL) {
            lastStatsPrintTime = now;
            LOGGER.info("Saw optimization stats: {}", getStats());
        }
    }

    //查询是否有配方配方
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean scanForAnyRecipe(Level level, Item item) {
        List<RecipeType<?>> typesToCheck = new ArrayList<>();
        typesToCheck.add(AllRecipeTypes.CUTTING.getType());

        //是否允许切石配方(机动原生配置)
        if (AllConfigs.server().recipes.allowStonecuttingOnSaw.get()) {
            typesToCheck.add(RecipeType.STONECUTTING);
        }

        //比对是否有配方
        for (RecipeType<?> type : typesToCheck) {
            List<Recipe<?>> recipes = (List<Recipe<?>>) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
            for (Recipe<?> recipe : recipes) {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    for (ItemStack stack : ingredient.getItems()) {
                        if (stack.getItem() == item) return true;
                    }
                }
            }
        }
        return false;
    }

    //清除所有缓存
    public static synchronized void clearAllCaches() {
        GLOBAL_CACHE.clear();
        NO_RECIPE_ITEM_TYPES.clear();
        KNOWN_VALID_ITEMS.clear();
        GLOBAL_RECIPE_VERSION.incrementAndGet();
        HIT_COUNT.set(0);
        MISS_COUNT.set(0);
        EVICTION_COUNT.set(0);
        LOGGER.info("Saw cache and blacklist & whitelist has been cleared");
    }

    //构建优化具体数据的方法
    private static CacheStats getStats() {
        return new CacheStats(GLOBAL_CACHE.size(), HIT_COUNT.get(), MISS_COUNT.get(),
                EVICTION_COUNT.get(), GLOBAL_RECIPE_VERSION.get(), NO_RECIPE_ITEM_TYPES.size(), KNOWN_VALID_ITEMS.size());
    }

    //优化数据的静态类
    private static final class CacheStats {
        public final int totalEntries;
        public final long hits;
        public final long misses;
        public final long evictions;
        public final int currentVersion;
        public final int blockedTypes;
        public final int validTypes;

        public CacheStats(int totalEntries, long hits, long misses, long evictions, int currentVersion, int blockedTypes, int validTypes) {
            this.totalEntries = totalEntries;
            this.hits = hits;
            this.misses = misses;
            this.evictions = evictions;
            this.currentVersion = currentVersion;
            this.blockedTypes = blockedTypes;
            this.validTypes = validTypes;
        }

        @Override
        public String toString() {
            // 计算缓存命中率(排除新加入的物品,目前为100%)(新加入物品会造成1次Miss)
            long totalReq = hits + misses;
            double hitRate = totalReq > 0 ? (double) hits / totalReq * 100.0 : 0.0;

            return String.format("Size: %d | Hits: %d (%.1f%%) | Misses: %d | Evict: %d | Blacklist: %d | Whitelist: %d",
                    totalEntries, hits, hitRate, misses, evictions, blockedTypes, validTypes);
        }
    }

    //缓存键构造类
    private static final class CacheKey {
        private final String itemId;
        private final boolean filterActive;
        private final int recipeVersion;
        private final String filterItemId;
        private final CompoundTag tag;
        private final CompoundTag filterTag;
        private final int hashCode;

        //缓存键
        public CacheKey(ItemStack input, ItemStack filter, boolean filterActive, int recipeVersion) {
            this.itemId = getSafeItemRegistryName(input);
            this.filterActive = filterActive;
            this.recipeVersion = recipeVersion;
            this.tag = input.hasTag() ? input.getTag() : null;

            if (filter != null && !filter.isEmpty()) {
                this.filterItemId = getSafeItemRegistryName(filter);
                this.filterTag = filter.hasTag() ? filter.getTag() : null;
            } else {
                this.filterItemId = "empty";
                this.filterTag = null;
            }
            this.hashCode = computeHashCode();
        }

        //构建哈希码
        private int computeHashCode() {
            int h = 17;
            h = h * 31 + itemId.hashCode();
            h = h * 31 + filterItemId.hashCode();
            h = h * 31 + (filterActive ? 1231 : 1237);
            h = h * 31 + recipeVersion;
            return h;
        }

        //重写equals比对
        //比对多种属性,先哈希,后版本,后过滤状态,后物品id,后过滤器id,后对比物品nbt地址(避免第一次直接nbt对比),最后对比过滤器nbt地址
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) o;

            if (this.hashCode != that.hashCode) return false;
            if (this.recipeVersion != that.recipeVersion) return false;
            if (this.filterActive != that.filterActive) return false;
            if (!Objects.equals(this.itemId, that.itemId)) return false;
            if (!Objects.equals(this.filterItemId, that.filterItemId)) return false;

            boolean tagMatch = (this.tag == that.tag);
            if (!tagMatch) tagMatch = Objects.equals(this.tag, that.tag);
            if (!tagMatch) return false;

            boolean filterTagMatch = (this.filterTag == that.filterTag);
            if (!filterTagMatch) filterTagMatch = Objects.equals(this.filterTag, that.filterTag);
            return filterTagMatch;
        }

        @Override
        public int hashCode() { return hashCode; }

        @Override
        public String toString() {
            return String.format("Key{item=%s}", itemId);
        }

        private String getSafeItemRegistryName(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return "empty";
            ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return key != null ? key.toString() : "unknown";
        }
    }
}