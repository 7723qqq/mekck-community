package cn.ism.mekck.kitchen;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 中央厨房「补全功能」的护栏：自动加工入口、线程生命周期、订单批数口径、昂贵请求节流。
 *
 * <p>这几条的共同点：<b>缺了不会编译失败、也不会有任何既有用例变红</b>，
 * 只是功能静默失效或物品出问题。本类把它们钉住。</p>
 */
public class TestKitchenFeatureCompleteness {

    private static final String BE =
            "src/main/java/cn/ism/mekck/blockentity/CentralKitchenBlockEntity.java";
    private static final String WINDOW = "src/main/java/cn/ism/mekck/client/KitchenModuleWindow.java";
    private static final String FILTER_PACKET = "src/main/java/cn/ism/mekck/network/KitchenFilterPacket.java";
    private static final String SYNC_PACKET = "src/main/java/cn/ism/mekck/network/KitchenFilterSyncPacket.java";
    private static final String ORDER_PACKET = "src/main/java/cn/ism/mekck/network/KitchenOrderPacket.java";
    private static final String PLAN = "src/main/java/cn/ism/mekck/kitchen/KitchenCraftingPlan.java";
    private static final String MENU = "src/main/java/cn/ism/mekck/menu/CentralKitchenMenu.java";
    private static final String SCREEN = "src/main/java/cn/ism/mekck/client/CentralKitchenScreen.java";

    // ==================== I2：自动加工模式必须可达 ====================

    /**
     * 自动加工开关必须<b>真的有入口</b>。
     *
     * <p>{@code setAutoMode} 此前<b>零调用方</b> ⇒ 整个自动加工引擎（线程推进、
     * ACTIVE 块状态、屏幕线程数、「模块提供 x 线程」显示）都是死代码，
     * 而「自动模式」作为一项产品功能只存在于类注释里。</p>
     */
    @Test
    public void autoModeSwitchHasAWorkingEntryPoint() throws IOException {
        assertTrue("setAutoMode 必须至少有一个调用方（此前零调用 ⇒ 整个自动加工引擎不可达）",
                countCallers("setAutoMode(", "src/main/java") > 0);

        // 服务端：action 5 切开关，且必须挡住「没装模块」
        String fp = TestSourceText.read(FILTER_PACKET);
        assertTrue("KitchenFilterPacket 必须有切换自动模式的分支（action 5）",
                fp.contains("case 5 ->"));
        String c5 = TestSourceText.methodBody(fp, "case 5 -> {");
        assertTrue("切换前必须判空 abilityOf：没有模块的系列即便置真也不会被 tickAutoMode 遍历到，"
                        + "玩家会看到「按钮开了但什么都不发生」—— 那正是本功能此前不可达的状态换个形式复现",
                fp.contains("abilityOf(families[familyOrdinal]) != null"));

        // 客户端：按钮 + 只对已装模块的系列响应
        String win = TestSourceText.read(WINDOW);
        assertTrue("模块窗口必须画自动加工开关", win.contains("cached.autoMode"));
        assertTrue("开关必须用 getFamilyMask() 判「已装模块」，与服务端口径一致",
                win.contains("menu.getFamilyMask()"));
        assertTrue("点击处理必须与服务端一样只对已装模块响应",
                TestSourceText.methodBody(win, "public mekanism.client.gui.element.GuiElement mouseClickedNested(")
                        .contains("(byte) 5"));

        // 状态必须能同步到客户端
        String sync = TestSourceText.read(SYNC_PACKET);
        assertTrue("同步包必须携带 autoMode", sync.contains("autoMode"));
        assertTrue("encode 必须写出该位", sync.contains("buffer.writeBoolean(autoMode)"));
        assertTrue("decode 必须读回该位", sync.contains("buffer.readBoolean()"));
    }

    // ==================== I3：线程缩容不得销毁在制品 ====================

    /**
     * 读档缩容<b>只能删空闲线程</b>。
     *
     * <p>busy 线程的材料在 {@code startThread} 开工那一刻就扣了、产物还没出，
     * 删它就是静默销毁玩家的材料。原实现是无条件
     * {@code while (size > want) list.remove(size - 1);}。
     * 这一条此前被「自动模式不可达」掩盖着 —— 补上开关就立刻是 Critical。</p>
     */
    @Test
    public void shrinkingThreadsNeverDropsABusyThread() throws IOException {
        String be = TestSourceText.read(BE);
        String load = TestSourceText.methodBody(be, "public void load(");
        assertFalse("load 里的缩容循环不得再出现无条件的 list.remove(list.size() - 1)"
                        + "（那会直接删掉正在加工的线程 ⇒ 材料凭空损失）",
                load.contains("list.remove(list.size() - 1)"));
        assertTrue("缩容必须先挑空闲线程（!busy()）再删", load.contains("!list.get(i).busy()"));
        assertTrue("全部线程都在忙时必须 break，不得强行删", load.contains("break;"));

        // 模块被拆掉时也要退款，不能静默丢弃
        assertTrue("模块被拆（abilityOf == null）时必须退款而不是 continue 丢弃",
                load.contains("refundOrphanedThreads(f)"));
        String refund = TestSourceText.methodBody(be, "private void refundOrphanedThreads(");
        assertFalse("找不到 refundOrphanedThreads", refund.isEmpty());
        assertTrue("退款必须按线程记录的 consumes 逐槽精确退", refund.contains("t.consumes"));
    }

    /**
     * 超编的空闲线程<b>不得开工</b>。
     *
     * <p>这是上一条的配套：读档为了不丢材料而保留了超编的 busy 线程，它们跑完后变空闲。
     * 若 {@code tickAutoMode} 对列表里<b>每一条</b>都调 {@code startThread}，
     * 那么换一个低等级模块反而会凭空多出线程 —— 而模块明明只提供了 capacity 条。</p>
     */
    @Test
    public void overCapacityIdleThreadsDoNotStartWork() throws IOException {
        String be = TestSourceText.read(BE);
        String tick = TestSourceText.methodBody(be, "private void tickAutoMode(");
        assertFalse("找不到 tickAutoMode", tick.isEmpty());
        assertTrue("tickAutoMode 必须算出一个 capacity 并只在容量内开工", tick.contains("capacity"));
        assertTrue("开工必须带 i < capacity 条件", tick.contains("i < capacity"));
        assertTrue("busy 线程必须无条件推进（超编的那些要跑完，否则材料/产物状态就悬着）",
                tick.contains("if (t.busy())"));
    }

    // ==================== I4：批数口径 ====================

    /**
     * {@code Step.batches} 是<b>执行次数</b>，读档重建时不得再除一次 {@code perCraft}。
     *
     * <p>写入侧 {@code expand} 存的是 {@code ceil(要求产出件数 / perCraft)}；
     * {@code rebuildStep} 原先又除了一遍 ⇒ 每读一次档订单量就缩一次
     * （400 件 / perCraft=4 → 100 → 25 → 7 → 2 → 1）。</p>
     */
    @Test
    public void rebuildStepTreatsBatchesAsExecutionCount() throws IOException {
        String plan = TestSourceText.read(PLAN);
        String expand = TestSourceText.methodBody(plan, "private static String expand(");
        String rebuild = TestSourceText.methodBody(plan, "public static Step rebuildStep(");
        assertFalse("找不到 rebuildStep", rebuild.isEmpty());

        // 写入侧：Step 拿到的是 need（执行次数）
        assertTrue("expand 必须把 need（执行次数）写进 Step，而不是原始的产出件数",
                expand.contains("new Step(family, recipe.getId(), inputs, out.copy(), need,"));
        // 读出侧：不得再除 perCraft
        assertFalse("rebuildStep 不得再除一次 perCraft："
                        + "Step.batches 存的就是执行次数，再除会让订单量每次读档都缩水",
                rebuild.contains("/ perCraft"));
        assertTrue("rebuildStep 必须按执行次数设置每种 ingredient 的数量（与 expand 的 setCount(need) 一致）",
                rebuild.contains("sample.setCount(executions)"));
        assertTrue("rebuildStep 构造 Step 时必须传 executions（执行次数）",
                rebuild.contains("inputs, output, executions,"));
    }

    // ==================== I9：昂贵请求节流 ====================

    /**
     * 预览 / 下单包必须过节流闸。
     *
     * <p>{@code previewOrder} 与 {@code placeOrder} 每次都走
     * {@code solve → buildReverseIndex}（对已安装系列的全部 recipeTypes 逐条
     * {@code getResultItem}）；{@code RecipeCache} 只缓存配方<b>列表</b>。
     * 而 {@code mode == 0} 的预览<b>不消耗任何材料</b>，客户端可纯刷打满主线程。</p>
     */
    @Test
    public void expensiveOrderRequestsAreThrottled() throws IOException {
        String guard = TestSourceText.read("src/main/java/cn/ism/mekck/network/PacketGuard.java");
        assertTrue("PacketGuard 必须提供昂贵请求的节流闸", guard.contains("expensiveRequest"));
        // 「同指纹放行」是刻意的：GUI 里连点两次预览是自然操作，一律拒绝会像 bug。
        String throttle = TestSourceText.methodBody(guard, "public static boolean expensiveRequest(");
        assertTrue("同指纹（重复请求）在冷却期内必须放行——结果必然相同，拒了会像 bug",
                throttle.contains("slot[1] == fingerprint"));
        assertTrue("不同请求在冷却期内必须被拒", throttle.contains("< EXPENSIVE_COOLDOWN_TICKS"));

        String order = TestSourceText.read(ORDER_PACKET);
        assertTrue("KitchenOrderPacket 必须先过节流再算订单（预览不耗材料，可被纯刷）",
                order.contains("PacketGuard.expensiveRequest"));

        String menu = TestSourceText.read(MENU);
        String screen = TestSourceText.read(SCREEN);
        assertTrue("存储浏览器必须会周期性重建（refreshDisplay 此前只在构造/搜索/排序/quickMove 时被调）",
                screen.contains("menu.refreshDisplay()"));
        assertTrue("滚动必须整段重建而不是只重切陈旧的 filtered",
                TestSourceText.methodBody(menu, "public void scroll(").contains("refreshDisplay()"));
        assertTrue("refreshDisplay 必须把 scrollRow 夹回合法范围（内容变少时否则整页空且页码指示错）",
                TestSourceText.methodBody(menu, "public void refreshDisplay()").contains("maxScrollRow()"));
    }

    // ==================== 工具 ====================

    private static int countCallers(String needle, String root) throws IOException {
        final int[] n = {0};
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(Path.of(root))) {
            for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String src = TestSourceText.readUnchecked(p.toString());
                int idx = src.indexOf(needle);
                while (idx >= 0) {
                    n[0]++;
                    idx = src.indexOf(needle, idx + needle.length());
                }
            }
        }
        return n[0];
    }
}
