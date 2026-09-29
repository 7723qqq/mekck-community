package cn.ism.mekck.blockentity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * TavernBrewBatch 真实 Java 类单元测试（普通 JVM + MC 数据结构类）。
 * 覆盖：创建/非法创建、等级与阶段时间推进、满级停止、可分装条件、消耗至 0、
 * 最后一瓶快照、重复消耗拒绝、NBT 保存→加载一致、旧存档无字段、损坏 NBT 停滞、
 * 配方缺失保留批次、重复加载不增加瓶数、**整批总长 = 配方 unit_time 与进度柱读数**。
 *
 * <p><b>2026-09-25 口径变更</b>（用户拍板，本文件同步改）：
 * ① 可分装门槛由 {@code level >= 1} 改为**满级（6 典藏）** ⇒ 凡涉及 consumeOne/canDispense 的用例
 *    须先 {@link #ageToMax} 陈到满级；
 * ② 阶段时长不再是「配方 unit_time × level」（那会使整批长 15 倍），而是
 *    {@code (unit_time / 15) × level} ⇒ 整批总长恰为 unit_time，保留 1:2:3:4:5 曲线。</p>
 */
public class TestTavernBrewBatch {

    private static ResourceLocation rid(String s) { return ResourceLocation.tryParse(s); }

    private TavernBrewBatch newBatch() { return new TavernBrewBatch(); }

    /** 把批次陈到满级（新口径下 consumeOne / canDispense 的前置）。 */
    private void ageToMax(TavernBrewBatch b) {
        int guard = 0;
        while (!b.isMaxBrewLevel() && guard++ < 40000) b.tickBatch();
        assertTrue("ageToMax 应在有限 tick 内到达满级，实际 " + guard, b.isMaxBrewLevel());
    }

    @Test
    public void idleInitialState() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.isIdle());
        assertEquals(0, b.getRemainingBottles());
        assertEquals(TavernBrewBatch.BREWING_NOT_STARTED, b.getBrewLevel());
    }

    @Test
    public void invalidCreateRejected() {
        TavernBrewBatch b = newBatch();
        assertFalse(b.createBatch(rid("t:wine"), rid("t:res"), 0, 100, "{}"));
        assertFalse(b.createBatch(rid("t:wine"), rid("t:res"), 17, 100, "{}"));
        assertFalse(b.createBatch(null, rid("t:res"), 5, 100, "{}"));
        assertFalse(b.createBatch(rid("t:wine"), rid("t:res"), 5, 0, "{}"));
        assertFalse(b.createBatch(rid("t:wine"), rid("t:res"), 5, 100, null));
        assertTrue(b.isIdle());
    }

    @Test
    public void validCreate() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 8, 100, "{\"item\":\"tavern:empty_bottle\"}"));
        assertTrue(b.isBrewing());
        assertEquals(8, b.getRemainingBottles());
        assertEquals(8, b.getTotalBottles());
        assertEquals(TavernBrewBatch.BREWING_STARTED, b.getBrewLevel());
        assertFalse("未陈到满级不得分装（2026-09-25 拍板，否则出货清一色 1 级「难以下咽」）",
                b.canDispense());
    }

    @Test
    public void levelProgressionToMax() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 100, "{}"));
        // 阶段时长 = (unit/15) × level = 6 × level：6+12+18+24+30 = 90 tick 到满级（整批总长 = 100/15 的整数商 × 15）
        int ticks = 0;
        while (!b.isMaxBrewLevel() && ticks < 2000) {
            b.tickBatch();
            ticks++;
        }
        assertTrue("should reach max within 2000 ticks, used " + ticks, b.isMaxBrewLevel());
        assertEquals(TavernBrewBatch.BREWING_FINISHED, b.getBrewLevel());
        // 满级后不再升级
        b.tickBatch();
        assertEquals(TavernBrewBatch.BREWING_FINISHED, b.getBrewLevel());
    }

    @Test
    public void levelZeroCannotDispense() {
        TavernBrewBatch b = newBatch();
        assertFalse(b.canDispense()); // IDLE/level 0 不可分装
    }

    @Test
    public void consumeToZeroThenReject() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 2, 100, "{}"));
        ageToMax(b); // 新口径：满级才可分装
        assertTrue(b.consumeOne());
        assertEquals(1, b.getRemainingBottles());
        assertTrue(b.consumeOne());
        assertTrue(b.isIdle()); // 最后一瓶消耗后清空
        assertFalse(b.consumeOne()); // 空批次拒绝
        assertEquals(0, b.getRemainingBottles());
    }

    @Test
    public void lastBottleSnapshotAvailableBeforeConsume() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 1, 100, "{\"item\":\"tavern:empty_bottle\"}"));
        ageToMax(b); // 新口径：满级才可分装
        // 分装事务安全调用方式：先取快照，再 consumeOne
        ResourceLocation result = b.getResultItemId();
        String carrier = b.getCarrierJson();
        int level = b.getBrewLevel();
        assertNotNull(result);
        assertNotNull(carrier);
        assertEquals(TavernBrewBatch.BREWING_FINISHED, level);
        assertTrue(b.consumeOne());
        assertTrue(b.isIdle());
        // 快照仍可用（最后一瓶生成所需数据已先取得）
        assertEquals(rid("t:res"), result);
    }

    @Test
    public void nbtRoundTrip() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 5, 100, "{}"));
        for (int i = 0; i < 3; i++) b.tickBatch(); // 推进一点
        CompoundTag tag = new CompoundTag();
        b.save(tag);
        TavernBrewBatch b2 = newBatch();
        b2.load(tag);
        assertTrue(b2.isBrewing());
        assertEquals(b.getRecipeId(), b2.getRecipeId());
        assertEquals(b.getResultItemId(), b2.getResultItemId());
        assertEquals(b.getRemainingBottles(), b2.getRemainingBottles());
        assertEquals(b.getTotalBottles(), b2.getTotalBottles());
        assertEquals(b.getBrewLevel(), b2.getBrewLevel());
        assertEquals(b.getBrewTimeRemaining(), b2.getBrewTimeRemaining());
        assertEquals(b.getCarrierJson(), b2.getCarrierJson());
    }

    @Test
    public void oldSaveNoFieldIsIdle() {
        CompoundTag tag = new CompoundTag(); // 无批次字段
        TavernBrewBatch b = newBatch();
        b.load(tag);
        assertTrue(b.isIdle());
        assertFalse(b.isStalled());
    }

    @Test
    public void corruptNbtStallsAndPreservesData() {
        // 字段存在但缺 recipeId（可能已投入材料）→ STALLED，不清空
        CompoundTag tag = new CompoundTag();
        tag.putByte("State", (byte) TavernBrewBatch.State.BREWING.ordinal());
        tag.putString("ResultId", "t:res");
        tag.putInt("TotalBottles", 5);
        tag.putInt("RemainingBottles", 5);
        tag.putInt("BrewLevel", 3);
        tag.putInt("BrewTimeRemaining", 100);
        tag.putInt("UnitTime", 100);
        TavernBrewBatch b = newBatch();
        b.load(tag);
        assertTrue("corrupt data should stall, not silently clear", b.isStalled());
        assertFalse(b.canDispense());
        // save 原样保留原始数据
        CompoundTag out = new CompoundTag();
        b.save(out);
        assertTrue(out.contains("ResultId"));
        assertEquals("t:res", out.getString("ResultId"));
    }

    @Test
    public void negativeTotalStalls() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("State", (byte) TavernBrewBatch.State.BREWING.ordinal());
        tag.putString("RecipeId", "t:wine");
        tag.putString("ResultId", "t:res");
        tag.putInt("TotalBottles", -3);
        tag.putInt("RemainingBottles", 5);
        tag.putInt("BrewLevel", 1);
        tag.putInt("BrewTimeRemaining", 100);
        tag.putInt("UnitTime", 100);
        TavernBrewBatch b = newBatch();
        b.load(tag);
        assertTrue(b.isStalled());
    }

    @Test
    public void recipeMissingKeepsBatch() {
        // 配方（数据包）消失：批次保留（recipeId + unitTime 副本可推进），不清空
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:gone_recipe"), rid("t:res"), 4, 100, "{}"));
        assertTrue(b.isBrewing());
        assertEquals(rid("t:gone_recipe"), b.getRecipeId());
        b.tickBatch();
        assertEquals(4, b.getRemainingBottles()); // 不产酒不扣料
    }

    @Test
    public void createRejectsActiveAndStalled() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 3, 100, "{}"));
        // 已 BREWING：拒绝新批次（不依赖外层检查）
        assertFalse(b.createBatch(rid("t:wine2"), rid("t:res2"), 3, 100, "{}"));
        assertEquals(rid("t:wine"), b.getRecipeId());
        // STALLED：拒绝新批次
        TavernBrewBatch s = newBatch();
        CompoundTag bad = new CompoundTag();
        bad.putByte("State", (byte) TavernBrewBatch.State.STALLED.ordinal());
        bad.putString("ResultId", "t:res");
        s.load(bad);
        assertTrue(s.isStalled());
        assertFalse(s.createBatch(rid("t:wine3"), rid("t:res3"), 3, 100, "{}"));
        assertTrue(s.isStalled());
    }

    @Test
    public void repeatedLoadDoesNotDuplicate() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 3, 100, "{}"));
        CompoundTag tag = new CompoundTag();
        b.save(tag);
        TavernBrewBatch b2 = newBatch();
        b2.load(tag);
        b2.load(tag); // 重复 load
        assertEquals(3, b2.getRemainingBottles());
        assertEquals(3, b2.getTotalBottles());
    }

    // ── 2026-09-25 新口径：整批总长与进度柱读数 ──

    @Test
    public void idleReportsZeroTimesSoClientSkipsDivision() {
        // 非酿造中总长必须为 0：menu.getProgress() 对 maximum==0 特判返回 0，避免除零
        TavernBrewBatch b = newBatch();
        assertEquals(0, b.getBrewTotalTicks());
        assertEquals(0, b.getBrewElapsedTicks());
    }

    @Test
    public void wholeBatchLastsExactlyRecipeUnitTime() {
        // 酒馆 24 条 barrel 配方的 unit_time 实测全为 2400 ⇒ 整批恰 2400 tick（= 陈酿机酿一瓶葡园酒香）
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        assertEquals(2400, b.getBrewTotalTicks());
        assertEquals(0, b.getBrewElapsedTicks());
        int ticks = 0;
        while (!b.isMaxBrewLevel() && ticks < 3000) {
            b.tickBatch();
            ticks++;
        }
        assertEquals("满级应恰好发生在第 unit_time 个 tick", 2400, ticks);
        assertEquals(2400, b.getBrewElapsedTicks());
    }

    @Test
    public void elapsedIsMonotonicAndCappedAtTotal() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        int prev = -1;
        for (int i = 0; i < 2600; i++) {
            int e = b.getBrewElapsedTicks();
            assertTrue("进度不得回退：" + e, e >= prev);
            assertTrue("进度不得超过总长：" + e, e <= b.getBrewTotalTicks());
            prev = e;
            b.tickBatch();
        }
        assertEquals(2400, prev);
        b.tickBatch(); // 满级后继续 tick 读数不变（柱子保持满格直到批次抽干）
        assertEquals(2400, b.getBrewElapsedTicks());
    }

    @Test
    public void stageWeightKeepsOriginalCurve() {
        // 原版「等级越高越慢」：前两阶段共 1+2=3 单位 = 480 tick，而非均分的 2/5 总长
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        for (int i = 0; i < 479; i++) b.tickBatch();
        assertEquals("479 tick 时仍应为 level 2", 2, b.getBrewLevel());
        b.tickBatch();
        assertEquals("第 480 tick 升入 level 3", 3, b.getBrewLevel());
        assertEquals(480, b.getBrewElapsedTicks());
    }

    // ── 2026-09-25 追加：酒馆模式吃速度升级（陈化预算按倍率每游戏 tick 折算消耗） ──

    private int ticksToMax(TavernBrewBatch b, double speedMult) {
        int gameTicks = 0;
        while (!b.isMaxBrewLevel() && gameTicks < 6000) { b.tickBatch(speedMult); gameTicks++; }
        return gameTicks;
    }

    @Test
    public void oneTimesEqualsLegacyNoArg() {
        // 1.0× 与无参逐 tick 完全等价：整批 2400 游戏 tick
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        assertEquals(2400, ticksToMax(b, 1.0));
    }

    @Test
    public void twoTimesHalvesWholeBatch() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        assertEquals("2× ⇒ 整批 1200 游戏 tick", 1200, ticksToMax(b, 2.0));
    }

    @Test
    public void tenTimesScalesWholeBatch() {
        // 8 个速度升级 = 10× ⇒ 2400 ÷ 10 = 240 游戏 tick；进度柱分母口径仍是 2400 预算 tick
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        assertEquals(240, ticksToMax(b, 10.0));
        assertEquals(2400, b.getBrewTotalTicks());
        assertEquals(2400, b.getBrewElapsedTicks());
    }

    @Test
    public void fractionalSpeedUsesAccumulator() {
        // 1.5×：小数余量进累加器攒满再扣 ⇒ 总耗时 = 2400 ÷ 1.5 = 1600 游戏 tick
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 4, 2400, "{}"));
        assertEquals(1600, ticksToMax(b, 1.5));
    }

    // ── 2026-09-25 追加：完工扣料（陈化期不扣料，满级才扣；失配归零由 BE 承担，此处锁组件口径） ──

    /**
     * 旧签名（起批即扣口径）⇒ consumed 恒 true：满级即可分装（回归防线：本文件其余用例全部走旧签名）。
     */
    @Test
    public void legacyCreateIsImmediatelyConsumed() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 2, 100, "{}"));
        assertTrue("旧签名批次应恒已扣料", b.isConsumed());
        ageToMax(b);
        assertTrue(b.canDispense());
    }

    /**
     * 完工扣料口径（10 参重载，deferConsume=true）：满级前后至多一次 markConsumed 前，canDispense 恒 false；
     * markConsumed 后才可分装。快照表用空表（ItemStack 类在普通 JVM 无法初始化，见 TestTavernBarrelPlan 同注）；
     * 槽位内容比对逻辑由实机验收覆盖，此处锁 consumed/流体/NBT 结构口径。
     */
    @Test
    public void deferredBatchNeedsConsumedBeforeDispense() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 2, 100, "{}",
                java.util.List.of(), java.util.List.of(),
                "minecraft:water", 4000, true));
        assertFalse(b.isConsumed());
        assertEquals(0, b.getHoldSlots().size());
        assertEquals(0, b.getHoldSnapshots().size());
        assertEquals("minecraft:water", b.getHoldFluidName());
        assertEquals(4000, b.getHoldFluidAmount());
        ageToMax(b);
        assertFalse("满级但未扣料 ⇒ 不可分装", b.canDispense());
        b.markConsumed();
        assertTrue("完工扣料后方可分装", b.canDispense());
        assertTrue(b.consumeOne());
    }

    /** 新签名对持守快照结构的防御：两表不同长拒绝建批且不改状态。 */
    @Test
    public void deferredCreateRejectsMismatchedHoldLists() {
        TavernBrewBatch b = newBatch();
        assertFalse(b.createBatch(rid("t:wine"), rid("t:res"), 2, 100, "{}",
                java.util.List.of(0), java.util.List.of(),
                "", 0, true));
        assertTrue(b.isIdle());
    }

    /** 完工扣料字段随 NBT 往返回来（未扣态 + 流体要求）；旧存档无 Consumed 键 ⇒ 已扣（零回归）。 */
    @Test
    public void deferredStateSurvivesNbtRoundTrip() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 3, 100, "{}",
                java.util.List.of(), java.util.List.of(),
                "minecraft:lava", 2000, true));
        b.tickBatch();
        CompoundTag tag = new CompoundTag();
        b.save(tag);
        TavernBrewBatch b2 = newBatch();
        b2.load(tag);
        assertTrue(b2.isBrewing());
        assertFalse("未扣态须随存档保留", b2.isConsumed());
        assertEquals(0, b2.getHoldSlots().size());
        assertEquals(0, b2.getHoldSnapshots().size());
        assertEquals("minecraft:lava", b2.getHoldFluidName());
        assertEquals(2000, b2.getHoldFluidAmount());
        // 旧存档（有完整批次字段、无 Consumed 键）⇒ 恒已扣，维持旧行为
        tag.remove("Consumed");
        TavernBrewBatch b3 = newBatch();
        b3.load(tag);
        assertTrue(b3.isBrewing());
        assertTrue("旧存档默认已扣料（零回归）", b3.isConsumed());
    }

    /** 声称未扣料却无持守快照（数据损坏）⇒ 停滞保留，不凭空扣/退任何材料。 */
    @Test
    public void missingHoldSnapshotsStall() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 3, 100, "{}",
                java.util.List.of(), java.util.List.of(),
                "", 0, true));
        CompoundTag tag = new CompoundTag();
        b.save(tag);
        tag.remove("HoldSlots");
        tag.remove("HoldSnap");
        TavernBrewBatch b2 = newBatch();
        b2.load(tag);
        assertTrue("未扣态但快照缺失应停滞保留", b2.isStalled());
        assertFalse(b2.canDispense());
    }

    /** markConsumed 后批次抽干 ⇒ reset 彻底清空持守字段（不泄漏到下一批）。 */
    @Test
    public void resetClearsHoldFields() {
        TavernBrewBatch b = newBatch();
        assertTrue(b.createBatch(rid("t:wine"), rid("t:res"), 1, 100, "{}",
                java.util.List.of(), java.util.List.of(),
                "minecraft:water", 4000, true));
        ageToMax(b);
        b.markConsumed();
        assertTrue(b.consumeOne()); // 抽干 → 内部 reset
        assertTrue(b.isIdle());
        assertTrue("抽干后 consumed 回默认 true", b.isConsumed());
        assertEquals(0, b.getHoldSlots().size());
        assertEquals(0, b.getHoldFluidAmount());
    }
}
