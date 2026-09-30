package cn.ism.mekck;

import net.minecraft.network.chat.TextColor;

public enum CuttingMachineFactoryTier {
    // 前 4 档 RGB 取自 Mek BaseTier（BASIC/ADVANCED/ELITE/ULTIMATE）；
    // 其余档位在 Mek 1.20.1 中无对应类型（不存在 AdvancedTier 类），颜色为自行设定。
    // 第 5 个参数是 energyEfficiency（能效乘数），取值的理由见该字段的注释。
    BASIC("basic", 3, 300_000, 60, 1.00, new int[]{95, 255, 184}),
    ADVANCED("advanced", 5, 500_000, 100, 0.80, new int[]{255, 128, 106}),
    ELITE("elite", 7, 700_000, 140, 0.65, new int[]{75, 248, 255}),
    ULTIMATE("ultimate", 9, 900_000, 180, 0.50, new int[]{247, 135, 255}),
    ABSOLUTE("absolute", 11, 1_100_000, 220, 0.40, new int[]{95, 255, 184}),
    SUPREME("supreme", 13, 1_300_000, 260, 0.32, new int[]{255, 128, 106}),
    COSMIC("cosmic", 15, 1_500_000, 300, 0.25, new int[]{75, 248, 255}),
    INFINITE("infinite", 17, 1_700_000, 340, 0.20, new int[]{247, 135, 255}),
    BLAZE("blaze", 25, 2_500_000, 380, 0.15, new int[]{255, 120, 40}), // 烈焰炽焱：烈焰橙；并行 5²；能耗延续 +40 规律
    CRYSTAL_MATRIX("crystal_matrix", 36, 3_600_000, 500, 0.10, new int[]{75, 248, 255}), // 并行 6²；能耗 500 FE/t
    // 并行 7²；免能耗（金色，与无尽升级组件物品名同色）。能效取 0 而非一个小正数，
    // 理由见该字段注释里「为什么免能耗档是 0 而不是 0.05」。
    NEBULA("nebula", 49, 4_900_000, 0, 0.00, new int[]{255, 170, 0}),
    SINGULARITY("singularity", 81, 8_100_000, 0, 0.00, new int[]{255, 85, 85}); // 奇点创世：顶级 9²；免能耗

    public final String name;
    public final int processes;
    public final int energyCapacity;
    public final int energyPerTick;

    /**
     * 本档的<b>能效乘数</b>（每单位产出的能耗系数，1.0 = 不打折）。
     *
     * <p><b>它与 {@link #energyPerTick} 是两件不同的事，别混用</b>：{@code energyPerTick}
     * 是给 Mek 的<b>声明值</b>（GUI 显示 / {@code AttributeEnergy.getUsage()}），
     * 只被当作「是不是免能耗档」的布尔量读（{@code == 0}）；
     * 真实扣电走的是 {@code ENERGY_PER_PROCESS × speed² × cons × 本值}，见
     * {@code CuttingFactoryTile.energyPerLanePerTick()}。</p>
     *
     * <p><b>设计意图：高阶更省电，因为贵。</b>玩家一次性付升级材料，换来长期更高的
     * 单位产出效率；于是 NEBULA / SINGULARITY 的免能耗不再是断崖，而是这条曲线的终点。</p>
     *
     * <p><b>取值形状</b>：前 8 档按每档约 ×0.8 递减（1.00 → 0.20，共 5 倍跨度），
     * 后 4 档加速下滑（0.15 / 0.10 / 0 / 0）让顶级档一眼可辨。之所以按「每档 ×0.8」
     * 而不是绝对值递减，是因为单位产出能耗正比于本值（推导见
     * {@code .superpowers/sdd/2026-09-29-mekck-phase1-upgrade-system/
     * energy-extract-and-efficiency-report.md}），
     * 等比曲线在日志坐标下是直线，玩家按「贵一档 ≈ 省两成电」就能估算出装价值。</p>
     *
     * <p><b>为什么免能耗档是 0 而不是 0.05</b>：NEBULA / SINGULARITY 的
     * {@link #energyPerTick == 0}，而 {@code energyPerLanePerTick()} 第一件事就是
     * 对它短路返回 0——公式根本算不到本值。给它们写 0.05 等于在配置文件里放一个
     * <b>永远读不到、且会误导人</b>的数字（整合包作者看到 0.05 会以为星云档要抽 5% 的电）。
     * 写 0 还多一层保险：万一哪天那个短路被去掉，曲线自己仍然终止在 0，行为不变。</p>
     *
     * <p>取值是<b>平衡参数</b>，运行期可由 {@code MekckConfig.energy_efficiency} 覆盖；
     * 本字段给的是默认值。</p>
     */
    public final double energyEfficiency;

    private final TextColor textColor;

    CuttingMachineFactoryTier(String name, int processes, int energyCapacity, int energyPerTick,
                               double energyEfficiency, int[] rgbCode) {
        this.name = name;
        this.processes = processes;
        this.energyCapacity = energyCapacity;
        this.energyPerTick = energyPerTick;
        this.energyEfficiency = energyEfficiency;
        this.textColor = TextColor.fromRgb(rgbCode[0] << 16 | rgbCode[1] << 8 | rgbCode[2]);
    }

    public TextColor getColor() {
        return textColor;
    }

    public String getBlockId() {
        return name + "_cutting_factory";
    }

    public String getCookingBlockId() {
        return name + "_cooking_factory";
    }

    public String getSkeweringBlockId() {
        return name + "_skewering_factory";
    }

    public String getGrillingBlockId() {
        return name + "_grill_factory";
    }

    public String getPlantingBlockId() {
        return name + "_planting_cutting_factory";
    }

    public String getGrindingBlockId() {
        return name + "_grinding_factory";
    }

    public String getIceMakerBlockId() {
        return name + "_ice_factory";
    }

    public CuttingMachineFactoryTier next() {
        CuttingMachineFactoryTier[] values = values();
        int ordinal = ordinal();
        return ordinal + 1 < values.length ? values[ordinal + 1] : null;
    }

    /**
     * 本等级是否拥有堆叠升级槽（Absolute ~ Nebula）。
     *
     * <p>奇点（Singularity）明确不支持：其设计前提是「基础并行即为极限并行」，
     * 再叠倍率没有意义。</p>
     *
     * <p><b>本方法是"是否支持堆叠升级"的唯一权威定义。</b>方块实体用它决定槽位是否存在，
     * {@code MekckConfig} 用它决定该等级的配置默认值。请勿在别处重新用
     * {@code processes >= 11} 或 {@code ordinal()} 复述这条规则——那正是历史上
     * 多处编码互相矛盾（11 / 6 / 64）的根源。</p>
     */
    public boolean supportsStackUpgrade() {
        return ordinal() >= ABSOLUTE.ordinal() && this != SINGULARITY;
    }
}