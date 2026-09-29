package cn.ism.mekck.factory;

import mekanism.api.SupportsColorMap;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.StringRepresentable;

/**
 * MekCK 工厂等级（12 档）——走 Mekanism 附属的 tier 扩展范式。
 *
 * <p>Mek 原生 {@code FactoryTier} 只有 4 档（BASIC/ADVANCED/ELITE/ULTIMATE），
 * 但 Mek 的 tier 体系本身<b>不限制附属扩充</b>：Mekanism Extras 就自建了
 * {@code ExtraFactoryTier}（ABSOLUTE~INFINITE），方式是<b>独立枚举 + 实现 tier 接口</b>，
 * 而非继承 Mek 的 tier。</p>
 *
 * <p>本类照同一范式实现：自建 12 档枚举，实现 Mek 的 {@link SupportsColorMap}
 * （提供颜色映射，供 Mek 的 GUI/文本染色使用）。工厂 tile 通过
 * {@link #processes} 决定并行槽数。</p>
 *
 * <p><b>并行数不是线性的</b>：BASIC~INFINITE 是 3/5/7/9/11/13/15/17，
 * 之后 BLAZE~SINGULARITY 跳到 25/36/49/81（平方序列）。因此不能照抄 Mek
 * 按 {@code tier.ordinal()} 查表的逻辑，必须用本枚举的字段直取。</p>
 *
 * <p><b>免能耗档</b>：NEBULA / SINGULARITY 的 {@link #energyPerTick} 为 0。</p>
 */
public enum MekCkFactoryTier implements StringRepresentable, SupportsColorMap {
    // 前 8 档：RGB 取自 Mek BaseTier 的 5 个常量（BASIC~ULTIMATE）；
    // ABSOLUTE~INFINITE 4 档在 Mek 1.20.1 中无对应类型（不存在 AdvancedTier 类），
    // 其颜色为自行设定。档位接入 Mek 的方案见规格 §8.3。
    BASIC("basic", 3, 300_000, 60, new int[]{95, 255, 184}),
    ADVANCED("advanced", 5, 500_000, 100, new int[]{255, 128, 106}),
    ELITE("elite", 7, 700_000, 140, new int[]{75, 248, 255}),
    ULTIMATE("ultimate", 9, 900_000, 180, new int[]{247, 135, 255}),
    ABSOLUTE("absolute", 11, 1_100_000, 220, new int[]{95, 255, 184}),
    SUPREME("supreme", 13, 1_300_000, 260, new int[]{255, 128, 106}),
    COSMIC("cosmic", 15, 1_500_000, 300, new int[]{75, 248, 255}),
    INFINITE("infinite", 17, 1_700_000, 340, new int[]{247, 135, 255}),
    // 后 4 档：MekCK 独有（并行数转为平方序列）
    BLAZE("blaze", 25, 2_500_000, 380, new int[]{255, 120, 40}),
    CRYSTAL_MATRIX("crystal_matrix", 36, 3_600_000, 500, new int[]{75, 248, 255}),
    NEBULA("nebula", 49, 4_900_000, 0, new int[]{255, 170, 0}),
    SINGULARITY("singularity", 81, 8_100_000, 0, new int[]{255, 85, 85});

    /** 并行槽数（输入槽 = 输出槽 = 本值）。 */
    public final int processes;
    /** 能量容量（FE）。 */
    public final int energyCapacity;
    /** 空闲能耗（FE/t）；0 表示免能耗档。 */
    public final int energyPerTick;

    private final String name;
    private final TextColor textColor;
    private final int[] rgbCode;

    MekCkFactoryTier(String name, int processes, int energyCapacity, int energyPerTick, int[] rgbCode) {
        this.name = name;
        this.processes = processes;
        this.energyCapacity = energyCapacity;
        this.energyPerTick = energyPerTick;
        this.rgbCode = rgbCode;
        this.textColor = TextColor.fromRgb(rgbCode[0] << 16 | rgbCode[1] << 8 | rgbCode[2]);
    }

    // ── Mek SupportsColorMap ───────────────────────────────────────────────

    @Override
    public int[] getRgbCode() {
        return rgbCode;
    }

    /**
     * Mek 的颜色图集回填钩子：换资源包时 Mek 会用图集里的实际颜色覆写。
     * 本模组的等级色是自定义的，不接受覆写（保持声明值），故为空实现。
     */
    @Override
    public void setColorFromAtlas(int[] atlasRgb) {
        // 故意不覆写等级色：本模组 12 档颜色自成体系，不随 Mek 色图集变化。
    }

    /** 供 GUI/文本直接取色（{@code getTextColor} 不属于 SupportsColorMap，是本枚举的便捷方法）。 */
    public TextColor getTextColor() {
        return textColor;
    }

    // ── StringRepresentable ────────────────────────────────────────────────

    @Override
    public String getSerializedName() {
        return name;
    }

    public String getLowerName() {
        return name;
    }

    /** 本档是否免能耗。 */
    public boolean isEnergyFree() {
        return energyPerTick == 0;
    }

    /**
     * 本等级是否拥有堆叠升级槽（ABSOLUTE ~ NEBULA）。
     *
     * <p>SINGULARITY 明确不支持：其设计前提是「基础并行即为极限并行」，
     * 再叠倍率没有意义。</p>
     */
    public boolean supportsStackUpgrade() {
        return ordinal() >= ABSOLUTE.ordinal() && this != SINGULARITY;
    }

    private static final MekCkFactoryTier[] TIERS = values();

    /** 越界回落 BASIC，避免非法的等级序号导致崩溃。 */
    public static MekCkFactoryTier byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < TIERS.length ? TIERS[ordinal] : BASIC;
    }
}
