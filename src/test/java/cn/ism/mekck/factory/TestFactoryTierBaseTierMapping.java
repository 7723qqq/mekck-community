package cn.ism.mekck.factory;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 钉住「12 档接入 Mek {@code ITier}」的映射规则——规格 §8.3.2 的决策，§15.2 第 1 条要求项。
 *
 * <p>映射规则：前 4 档一一对应 {@code BaseTier}，其余 8 档一律归
 * {@code ULTIMATE}（用户 2026-09-29 决策：有 Mek 拓展就对应，没有就归终极工厂升级）。
 * {@code CREATIVE} 不映射任何档位——它对应 Mek 的创造模式物品，与工厂档位无关。</p>
 *
 * <p>本测试<b>不加载 {@code MekCkFactoryTier}</b>：它实现 {@code SupportsColorMap}，
 * 静态初始化链会拉进 {@code DyeColor} / {@code TextColor} / 注册表，裸 JVM 里必然失败
 * （同 {@code TestUpgradeIndexWraparoundArithmetic} 的处理）。这里复刻映射表本身，
 * 「该表确实接在枚举上」由编译期 {@code implements ITier} 保证，
 * 「枚举确实返回这张表」由实机确认。</p>
 */
public class TestFactoryTierBaseTierMapping {

    /** Mek 侧的基础档位，按 {@code javap} 实测只有 5 个常量。 */
    private enum MekBaseTier {
        BASIC, ADVANCED, ELITE, ULTIMATE, CREATIVE
    }

    private enum MekTier {
        BASIC, ADVANCED, ELITE, ULTIMATE, ABSOLUTE, SUPREME, COSMIC, INFINITE,
        BLAZE, CRYSTAL_MATRIX, NEBULA, SINGULARITY
    }

    /** 复刻 MekCkFactoryTier 的映射表。 */
    private static MekBaseTier mapped(MekTier t) {
        switch (t) {
            case BASIC: return MekBaseTier.BASIC;
            case ADVANCED: return MekBaseTier.ADVANCED;
            case ELITE: return MekBaseTier.ELITE;
            case ULTIMATE:
            case ABSOLUTE:
            case SUPREME:
            case COSMIC:
            case INFINITE:
            case BLAZE:
            case CRYSTAL_MATRIX:
            case NEBULA:
            case SINGULARITY:
                return MekBaseTier.ULTIMATE;
            default:
                throw new IllegalStateException("未覆盖的档位：" + t);
        }
    }

    @Test
    public void firstFourTiersMapOneToOne() {
        assertEquals(MekBaseTier.BASIC, mapped(MekTier.BASIC));
        assertEquals(MekBaseTier.ADVANCED, mapped(MekTier.ADVANCED));
        assertEquals(MekBaseTier.ELITE, mapped(MekTier.ELITE));
        assertEquals(MekBaseTier.ULTIMATE, mapped(MekTier.ULTIMATE));
    }

    @Test
    public void theOtherEightTiersAllFallBackToUltimate() {
        List<String> notUltimate = new ArrayList<>();
        for (MekTier t : new MekTier[]{MekTier.ABSOLUTE, MekTier.SUPREME, MekTier.COSMIC, MekTier.INFINITE,
                MekTier.BLAZE, MekTier.CRYSTAL_MATRIX, MekTier.NEBULA, MekTier.SINGULARITY}) {
            if (mapped(t) != MekBaseTier.ULTIMATE) notUltimate.add(t + " → " + mapped(t));
        }
        assertTrue("无 Mek 对应档的 8 档必须全归 ULTIMATE，实际：" + notUltimate, notUltimate.isEmpty());
    }

    /** 12 档全部有映射，不允许出现 null（null 会让调用方的 NPE 变成静默失败）。 */
    @Test
    public void everyTierHasAMapping() {
        for (MekTier t : MekTier.values()) {
            assertNotNull(t + " 必须有基础档位映射", mapped(t));
        }
        assertEquals("共 12 档", 12, MekTier.values().length);
    }

    /** CREATIVE 保留给 Mek 的创造模式物品，不得被任何工厂档位占用。 */
    @Test
    public void creativeIsNeverUsed() {
        for (MekTier t : MekTier.values()) {
            assertTrue(t + " 不得映射到 CREATIVE", mapped(t) != MekBaseTier.CREATIVE);
        }
    }

    /** 映射不得"降级"——低于 ELITE 的档位不能被映到 ULTIMATE，反之亦然。 */
    @Test
    public void mappingNeverInvertsTheTierOrder() {
        MekBaseTier prev = null;
        for (MekTier t : MekTier.values()) {
            MekBaseTier cur = mapped(t);
            if (prev != null) {
                assertTrue(t + " 的映射 " + cur + " 不应低于前一档 " + prev,
                        cur.ordinal() >= prev.ordinal());
            }
            prev = cur;
        }
    }
}
