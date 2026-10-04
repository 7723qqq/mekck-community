package cn.ism.mekck.event;

import cn.ism.mekck.effect.EternalFreezeEffect;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 「冷冻类机器关 AI 的恢复链」护栏（第 6 轮审查 Critical 修复）。
 *
 * <h3>它挡的是哪一类事故</h3>
 * 三处关 AI（冰块 / 费列罗 / 永冻）把生物置 {@code NoAI=true} 并写档后，
 * 「恢复」若挂错了事件粒度、或写入与比较用了不同的时间基准，就会留下
 * <b>永久无 AI</b> 的生物。这类缺陷编译通过、单机看不出来，只在重启/重载时发作，
 * 所以这里用两层断言钉住：
 * <ol>
 *   <li><b>纯逻辑</b>：{@link FreezeAiReaper#shouldReap}（{@link FreezeAiReaper#reapIfOrphaned}
 *       的判据，抽成包级静态以便裸 JVM 测试）的三态与两键独立性 —— 不碰真 {@code Mob}；
 *       其中「无键 → 不回收」是反误伤不变量；</li>
 *   <li><b>源码形态</b>：reaper 必须挂 {@code EntityJoinLevelEvent}（而非每维度一次的
 *       {@code LevelEvent} 维度加载子事件）；写入/比较一律用持久基准 {@code getGameTime}
 *       （不得用每会话归零的 {@code getTickCount} 计算恢复刻）。</li>
 * </ol>
 *
 * <h3>变异点（改哪一行应让本护栏变红）</h3>
 * <ol>
 *   <li>{@code FreezeAiReaper} 的事件参数换回 {@code LevelEvent.Load} → {@code reaperNoLonger...} 红；</li>
 *   <li>{@code IceCubeEntity}/{@code FerreroEntity}/{@code EternalFreezeEffect} 里把
 *       {@code long restoreTick = serverLevel.getGameTime() + ...} 改回
 *       {@code serverLevel.getServer().getTickCount() + ...} → {@code restoreTickUsesPersistentBaseline} 红；</li>
 *   <li>{@code FreezeAiReaper} 兜底键集合删掉 {@code EternalFreezeEffect.AI_RESTORE_KEY}
 *       → {@code reaperCoversAllThreeSources} 红；</li>
 *   <li>{@link FreezeAiReaper#shouldReap} 删掉「{@code !anyKeyPresent → false}」前置判断
 *       → {@code noKeyIsNeverReaped} 红（反误伤不变量被破坏，会解锁其它模组的 NoAI 生物）；</li>
 *   <li>{@code IceCubeEntity} 的 {@code IceSplash} 落盘写/读任一被删 → {@code iceSplashIsPersisted} 红。</li>
 * </ol>
 */
public class TestFreezeAiRecovery {

    private static final Path SRC = Path.of("src", "main", "java", "cn", "ism", "mekck");
    private static final Path REAPER = SRC.resolve("event").resolve("FreezeAiReaper.java");
    private static final Path ICE_CUBE = SRC.resolve("entity").resolve("IceCubeEntity.java");
    private static final Path FERRERO = SRC.resolve("entity").resolve("FerreroEntity.java");
    private static final Path EFFECT = SRC.resolve("effect").resolve("EternalFreezeEffect.java");

    /**
     * 恢复刻若用会话基准计算，重启后会与兜底回收器的持久基准比较对不上。
     * 只匹配「{@code long <变量> = ... getTickCount(}」——会话计数只允许出现在
     * 用于 TickTask 调度的 {@code int} 变量里。
     */
    private static final Pattern SESSION_BASED_LONG_TICK =
            Pattern.compile("long\\s+\\w+\\s*=\\s*[^;\\n]*getTickCount\\s*\\(");

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    // ── ① 判据纯逻辑：无键 / 带键未到 / 带键已到 三态 ─────────────────────────

    /**
     * 反误伤不变量：<b>无三键者一律不回收</b>（{@code anyKeyPresent == false} ⇒ 恒 false）。
     *
     * <p>{@code EntityJoinLevelEvent} 覆盖全部区块载入路径，若「无键的 NoAI」也被清 AI，
     * 会把其它模组/刷怪蛋/命令造出的 NoAI 生物（NPC、雕像、假人）误伤解冻。</p>
     */
    @Test
    public void noKeyIsNeverReaped() {
        assertFalse("无键必须不回收", FreezeAiReaper.shouldReap(1000L, false, 0L, 0L, 0L));
        assertFalse("就算传了已到期的数值，无键也不回收",
                FreezeAiReaper.shouldReap(1000L, false, 900L, 900L, 900L));
    }

    /** 带键且恢复刻仍在未来：有来源认领，不回收。 */
    @Test
    public void futureDueWithKeyIsClaimed() {
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 1040L, 0L, 0L));
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 0L, 1040L, 0L));
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 0L, 0L, 1040L));
    }

    /** 带键且恢复刻已到（含恰好等于当前刻）：回收。 */
    @Test
    public void pastDueWithKeyIsReaped() {
        assertTrue("恰好到期", FreezeAiReaper.shouldReap(1000L, true, 1000L, 0L, 0L));
        assertTrue("已过期", FreezeAiReaper.shouldReap(1000L, true, 900L, 950L, 999L));
    }

    /**
     * 两键（乃至三键）各自独立：一把到期、另一把未到 → 仍有来源认领，不回收。
     *
     * <p>这正是「互不覆盖」的核心 —— 短窗口来源的键过期不得让长窗口来源的冻结被提前解除。</p>
     */
    @Test
    public void keysAreIndependent() {
        // 冰块已到期、费列罗未到
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 900L, 1040L, 0L));
        // 费列罗已到期、冰块未到
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 1040L, 900L, 0L));
        // 永冻未到，另两把都过期
        assertFalse(FreezeAiReaper.shouldReap(1000L, true, 900L, 950L, 1040L));
        // 三把全过期 → 回收
        assertTrue(FreezeAiReaper.shouldReap(1000L, true, 900L, 950L, 990L));
    }

    /** 三来源的键必须互不相同（共键会让「谁后命中谁说了算」）。 */
    @Test
    public void restoreKeysAreDistinct() {
        assertNotEquals(FreezeAiReaper.ICE_CUBE_AI_RESTORE_KEY, FreezeAiReaper.FERRERO_AI_RESTORE_KEY);
        assertNotEquals(FreezeAiReaper.ICE_CUBE_AI_RESTORE_KEY, EternalFreezeEffect.AI_RESTORE_KEY);
        assertNotEquals(FreezeAiReaper.FERRERO_AI_RESTORE_KEY, EternalFreezeEffect.AI_RESTORE_KEY);
    }

    // ── ② 源码形态：reaper 的事件粒度 ─────────────────────────────────────────────

    /**
     * 兜底必须挂「真正按实体触发」的 {@code EntityJoinLevelEvent}，且不得再挂
     * {@code LevelEvent.Load}（每维度一次、触发时实体未载入、全程空转）。
     */
    @Test
    public void reaperSubscribesToEntityJoinNotLevelLoad() throws IOException {
        String src = read(REAPER);
        assertFalse("兜底不得再挂每维度一次的 LevelEvent 维度加载子事件",
                src.contains("LevelEvent.Load"));
        assertFalse("不得 import LevelEvent（订阅了才会用到）",
                src.contains("import net.minecraftforge.event.level.LevelEvent"));
        assertTrue("兜底必须挂 EntityJoinLevelEvent（区块/重启时实体从 NBT 载入也走它）",
                src.contains("EntityJoinLevelEvent"));
        assertTrue("只回收「从存档重新载入」的实体，闸门用 loadedFromDisk()",
                src.contains("event.loadedFromDisk()"));
    }

    /** reaper 只读持久基准，不得出现会话基准（它没有 TickTask 调度职责）。 */
    @Test
    public void reaperOnlyUsesPersistentBaseline() throws IOException {
        String src = read(REAPER);
        assertTrue("reaper 必须用持久基准 getGameTime 比较", src.contains("getGameTime()"));
        assertFalse("reaper 不得用会话基准 getTickCount", src.contains("getTickCount"));
    }

    /** 兜底键集合必须覆盖三来源（含永冻键）。 */
    @Test
    public void reaperCoversAllThreeSources() throws IOException {
        String src = read(REAPER);
        assertTrue("兜底须判断冰块键", src.contains("ICE_CUBE_AI_RESTORE_KEY"));
        assertTrue("兜底须判断费列罗键", src.contains("FERRERO_AI_RESTORE_KEY"));
        assertTrue("兜底须判断永冻键", src.contains("EternalFreezeEffect.AI_RESTORE_KEY"));
    }

    // ── ③ 源码形态：写入 / 比较的时间基准同源 ────────────────────────────────────

    /**
     * 三个写入侧的恢复刻必须由持久基准 {@code getGameTime} 计算；
     * 不得出现「{@code long ... = ... getTickCount(}」（会话内归零，重启后对不上）。
     */
    @Test
    public void restoreTickUsesPersistentBaseline() throws IOException {
        for (Path path : List.of(ICE_CUBE, FERRERO, EFFECT)) {
            String src = read(path);
            assertTrue(path.getFileName() + " 必须存在以持久基准 getGameTime 计算的恢复刻",
                    src.contains("getGameTime()"));
            Matcher m = SESSION_BASED_LONG_TICK.matcher(src);
            boolean sessionBased = m.find();
            assertFalse(path.getFileName() + " 的恢复刻不得用会话基准 getTickCount 计算（重启归零），实到："
                    + (sessionBased ? m.group() : "<无>"), sessionBased);
        }
    }

    /** 键必须来自共享常量，不得再出现裸字面量（防两处拼写漂移）。 */
    @Test
    public void restoreKeysComeFromConstants() throws IOException {
        assertFalse("IceCubeEntity 不得裸写恢复键字面量",
                read(ICE_CUBE).contains("\"mekck:ai_restore_tick\""));
        assertTrue("IceCubeEntity 须引用 FreezeAiReaper.ICE_CUBE_AI_RESTORE_KEY",
                read(ICE_CUBE).contains("FreezeAiReaper.ICE_CUBE_AI_RESTORE_KEY"));
        assertFalse("FerreroEntity 不得裸写恢复键字面量",
                read(FERRERO).contains("\"mekck:ferrero_ai_restore_tick\""));
    }

    // ── 顺带：IceCube 溅射伤害落盘 ────────────────────────────────────────────────

    @Test
    public void iceSplashIsPersisted() throws IOException {
        String src = read(ICE_CUBE);
        assertTrue("溅射伤害须写入存档（IceSplash）", src.contains("putFloat(\"IceSplash\""));
        assertTrue("溅射伤害须从存档读回（IceSplash）", src.contains("\"IceSplash\""));
        assertTrue("读取须判键存在，避免旧档把默认 5.0 覆盖成 0",
                src.contains("contains(\"IceSplash\""));
    }
}
