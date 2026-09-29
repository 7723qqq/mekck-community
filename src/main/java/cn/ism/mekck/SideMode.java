package cn.ism.mekck;

/**
 * 机器侧面配置模式。
 * <p>旧存档按 ordinal 序列化，新枚举值只能追加在末尾（NONE=0, PULL_INPUT=1, PUSH_OUTPUT=2,
 * PULL_INPUT_STORAGE=3），不得重排。</p>
 */
public enum SideMode {
    NONE,
    /** 抽取（至输入格）：管道/漏斗材料进入输入槽直接加工。 */
    PULL_INPUT,
    PUSH_OUTPUT,
    /** 抽取（至存储空间）：材料进入机器存储区缓冲（颜色与 Mekanism DataType.EXTRA 一致）。 */
    PULL_INPUT_STORAGE;

    /**
     * 循环到下一个/上一个模式；allowStorage=false 时跳过 PULL_INPUT_STORAGE
     * （无存储空间的机器不提供该模式）。
     */
    public SideMode cycle(boolean next, boolean allowStorage) {
        return cycle(next, allowStorage, true);
    }

    /**
     * 循环到下一个/上一个模式。
     *
     * @param allowStorage false 时跳过 PULL_INPUT_STORAGE（无存储空间的机器）
     * @param allowPush    false 时跳过 PUSH_OUTPUT（如气体只有单一储罐、无法区分原料与产物）
     */
    public SideMode cycle(boolean next, boolean allowStorage, boolean allowPush) {
        SideMode[] all = values();
        int idx = ordinal();
        for (int i = 0; i < all.length; i++) {
            idx = next ? (idx + 1) % all.length : (idx - 1 + all.length) % all.length;
            SideMode candidate = all[idx];
            if (candidate == PULL_INPUT_STORAGE && !allowStorage) continue;
            if (candidate == PUSH_OUTPUT && !allowPush) continue;
            return candidate;
        }
        return this;
    }

    public boolean isPull() {
        return this == PULL_INPUT || this == PULL_INPUT_STORAGE;
    }
}
