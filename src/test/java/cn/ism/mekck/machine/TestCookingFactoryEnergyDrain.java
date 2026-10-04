package cn.ism.mekck.machine;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.machine.cooking.CookingFactoryExecutor;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 烹饪工厂「产物槽满时不再持续扣电、且会告警」的护栏。
 *
 * <h3>它钉的是哪个 bug</h3>
 * {@code workCycle} 的逐路扣电条件是 {@code exec.canProcess(this, i)}：判 true 才扣电、
 * 才推进度条。而 {@code CookingFactoryExecutor.canProcess} 原先只判「有订单 + 有配方 +
 * 批量 &gt; 0」，<b>不判产物装不装得下</b> —— 产物槽满时它仍返回 true，于是：
 * <ul>
 *   <li>每 tick 照扣电、进度条照走满一个周期；</li>
 *   <li>周期末 {@code run()} 在落槽前 {@code return}，什么也不产出；</li>
 *   <li>告警位是 {@code !can && laneHasInput(i)}，{@code can} 为 true ⇒ <b>不告警</b>。</li>
 * </ul>
 * 旧实现的 {@code canProcess} 里本来就有这一项（{@code canFitAll}），
 * 且 {@code energyPerTick = canProcess ? … : 0} —— 产物满时旧机器一滴电都不扣。
 *
 * <h3>为什么护栏分两半</h3>
 * 真 tile 在裸 JVM 里造不出来，{@code canProcess} 又需要 {@code Level} 与配方，
 * 所以：
 * <ul>
 *   <li><b>行为断言</b>打在 {@link CookingFactoryExecutor#canFitBatch} 上 ——
 *       它是 {@code canProcess} 与 {@code run} 共用的容量判据，全部输入就是
 *       「产物槽 + 单份产物 + 批量」，用假槽位即可覆盖「满 / 差一点 / 装得下」；</li>
 *   <li><b>接线断言</b>读源码：{@code canProcess} 与 {@code run} 都必须调它。
 *       少了这一半，把容量判定从 {@code canProcess} 里删掉时行为断言仍会全绿。</li>
 * </ul>
 */
public class TestCookingFactoryEnergyDrain {

    private static final String COOKING_EXECUTOR =
            "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryExecutor.java";

    /**
     * 裸 JVM 里把原版注册表拉起来 —— 与 {@code TestMekCkInputSorting.boot()} 同一套两步。
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

    // ── 行为：产物装不下 ⇒ 判 false（canProcess 据此不扣电、点亮告警） ──

    /** 产物槽被<b>别的物品</b>占满 ⇒ 本批装不下。 */
    @Test
    public void fullProductSlotsRejectTheBatch() {
        List<IInventorySlot> outputs = List.of(new TestSlot(new ItemStack(Items.BREAD, 64), 64));
        assertFalse("产物槽满时 canProcess 必须判 false，否则进度条照走、电照扣、还不告警",
                CookingFactoryExecutor.canFitBatch(outputs, beef(1), 1));
    }

    /** 同类物品但剩余空间不够 ⇒ 装不下；正好够 ⇒ 装得下。 */
    @Test
    public void batchLargerThanRemainingSpaceIsRejected() {
        List<IInventorySlot> outputs = List.of(new TestSlot(beef(60), 64));
        assertFalse("只剩 4 个位置，批量 8 装不下",
                CookingFactoryExecutor.canFitBatch(outputs, beef(1), 8));
        assertTrue("批量 4 正好装得下",
                CookingFactoryExecutor.canFitBatch(outputs, beef(1), 4));
    }

    /** 空产物槽：按单槽容量判，超一个都不算装得下。 */
    @Test
    public void emptyProductSlotAcceptsUpToItsLimit() {
        List<IInventorySlot> outputs = List.of(new TestSlot(ItemStack.EMPTY, 64));
        assertTrue(CookingFactoryExecutor.canFitBatch(outputs, beef(1), 64));
        assertFalse("超过单槽容量就不算装得下",
                CookingFactoryExecutor.canFitBatch(outputs, beef(1), 65));
    }

    /** 退化输入（没有产物槽 / 配方无产物 / 批量 &le; 0）一律判 false，不抛异常。 */
    @Test
    public void degenerateInputsAreRejected() {
        List<IInventorySlot> outputs = List.of(new TestSlot(ItemStack.EMPTY, 64));
        assertFalse("没有产物槽", CookingFactoryExecutor.canFitBatch(List.of(), beef(1), 1));
        assertFalse("产物槽列表为 null", CookingFactoryExecutor.canFitBatch(null, beef(1), 1));
        assertFalse("配方无产物", CookingFactoryExecutor.canFitBatch(outputs, ItemStack.EMPTY, 1));
        assertFalse("批量 <= 0", CookingFactoryExecutor.canFitBatch(outputs, beef(1), 0));
    }

    // ── 接线：canProcess 与 run 必须共用同一判据 ────────────────────────

    /**
     * 源码不变量：{@code canProcess} 里必须有产物容量判定，且 {@code run} 与它共用
     * 同一个入口。
     *
     * <p>只钉 {@code canProcess} 不够：两处一旦各写一份，就会出现「预检说能加工、
     * 落槽时却装不下」的漂移 —— 那正是本仓库反复踩过的那类静默故障。</p>
     */
    @Test
    public void canProcessAndRunShareTheSameCapacityCheck() throws IOException {
        String src = TestSourceText.read(COOKING_EXECUTOR);
        String canProcess = TestSourceText.methodBody(
                src, "public boolean canProcess(MekCkMachineTile tile, int index)");
        String run = TestSourceText.methodBody(
                src, "private void run(Level level, Recipe<?> recipe, int batch, List<IInventorySlot> scan)");
        assertFalse("找不到 canProcess", canProcess.isEmpty());
        assertFalse("找不到 run", run.isEmpty());
        assertTrue("canProcess 必须做产物容量判定，否则产物满时仍扣电且无告警",
                canProcess.contains("canFitBatch("));
        assertTrue("run 必须与 canProcess 共用同一容量判据", run.contains("canFitBatch("));
    }
}
