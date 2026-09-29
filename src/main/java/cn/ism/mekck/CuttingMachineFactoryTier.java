package cn.ism.mekck;

import net.minecraft.network.chat.TextColor;

public enum CuttingMachineFactoryTier {
    // 前 4 档 RGB 取自 Mek BaseTier（BASIC/ADVANCED/ELITE/ULTIMATE）；
    // 其余档位在 Mek 1.20.1 中无对应类型（不存在 AdvancedTier 类），颜色为自行设定
    BASIC("basic", 3, 300_000, 60, new int[]{95, 255, 184}),
    ADVANCED("advanced", 5, 500_000, 100, new int[]{255, 128, 106}),
    ELITE("elite", 7, 700_000, 140, new int[]{75, 248, 255}),
    ULTIMATE("ultimate", 9, 900_000, 180, new int[]{247, 135, 255}),
    ABSOLUTE("absolute", 11, 1_100_000, 220, new int[]{95, 255, 184}),
    SUPREME("supreme", 13, 1_300_000, 260, new int[]{255, 128, 106}),
    COSMIC("cosmic", 15, 1_500_000, 300, new int[]{75, 248, 255}),
    INFINITE("infinite", 17, 1_700_000, 340, new int[]{247, 135, 255}),
    BLAZE("blaze", 25, 2_500_000, 380, new int[]{255, 120, 40}), // 烈焰炽焱：烈焰橙；并行 5²；能耗延续 +40 规律
    CRYSTAL_MATRIX("crystal_matrix", 36, 3_600_000, 500, new int[]{75, 248, 255}), // 并行 6²；能耗 500 FE/t
    NEBULA("nebula", 49, 4_900_000, 0, new int[]{255, 170, 0}), // 星云塑造：并行 7²；免能耗（金色，与无尽升级组件物品名同色）
    SINGULARITY("singularity", 81, 8_100_000, 0, new int[]{255, 85, 85}); // 奇点创世：顶级 9²；免能耗

    public final String name;
    public final int processes;
    public final int energyCapacity;
    public final int energyPerTick;
    private final TextColor textColor;

    CuttingMachineFactoryTier(String name, int processes, int energyCapacity, int energyPerTick, int[] rgbCode) {
        this.name = name;
        this.processes = processes;
        this.energyCapacity = energyCapacity;
        this.energyPerTick = energyPerTick;
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