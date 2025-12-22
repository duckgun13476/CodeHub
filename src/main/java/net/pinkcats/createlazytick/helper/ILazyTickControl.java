package net.pinkcats.createlazytick.helper;

public interface ILazyTickControl {
    // --- 强制控制功能 ---
    boolean lazytick$isForceDisabled();
    void lazytick$setForceDisabled(boolean forced, String operator);
    String lazytick$getOperatorName();

    // --- 状态显示功能 (新增) ---
    // 设置当前的档位 (传入数值，自动计算档位并决定是否发包)
    void lazytick$setSyncedTier(int currentTick, int maxTick);

    // 获取当前档位 (客户端渲染用)
    LazyTickTier lazytick$getSyncedTier();
}