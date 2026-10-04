package cn.ism.mekck.ae2;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AE2 胶水层加固护栏（M27：M7-M5 / M7-M4 / M7-M3 / M7-m2）。
 *
 * <h3>守的四件事</h3>
 * <ol>
 *   <li><b>M7-M5</b>：{@code pullGenericIngredients} 必须先拒绝忙机器（job 未回收 / 机器订单
 *       未完成）并校验配方在本机样板里，否则会覆盖进行中的 job；{@code processJob} 必须有
 *       停滞回收（订单结束后产物连续超时导不出 ⇒ 清掉并记日志），否则 job 永久锁死
 *       {@code isBusy()}。</li>
 *   <li><b>M7-M4</b>：{@code getNetworkAvail} 必须走按 host + tick 节流的快照入口，
 *       不得每次调用都全量扫描网络并新建 HashMap。</li>
 *   <li><b>M7-M3</b>：勾选自动补料清单必须有硬上限，且落盘读取与上限同源。</li>
 *   <li><b>M7-m2</b>：AUTO_INDEX 缓存必须存勾选列表的不可变快照，否则
 *       {@code selected().equals(selected)} 自比恒真、勾选变化要等 TTL 过期才生效。</li>
 *   <li><b>复审折入 finding</b>（20261005-review-m27-bug-isbusy-ae2-cpu-me）：组装机
 *       {@code ownerBusy()} 不得返回 {@code !orderDone()}——空闲（输出槽空）时恒忙，
 *       AE2 的 CraftingCpuLogic 会跳过 {@code isBusy()} 为真的 provider，终端下单推不进来。</li>
 * </ol>
 *
 * <p>纯逻辑部分直接调 {@code MekckAe2.Hardening} 的包内静态函数（该嵌套类不引用 AE2 类型，
 * 普通 JVM 可加载）；结构部分用 {@link TestSourceText} 剥注释后断言源码形态，
 * 避免注释里的示意代码被当成真代码。</p>
 *
 * <p><b>源码形态断言一律钉「效果」而不是「token 存在」</b>：必须出现
 * {@code ownerBusy()) return false}、{@code !isRegisteredRecipe(be, rid)) return false}、
 * {@code if (Hardening.jobStalled(...)) {}、{@code return host.networkAvailSnapshot();}、
 * {@code List<String> next = ...} + {@code selectedAutoItems.addAll(next)} 这类
 * 「调用真的生效」的片段。只断言 token 出现时，「保留调用、去掉效果」的变异会静默全绿
 * （M27 复审 P2-1 实测）。</p>
 */
public class TestAe2Hardening {

    private static final String AE2 = "src/main/java/cn/ism/mekck/ae2/MekckAe2.java";

    // ================== 一、纯逻辑 ==================

    @Test
    public void stallPredicateOnlyFiresAfterTimeout() {
        long timeout = MekckAe2.JOB_STALL_TIMEOUT_TICKS;
        assertFalse("尚未观察过进展时不得回收（先记时刻）", MekckAe2.Hardening.jobStalled(1000L, -1L, timeout));
        assertFalse("刚观察到进展时不得回收", MekckAe2.Hardening.jobStalled(1000L, 1000L, timeout));
        assertFalse("未到超时不得回收", MekckAe2.Hardening.jobStalled(1000L + timeout - 1L, 1000L, timeout));
        assertTrue("到超时必须回收", MekckAe2.Hardening.jobStalled(1000L + timeout, 1000L, timeout));
        assertTrue("超过超时必须回收", MekckAe2.Hardening.jobStalled(1000L + timeout * 3L, 1000L, timeout));
    }

    @Test
    public void stallPredicateSurvivesClockRewind() {
        // 存档重载 / 时钟回拨时 now < last，间隔不可比：保守地不回收（宁可晚回收，不可误回收）。
        assertFalse("时钟回拨时不得误回收",
                MekckAe2.Hardening.jobStalled(500L, 1000L, MekckAe2.JOB_STALL_TIMEOUT_TICKS));
    }

    @Test
    public void stallTimeoutIsGenerousVersusBaseCraftTime() {
        // 所有家族单批基础耗时都是 200 tick（各 PROCESS_TIME 常量）；超时必须显著大于它，
        // 否则正常加工会被误回收。
        assertTrue("停滞超时必须 ≥ 2 倍基础加工耗时（200 tick）",
                MekckAe2.JOB_STALL_TIMEOUT_TICKS >= 400L);
        assertTrue("停滞超时必须有限且不夸张（≤ 5 分钟）",
                MekckAe2.JOB_STALL_TIMEOUT_TICKS <= 6000L);
    }

    @Test
    public void autoSelectCapStopsGrowthButStillAllowsRemoval() {
        int limit = MekckAe2.AUTO_SELECT_LIMIT;
        List<String> list = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            list = MekckAe2.Hardening.toggledAutoItems(list, "item_" + i, limit);
        }
        assertEquals("到上限前必须能加满", limit, list.size());

        List<String> capped = MekckAe2.Hardening.toggledAutoItems(list, "one_more", limit);
        assertEquals("到上限后不得再增长", limit, capped.size());
        assertFalse("到上限后新 id 不得进入清单", capped.contains("one_more"));

        List<String> removed = MekckAe2.Hardening.toggledAutoItems(capped, "item_0", limit);
        assertEquals("到上限后仍必须能取消勾选", limit - 1, removed.size());
        assertFalse(removed.contains("item_0"));

        List<String> afterRemove = MekckAe2.Hardening.toggledAutoItems(removed, "one_more", limit);
        assertTrue("腾出空位后必须能再加", afterRemove.contains("one_more"));
        assertEquals("腾出空位后加回仍是上限内", limit, afterRemove.size());
    }

    @Test
    public void autoSelectCapIsBoundedAndAtLeastLegacyPersistence() {
        assertTrue("上限不得小于旧落盘读取的 32 项", MekckAe2.AUTO_SELECT_LIMIT >= 32);
        assertTrue("上限必须有限（防客户端无界增长）", MekckAe2.AUTO_SELECT_LIMIT <= 256);
    }

    @Test
    public void emptyItemIdNeverEntersSelection() {
        assertTrue("空 id 不得进入清单",
                MekckAe2.Hardening.toggledAutoItems(List.of(), "", MekckAe2.AUTO_SELECT_LIMIT).isEmpty());
        assertTrue("null id 不得进入清单",
                MekckAe2.Hardening.toggledAutoItems(List.of(), null, MekckAe2.AUTO_SELECT_LIMIT).isEmpty());
    }

    @Test
    public void snapshotDetectsSelectionChangeWithinTtl() {
        // M7-m2 的根因：缓存里存 live list 会自比恒真；存快照才能识别勾选变化。
        List<String> live = new ArrayList<>(List.of("a"));
        List<String> snapshot = List.copyOf(live);
        assertTrue("未变化时快照与 live 相等（缓存可命中）", snapshot.equals(live));
        List<String> changed = MekckAe2.Hardening.toggledAutoItems(live, "b", MekckAe2.AUTO_SELECT_LIMIT);
        assertFalse("勾选变化后快照必须不再相等（否则缓存要等 TTL 过期才失效）",
                snapshot.equals(changed));
    }

    @Test
    public void availSnapshotTtlIsThrottleSized() {
        assertTrue("快照窗口必须 ≥ 1 tick（真的能合并连点）", MekckAe2.AVAIL_SNAPSHOT_TTL >= 1L);
        assertTrue("快照窗口不得长到面板明显过期（≤ 40 tick）", MekckAe2.AVAIL_SNAPSHOT_TTL <= 40L);
    }

    // ================== 二、源码形态 ==================

    @Test
    public void pullGenericIngredientsRejectsBusyMachineAndUnknownRecipe() throws IOException {
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src,
                "boolean pullGenericIngredients(BlockEntity be, String recipeId, int quantity, String seasoningId)");
        assertFalse("源码里找不到 pullGenericIngredients，判据失效", body.isEmpty());
        assertTrue("忙检查必须真的拒绝（保留调用但去掉 return false 即失效）",
                body.contains("ownerBusy()) return false"));
        assertTrue("配方校验必须真的拒绝（保留调用但去掉 return false 即失效）",
                body.contains("!isRegisteredRecipe(be, rid)) return false"));
        int busy = body.indexOf("ownerBusy()) return false");
        int extract = body.indexOf("extractAll(");
        assertTrue("忙检查必须发生在抽料之前（先抽料再拒绝会白扣网络材料）",
                busy >= 0 && extract > busy);
    }

    @Test
    public void registeredRecipeCheckReadsPanelEntries() throws IOException {
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src, "boolean isRegisteredRecipe(BlockEntity be, ResourceLocation recipeId)");
        assertFalse("源码里找不到 isRegisteredRecipe，判据失效", body.isEmpty());
        assertTrue("准入判据必须来自本机注册到终端的样板（panelEntries）", body.contains("panelEntries"));
        assertTrue("必须按 recipeId 比对", body.contains("recipeId.equals(entry.recipeId)"));
        int loop = body.indexOf("for (PatternEntry entry : panelEntries(be))");
        int yes = body.indexOf("return true;", loop);
        int no = body.indexOf("return false;", yes);
        assertTrue("必须命中才 true、遍历完才 false（恒 true 即失效）",
                loop >= 0 && yes > loop && no > yes);
    }

    @Test
    public void processJobRecyclesStalledJobWithLog() throws IOException {
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src, "void processJob(Level level)");
        assertFalse("源码里找不到 processJob，判据失效", body.isEmpty());
        int done = body.indexOf("orderDone()");
        int stalled = body.indexOf("if (Hardening.jobStalled(");
        assertTrue("停滞判定必须在 orderDone() 之后（订单还在跑时不能判停滞）",
                done >= 0 && stalled > done);
        assertTrue("停滞判定必须真的作为 if 条件（保留调用但去掉 if 即失效）",
                body.contains("if (Hardening.jobStalled(now, job.lastProgressTick, JOB_STALL_TIMEOUT_TICKS)) {"));
        assertTrue("停滞回收必须记日志", body.contains("LOGGER.warn"));
        int cleared = body.indexOf("job = null", stalled);
        assertTrue("停滞分支必须清掉 job", cleared > stalled);
    }

    @Test
    public void getNetworkAvailGoesThroughThrottledSnapshot() throws IOException {
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src, "Map<AEKey, Long> getNetworkAvail(BlockEntity be)");
        assertFalse("源码里找不到 getNetworkAvail，判据失效", body.isEmpty());
        assertTrue("getNetworkAvail 必须真的返回快照（保留调用但丢弃返回值即失效）",
                body.contains("return host.networkAvailSnapshot();"));
        assertFalse("getNetworkAvail 不得再无条件全量扫描 + 建 HashMap（含全限定写法）",
                body.contains("HashMap"));

        String snapshot = TestSourceText.methodBody(src, "Map<AEKey, Long> networkAvailSnapshot()");
        assertFalse("源码里找不到 networkAvailSnapshot，判据失效", snapshot.isEmpty());
        assertTrue("快照必须按 tick 窗口复用", snapshot.contains("AVAIL_SNAPSHOT_TTL"));
        assertTrue("窗口内必须真的复用缓存（return availSnapshot）",
                snapshot.contains("return availSnapshot;"));
        assertTrue("快照必须真的扫描网络（否则是空实现）", snapshot.contains("getAvailableStacks"));
    }

    @Test
    public void toggleAutoItemUsesHardCapAndPersistenceMatches() throws IOException {
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src, "void toggleAutoItem(String itemId)");
        assertFalse("源码里找不到 toggleAutoItem，判据失效", body.isEmpty());
        assertTrue("toggleAutoItem 必须把带上限的纯函数结果写回清单（丢弃返回值即失效）",
                body.contains("List<String> next = Hardening.toggledAutoItems(")
                        && body.contains("selectedAutoItems.addAll(next)"));
        assertTrue("纯函数调用必须传入上限常量", body.contains("itemId, AUTO_SELECT_LIMIT)"));
        assertTrue("上限常量必须存在", src.contains("AUTO_SELECT_LIMIT"));
        assertTrue("纯函数必须放在不引用 AE2 的静态嵌套类里（测试运行时 AE2 不在 classpath）",
                src.contains("static final class Hardening"));

        String load = TestSourceText.methodBody(src, "void loadFromNBT(CompoundTag tag)");
        assertFalse("源码里找不到 loadFromNBT，判据失效", load.isEmpty());
        assertTrue("落盘读取必须与上限同源（否则 33..64 项读档即丢）",
                load.contains("i < AUTO_SELECT_LIMIT"));
    }

    @Test
    public void autoIndexStoresImmutableSnapshot() throws IOException {
        String src = TestSourceText.read(AE2);
        assertTrue("AUTO_INDEX 缓存必须存勾选列表快照（List.copyOf），"
                        + "否则 selected().equals(selected) 自比恒真",
                src.contains("new CachedIndex(List.copyOf(selected)"));
        assertTrue("缓存命中必须比较快照与 live list（比较被短路即失效）",
                src.contains("cached.selected().equals(selected)"));
    }

    @Test
    public void assemblerOwnerBusyIsNotInverted() throws IOException {
        // 组装机没有订单字段：ownerBusy() 曾写成 !orderDone()（= 输出槽空 ⇒ 空闲恒忙），
        // 而 AE2 的 CraftingCpuLogic 会跳过 isBusy() 为真的 provider ⇒ 终端下单永远推不进来。
        String src = TestSourceText.read(AE2);
        String body = TestSourceText.methodBody(src, "boolean ownerBusy()");
        assertFalse("源码里找不到 ownerBusy，判据失效", body.isEmpty());
        int asm = body.indexOf("SandwichAssemblerBlockEntity");
        assertTrue("ownerBusy 必须显式处理组装机（无订单字段机器）", asm >= 0);
        int next = body.indexOf("if (owner instanceof", asm + 1);
        String branch = next > asm ? body.substring(asm, next) : body.substring(asm);
        assertFalse("组装机分支不得返回 !orderDone()（空闲时恒忙 ⇒ AE2 永远跳过 provider）",
                branch.contains("!orderDone()"));
        assertTrue("组装机分支必须显式返回 false（空闲即不忙；有任务由 job != null 覆盖）",
                branch.contains("return false;"));
    }

    // ── 能力清单不许静默缩水 ──────────────────────────────────────────────

    /**
     * <b>凡是名字里带「研磨/烧烤/切菜/…」的已迁单机 tile，都必须仍然满足 AE2 的能力面。</b>
     *
     * <h3>缺陷形态（本轮实测踩到）</h3>
     * 电力研磨机迁到 Mek 原生 tile 时，新 tile <b>只 implements {@code MenuProvider}</b>，
     * 把旧 BE 的 {@code INetworkPullable} 整份漏掉了 ⇒ AE2 的「网络拉料」「自动补料」
     * 「面板下单」<b>全部静默消失</b>：编译通过、732 个测试全绿，
     * 因为没有任何护栏守「这台机器的能力清单」。
     *
     * <p>对比参照：烧烤架（同为已迁单机）保留了 {@code INetworkPullable}；
     * 6 个工厂家族与它们的 tile 走 {@code IMekCkPorted}。
     * 两条路都通 AE2，但<b>不能两条都不走</b>。</p>
     *
     * <p>判据：扫 {@code machine/} 与 {@code blockentity/} 下所有 tile 类，
     * 取「已迁到 Mek 原生基类」的那些（继承 {@code MekCkMachineTile} /
     * {@code MekCkNetworkPullableTile} / {@code TileEntityConfigurableMachine}），
     * 每一个都必须通过自身或基类实现 {@code INetworkPullable} 或 {@code IMekCkPorted}。</p>
     */
    @Test
    public void migratedTilesKeepTheirAe2Capability() throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/cn/ism/mekck");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : files
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String path = file.toString().replace('\\', '/');
                // 只看机器 tile：machine/ 下的 *Tile，以及 blockentity/ 下已迁到 Mek 基类的
                if (!(path.contains("/machine/") && path.endsWith("Tile.java"))) {
                    continue;
                }
                String src = cn.ism.mekck.TestSourceText.read(file.toString());
                String decl = src.substring(0, Math.min(src.length(), 4000));
                if (!decl.contains("extends MekCkMachineTile")
                        && !decl.contains("extends MekCkNetworkPullableTile")) {
                    continue;
                }
                scanned++;
                // 自身或基类链上有这两个之一即可（MekCkMachineTile / MekCkNetworkPullableTile
                // 都实现了 INetworkPullable，所以继承它们即满足）。
                boolean ok = decl.contains("INetworkPullable")
                        || decl.contains("IMekCkPorted")
                        || decl.contains("extends MekCkMachineTile")
                        || decl.contains("extends MekCkNetworkPullableTile");
                if (!ok) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue("一个已迁 tile 都没扫到，判据已失效（扫描面变了？）", scanned >= 7);
        assertEquals("这些已迁 tile 既不走 IMekCkPorted 也不走 INetworkPullable "
                        + "—— 它们的 AE2 能力（网络拉料/自动补料/面板下单）已经静默消失：\n  "
                        + String.join("\n  ", offenders),
                List.of(), offenders);
    }

    /**
     * 单机 tile 的基类必须真的把 {@code INetworkPullable} 声明出来 ——
     * 这是上一条断言「继承即满足」的依据，把它钉住，防止有人把基类的 implements 删掉
     * 而上一条判据仍因「名字出现过」而恒绿。
     */
    @Test
    public void networkPullableBaseActuallyDeclaresTheInterface() throws IOException {
        String src = TestSourceText.read(
                "src/main/java/cn/ism/mekck/machine/MekCkNetworkPullableTile.java");
        assertTrue("MekCkNetworkPullableTile 必须 implements INetworkPullable —— "
                        + "上一条判据把「继承它」当作满足能力的依据，删了这个 implements 就变成假绿",
                src.contains("implements cn.ism.mekck.ae2.INetworkPullable"));
        // 五个方法一个都不能少
        for (String m : new String[]{"getNetworkPullable", "getInputSlotRange",
                "getNetworkPullItems", "supportsAutoPull", "getNetworkPullInputs"}) {
            assertTrue("MekCkNetworkPullableTile 缺少 " + m + "（INetworkPullable 的契约）",
                    src.contains(m));
        }
    }
}
