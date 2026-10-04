package cn.ism.mekck.machine.skewering;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.compat.KaleidoscopeGrillingCompat;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 穿串工厂执行器的三条护栏：<b>返还闸门</b>、<b>返还来源</b>与<b>产物容量判定</b>。
 *
 * <h3>它钉的是哪几个 bug</h3>
 * <ol>
 *   <li><b>返还槽无条件复制签子</b>。{@code run()} 原先无条件调 {@code returnPayload}：
 *       自有配方序列化器写死 {@code ingredientCount = 0}（签子不消耗），返还的却是
 *       「输入槽 0 的整叠 × 批量」⇒ 每批把 batch 个签子复制进返还槽（物品复制，
 *       与 M13 修的机器侧同型）。M24 加了 {@code toolCount > 0} 闸门。</li>
 *   <li><b>返还复制的是槽 0 的另一种物品</b>。闸门只挡住了 toolCount=0 那一路：
 *       toolCount&gt;0 且签子不在槽 0 时（扣料位置无关，签子放存储区照样扣得动），
 *       返还的仍是「槽 0 的整叠 × 批量」——主料被复制。M29 修复：返还按扣料记录落槽，
 *       内容 = 本次实际消耗的签子（类型与数量）。</li>
 *   <li><b>canProcess 不判产物容量</b>。产物槽满时它仍返回 true，{@code workCycle}
 *       照常扣电、进度条照走，而 {@code run} 在落槽前直接 {@code return} ——
 *       玩家看不到产出、看不到告警，电却一直在掉（与 M18 修的烹饪侧同型）。</li>
 * </ol>
 *
 * <h3>为什么护栏分两半</h3>
 * 真 tile 在裸 JVM 里造不出来，{@code canProcess} 又需要 {@code Level} 与配方，所以：
 * <ul>
 *   <li><b>行为断言</b>打在 {@link SkeweringFactoryExecutor#canFitBatch} 与
 *       {@link SkeweringFactoryExecutor#consumeOne} / {@link SkeweringFactoryExecutor#returnPayload}
 *       上 —— 容量判据的全部输入就是「产物槽 + 单份产物 + 批量 + 返还预览」，
 *       返还链路的全部输入就是「扫描槽 + 签子配料 + 数量」，用假槽位即可覆盖
 *       「满 / 差一点 / 装得下」与「签子不在槽 0」的反例形态；</li>
 *   <li><b>接线断言</b>读源码：{@code canProcess} 与 {@code run} 都必须调容量判据，
 *       且返还的唯一产出点必须带 {@code toolCountOf(recipe) > 0} 闸门、必须来自扣料记录。
 *       少了这一半，把闸门或容量判定删掉时行为断言仍会全绿。</li>
 * </ul>
 */
public class TestSkeweringFactoryGuards {

    private static final String SKEWERING_EXECUTOR =
            "src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryExecutor.java";

    /**
     * 裸 JVM 里把原版注册表拉起来 —— 与 {@code TestCookingFactoryEnergyDrain.boot()} 同一套两步。
     *
     * <p>第二步会在 Forge 网络钩子阶段抛
     * {@code NoSuchMethodException: net.minecraftforge.network.NetworkEvent.<init>()}
     * （测试 classpath 上的 Minecraft 没经 Forge 的 mixin/ASM 处理），
     * 但 {@code BuiltInRegistries} 与原版物品此时已注册完毕，吞掉即可。</p>
     */
    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    /** 最小可用的 {@link IInventorySlot}：栈读写 + 可配单槽容量。 */
    private static final class TestSlot implements IInventorySlot {

        private ItemStack stack;
        private final int limit;

        TestSlot(ItemStack stack, int limit) {
            this.stack = stack;
            this.limit = limit;
        }

        @Override
        public ItemStack getStack() {
            return stack;
        }

        @Override
        public void setStack(ItemStack stack) {
            this.stack = stack;
        }

        @Override
        public int getLimit(ItemStack stack) {
            return limit;
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return true;
        }

        @Override
        public Slot createContainerSlot() {
            throw new UnsupportedOperationException("本测试不建 GUI 槽位");
        }

        @Override
        public void onContentsChanged() {
        }

        @Override
        public CompoundTag serializeNBT() {
            return new CompoundTag();
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
        }
    }

    private static ItemStack beef(int count) {
        return new ItemStack(Items.COOKED_BEEF, count);
    }

    private static List<IInventorySlot> outputs(IInventorySlot product, IInventorySlot returns) {
        return List.of(product, returns);
    }

    // ── 行为：产物 / 返还物装不下 ⇒ 判 false（canProcess 据此不扣电、点亮告警） ──

    /** 产物槽被<b>别的物品</b>占满 ⇒ 本批装不下。 */
    @Test
    public void fullProductSlotRejectsTheBatch() {
        List<IInventorySlot> outputs = outputs(new TestSlot(new ItemStack(Items.BREAD, 64), 64),
                new TestSlot(ItemStack.EMPTY, 64));
        assertFalse("产物槽满时 canProcess 必须判 false，否则进度条照走、电照扣、还不告警",
                SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 1, ItemStack.EMPTY));
    }

    /** 同类物品但剩余空间不够 ⇒ 装不下；正好够 ⇒ 装得下。 */
    @Test
    public void batchLargerThanRemainingSpaceIsRejected() {
        List<IInventorySlot> outputs = outputs(new TestSlot(beef(60), 64), new TestSlot(ItemStack.EMPTY, 64));
        assertFalse("只剩 4 个位置，批量 8 装不下",
                SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 8, ItemStack.EMPTY));
        assertTrue("批量 4 正好装得下",
                SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 4, ItemStack.EMPTY));
    }

    /** 空产物槽：按单槽容量判，超一个都不算装得下。 */
    @Test
    public void emptyProductSlotAcceptsUpToItsLimit() {
        List<IInventorySlot> outputs = outputs(new TestSlot(ItemStack.EMPTY, 64), new TestSlot(ItemStack.EMPTY, 64));
        assertTrue(SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 64, ItemStack.EMPTY));
        assertFalse("超过单槽容量就不算装得下",
                SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 65, ItemStack.EMPTY));
    }

    /** 返还物也要装得下：产物槽空、返还槽满 ⇒ 判 false（否则 run 会在扣料后白工）。 */
    @Test
    public void returnPreviewMustAlsoFit() {
        List<IInventorySlot> full = outputs(new TestSlot(ItemStack.EMPTY, 64),
                new TestSlot(new ItemStack(Items.BREAD, 64), 64));
        assertFalse("返还槽满时本批装不下",
                SkeweringFactoryExecutor.canFitBatch(full, beef(1), 1, new ItemStack(Items.STICK, 1)));
        List<IInventorySlot> roomy = outputs(new TestSlot(ItemStack.EMPTY, 64),
                new TestSlot(new ItemStack(Items.STICK, 60), 64));
        assertTrue("返还槽还剩 4 个位置，返还 4 个正好装得下",
                SkeweringFactoryExecutor.canFitBatch(roomy, beef(1), 1, new ItemStack(Items.STICK, 4)));
        assertFalse("返还 5 个装不下",
                SkeweringFactoryExecutor.canFitBatch(roomy, beef(1), 1, new ItemStack(Items.STICK, 5)));
    }

    /**
     * 返还预览为空（自有配方 toolCount = 0）⇒ 返还槽不参与判定。
     *
     * <p>这条同时钉住闸门的<b>效果</b>：返还槽被别的物品占满时，自有配方照样能开工
     * —— 它本来就不该往返还槽放东西。</p>
     */
    @Test
    public void emptyReturnPreviewIgnoresTheReturnSlot() {
        List<IInventorySlot> outputs = outputs(new TestSlot(ItemStack.EMPTY, 64),
                new TestSlot(new ItemStack(Items.BREAD, 64), 64));
        assertTrue("不返还的批次不该被返还槽的占用挡住",
                SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 1, ItemStack.EMPTY));
    }

    /** 退化输入（没有产物槽 / 只有一格 / 配方无产物 / 批量 &le; 0）一律判 false，不抛异常。 */
    @Test
    public void degenerateInputsAreRejected() {
        List<IInventorySlot> outputs = outputs(new TestSlot(ItemStack.EMPTY, 64), new TestSlot(ItemStack.EMPTY, 64));
        assertFalse("没有产物槽", SkeweringFactoryExecutor.canFitBatch(List.of(), beef(1), 1, ItemStack.EMPTY));
        assertFalse("产物槽列表为 null", SkeweringFactoryExecutor.canFitBatch(null, beef(1), 1, ItemStack.EMPTY));
        assertFalse("只有产物槽、没有返还槽",
                SkeweringFactoryExecutor.canFitBatch(List.of(new TestSlot(ItemStack.EMPTY, 64)),
                        beef(1), 1, ItemStack.EMPTY));
        assertFalse("配方无产物", SkeweringFactoryExecutor.canFitBatch(outputs, ItemStack.EMPTY, 1, ItemStack.EMPTY));
        assertFalse("批量 <= 0", SkeweringFactoryExecutor.canFitBatch(outputs, beef(1), 0, ItemStack.EMPTY));
    }

    // ── 行为：返还只落本次实际消耗的签子（M29 反例形态） ──────────────

    /**
     * 森罗虚拟配方同形的配方：执行器反射读 public 字段 {@code tool} / {@code ingredientCount}。
     *
     * <p>用真 {@link KaleidoscopeGrillingCompat.VirtualRecipe} 而不是测试自造类，
     * 是因为「签子 = {@code Ingredient.of(STICK)}、toolCount = 1」正是 finding 里
     * 那条可达路径的形状。</p>
     */
    private static Recipe<?> threadingRecipe(Ingredient tool, int toolCount) {
        return new KaleidoscopeGrillingCompat.VirtualRecipe(
                new ResourceLocation("mekck", "threading/test"),
                tool, Ingredient.of(Items.COOKED_BEEF), toolCount, null, 0,
                new ItemStack(Items.COOKED_BEEF));
    }

    /**
     * 反例形态：主料在输入槽 0、签子在存储槽。
     *
     * <p>扣料位置无关（扫描集合 = 3 输入槽 + 81 存储槽），所以签子照样被扣；
     * 返还必须是<b>被扣掉的签子</b>（类型与数量），而不是槽 0 里恰好放着的主料 ——
     * 旧写法读槽 0 复制整叠，这条路径上复制的是主料（物品复制）。</p>
     */
    @Test
    public void returnFollowsConsumedToolNotSlotZero() {
        IInventorySlot slot0 = new TestSlot(beef(64), 64);
        IInventorySlot storage = new TestSlot(new ItemStack(Items.STICK, 10), 64);
        List<IInventorySlot> scan = List.of(slot0, storage);
        Ingredient stick = Ingredient.of(Items.STICK);

        // 预览（canProcess / run 的容量判据用）：批量 4 ⇒ 预览是 4 根签子
        ItemStack preview = SkeweringFactoryExecutor.returnPreview(threadingRecipe(stick, 1), scan, 4);
        assertTrue("预览必须是实际会被扣的签子，不是槽 0 的主料",
                ItemStack.isSameItemSameTags(preview, new ItemStack(Items.STICK)));
        assertEquals("预览数量 = 本批要扣的签子数（toolCount × batch）", 4, preview.getCount());

        // 真扣料：记录 = 签子；返还 = 记录合并
        List<ItemStack> consumed = SkeweringFactoryExecutor.consumeOne(scan, stick, 4, false);
        ItemStack returned = SkeweringFactoryExecutor.returnPayload(consumed);
        assertTrue("返还必须是本次实际扣掉的签子",
                ItemStack.isSameItemSameTags(returned, new ItemStack(Items.STICK)));
        assertEquals("返还数量必须等于实际扣掉的数量", 4, returned.getCount());
        assertEquals("槽 0 的主料一个都不能少", 64, slot0.getStack().getCount());
        assertEquals("存储槽的签子被扣掉 4 根", 6, storage.getStack().getCount());
    }

    /** 正常形态：签子在槽 0 ⇒ 返还仍是签子（修复不能把正常路径改坏）。 */
    @Test
    public void toolInSlotZeroStillReturnsTool() {
        IInventorySlot slot0 = new TestSlot(new ItemStack(Items.STICK, 10), 64);
        IInventorySlot slot1 = new TestSlot(beef(64), 64);
        List<IInventorySlot> scan = List.of(slot0, slot1);
        Ingredient stick = Ingredient.of(Items.STICK);

        ItemStack preview = SkeweringFactoryExecutor.returnPreview(threadingRecipe(stick, 1), scan, 3);
        assertTrue(ItemStack.isSameItemSameTags(preview, new ItemStack(Items.STICK)));
        assertEquals(3, preview.getCount());

        List<ItemStack> consumed = SkeweringFactoryExecutor.consumeOne(scan, stick, 3, false);
        ItemStack returned = SkeweringFactoryExecutor.returnPayload(consumed);
        assertTrue(ItemStack.isSameItemSameTags(returned, new ItemStack(Items.STICK)));
        assertEquals(3, returned.getCount());
        assertEquals("槽 0 的签子被扣掉 3 根", 7, slot0.getStack().getCount());
        assertEquals("槽 1 的主料一个都不能少", 64, slot1.getStack().getCount());
    }

    /** 记录跨多槽（同物）⇒ 合并计数；返还数量 = 实际扣掉的总数。 */
    @Test
    public void consumedRecordMergesAcrossSlots() {
        IInventorySlot first = new TestSlot(new ItemStack(Items.STICK, 2), 64);
        IInventorySlot second = new TestSlot(new ItemStack(Items.STICK, 5), 64);
        List<IInventorySlot> scan = List.of(first, second);

        List<ItemStack> consumed = SkeweringFactoryExecutor.consumeOne(scan, Ingredient.of(Items.STICK), 6, false);
        ItemStack returned = SkeweringFactoryExecutor.returnPayload(consumed);
        assertTrue(ItemStack.isSameItemSameTags(returned, new ItemStack(Items.STICK)));
        assertEquals("跨槽扣掉的 6 根必须合并成一个返还栈", 6, returned.getCount());
        assertTrue("第一槽被扣空", first.getStack().isEmpty());
        assertEquals("第二槽剩 1 根", 1, second.getStack().getCount());
    }

    /** 闸门行为：toolCount = 0（自有配方）或签子配料为空 ⇒ 预览为空，不返还。 */
    @Test
    public void unconsumedToolIsNotReturned() {
        List<IInventorySlot> scan = List.of(new TestSlot(new ItemStack(Items.STICK, 10), 64));

        ItemStack zeroCount = SkeweringFactoryExecutor.returnPreview(
                threadingRecipe(Ingredient.of(Items.STICK), 0), scan, 4);
        assertTrue("toolCount = 0 时不得返还（自有配方签子不消耗，无条件返还等于复制）",
                zeroCount.isEmpty());

        ItemStack emptyTool = SkeweringFactoryExecutor.returnPreview(
                threadingRecipe(Ingredient.EMPTY, 1), scan, 4);
        assertTrue("签子配料为空时不得返还", emptyTool.isEmpty());
    }

    // ── 接线：返还闸门 + canProcess/run 共用同一容量判据 ────────────────

    /**
     * 源码不变量：返还的唯一闸门在 {@code returnPreview} ——
     * {@code toolCountOf(recipe) > 0} 且签子配料非空，与机器侧同款。
     *
     * <p>自有配方写死 {@code ingredientCount = 0}：闸门一旦被删，每批把 batch 个签子
     * 复制进返还槽 —— 物品复制，且没有任何日志。</p>
     */
    @Test
    public void returnPreviewIsGatedByToolCount() throws IOException {
        String src = TestSourceText.read(SKEWERING_EXECUTOR);
        String body = TestSourceText.methodBody(
                src, "static ItemStack returnPreview(Recipe<?> recipe, List<IInventorySlot> scan, int batch)");
        assertFalse("找不到 returnPreview（改名了就同步更新本测试）", body.isEmpty());
        assertTrue("返还必须受 toolCountOf(recipe) > 0 门控：自有配方 toolCount = 0（签子不消耗），"
                        + "无条件返还等于每批复制 batch 个签子",
                body.contains("toolCountOf(recipe) > 0"));
        assertTrue("返还还必须要求签子配料非空（与机器侧 !tool.isEmpty() 同款）",
                body.contains("!toolOf(recipe).isEmpty()"));
        assertTrue("返还预览必须在扫描集合上预演扣料（consumeOne），不能读输入槽 0："
                        + "扣料位置无关，签子不在槽 0 时读槽 0 会复制另一种物品",
                body.contains("consumeOne("));
        assertFalse("返还预览不得读输入槽 0（旧错配形态）", body.contains("inputs.get(0)"));
    }

    /**
     * 源码不变量：{@code run} 的返还必须来自本次扣料记录，而不是任何槽的当前内容。
     *
     * <p>扣料位置无关（3 输入槽 + 81 存储槽），「读槽 0」与「读记录」在签子不在槽 0 时
     * 给出完全不同的物品 —— 前者复制主料（物品复制），后者才是签子本身。</p>
     */
    @Test
    public void runReturnsTheConsumedRecord() throws IOException {
        String src = TestSourceText.read(SKEWERING_EXECUTOR);
        String run = TestSourceText.methodBody(
                src, "private void run(Level level, Recipe<?> recipe, int batch, List<IInventorySlot> scan)");
        assertFalse("找不到 run", run.isEmpty());
        assertTrue("run 必须把扣料记录交给 returnPayload（返还 = 实际消耗）",
                run.contains("returnPayload(consumedTool)"));
        assertTrue("run 必须用 consume 的记录（consume 返回被扣的签子栈）",
                run.contains("consume(scan, recipe, batch)"));
        assertFalse("run 的返还不得再读输入槽 0（旧错配形态）", run.contains("inputs.get(0)"));
    }

    /**
     * 源码不变量：{@code canProcess} 与 {@code run} 必须共用同一容量判据，
     * 且 {@code canProcess} 要把返还物也纳入判定。
     *
     * <p>只钉一处不够：两处一旦各写一份，就会出现「预检说能加工、落槽时却装不下」
     * 的漂移 —— 那正是本仓库反复踩过的那类静默故障。</p>
     */
    @Test
    public void canProcessAndRunShareTheSameCapacityCheck() throws IOException {
        String src = TestSourceText.read(SKEWERING_EXECUTOR);
        String canProcess = TestSourceText.methodBody(
                src, "public boolean canProcess(MekCkMachineTile tile, int index)");
        String run = TestSourceText.methodBody(
                src, "private void run(Level level, Recipe<?> recipe, int batch, List<IInventorySlot> scan)");
        assertFalse("找不到 canProcess", canProcess.isEmpty());
        assertFalse("找不到 run", run.isEmpty());
        assertTrue("canProcess 必须做容量判定，否则产物满时仍扣电且无告警",
                canProcess.contains("canFitBatch("));
        assertTrue("canProcess 必须把返还物也纳入容量判定（与 run 同口径）",
                canProcess.contains("returnPreview("));
        assertTrue("run 必须与 canProcess 共用同一容量判据", run.contains("canFitBatch("));
        assertTrue("run 的返还必须走带闸门的 returnPreview", run.contains("returnPreview("));
    }
}
