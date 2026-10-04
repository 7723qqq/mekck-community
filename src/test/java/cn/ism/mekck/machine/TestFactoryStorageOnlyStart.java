package cn.ism.mekck.machine;

import cn.ism.mekck.TestSourceText;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 烹饪 / 穿串工厂「材料只在存储区」时必须能开工 —— 相对旧实现的功能回归护栏。
 *
 * <h3>它钉的是哪个 bug</h3>
 * 这两家的槽位形态是「固定输入 + 存储缓冲」：烹饪 6 输入 + 144 存储、穿串 3 输入 + 81 存储，
 * 而<b>存储区才是真正的料仓</b>。旧实现的 {@code getMaxConsumableCount} 明确写着
 * 「先扫输入槽、再扫存储区」，所以材料全放存储区时旧机器照常开工。
 *
 * <p>迁到 Mek 原生体系后 {@code hasWorkToDo()} 写成
 * {@code hasOrder() && activeWorkSlots() > 0}，而基类的 {@code activeWorkSlots()} 只数
 * {@code inputSlots} —— 于是「材料只在存储区」的机器<b>永不启动</b>：进度条不动、
 * 不扣电、也不报错，而执行器的 {@code canProcess} 明明扫得到那些料
 * （它用的是 {@code ingredientSlots()} = 输入 + 存储）。</p>
 *
 * <p>修法是覆写 {@code activeWorkSlots()} 让它数 {@code ingredientSlots()}：
 * 门禁的形态（{@code hasOrder() && activeWorkSlots() > 0}）保持不变，
 * 由 {@code TestMekCkPersistedSlotCoverage.orderOnlyGateIsNotEnoughForCookingAndSkewering}
 * 钉住。</p>
 *
 * <h3>为什么护栏分两半</h3>
 * 真 tile 在裸 JVM 里造不出来（构造链要 {@code BlockEntityType} 与 Mek 的注册表），
 * 所以：
 * <ul>
 *   <li><b>行为断言</b>打在 {@link MekCkMachineTile#countNonEmpty} 上 —— 判据的全部输入
 *       就是一个槽列表，这里用假槽位构造「6 个输入槽全空 + 存储区有 1 个苹果」；</li>
 *   <li><b>接线断言</b>读源码：两个 tile 的 {@code activeWorkSlots()} 覆写必须数
 *       {@code ingredientSlots()}，且 {@code hasWorkToDo()} 仍要求
 *       {@code activeWorkSlots() > 0}。少了这一半，把覆写删掉时行为断言仍会全绿。</li>
 * </ul>
 */
public class TestFactoryStorageOnlyStart {

    private static final String COOKING_TILE =
            "src/main/java/cn/ism/mekck/machine/cooking/CookingFactoryTile.java";
    private static final String SKEWERING_TILE =
            "src/main/java/cn/ism/mekck/machine/skewering/SkeweringFactoryTile.java";

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

    /** 最小可用的 {@link IInventorySlot}：只做栈的读写，容量不参与本用例。 */
    private static final class TestSlot implements IInventorySlot {

        private ItemStack stack;

        TestSlot(ItemStack stack) {
            this.stack = stack;
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
            return Integer.MAX_VALUE;
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

    /**
     * 配料扫描集合：{@code inputs} 个空输入槽 + {@code storage} 格存储，
     * 存储区第 0 格放 {@code storageItem}（其余为空）。
     */
    private static List<IInventorySlot> ingredientSlots(int inputs, int storage, ItemStack storageItem) {
        List<IInventorySlot> scan = new ArrayList<>(inputs + storage);
        for (int i = 0; i < inputs; i++) {
            scan.add(new TestSlot(ItemStack.EMPTY));
        }
        for (int i = 0; i < storage; i++) {
            scan.add(new TestSlot(i == 0 ? storageItem : ItemStack.EMPTY));
        }
        return scan;
    }

    // ── 行为：判据必须看得见存储区 ──────────────────────────────────────

    /**
     * 6 个输入槽全空、存储区里只有 1 个苹果 ⇒ <b>有活干</b>。
     *
     * <p>旧判据（只数输入槽）在这里返回 0，机器永不启动 —— 这正是要修的回归。</p>
     */
    @Test
    public void storageOnlyMaterialsCountAsWork() {
        List<IInventorySlot> scan = ingredientSlots(6, 144, new ItemStack(Items.APPLE, 1));
        assertTrue("材料只在存储区时也必须算「有活干」，否则机器永不启动",
                MekCkMachineTile.countNonEmpty(scan) > 0);
    }

    /** 输入槽与存储区全空 ⇒ 没活干（不能把「空机器」也放行）。 */
    @Test
    public void allEmptyMeansNoWork() {
        assertFalse(MekCkMachineTile.countNonEmpty(ingredientSlots(6, 144, ItemStack.EMPTY)) > 0);
    }

    /** 输入槽里有料 ⇒ 有活干（原有行为不能丢）。 */
    @Test
    public void inputSlotMaterialsStillCountAsWork() {
        List<IInventorySlot> scan = ingredientSlots(6, 144, ItemStack.EMPTY);
        scan.get(0).setStack(new ItemStack(Items.APPLE, 1));
        assertTrue(MekCkMachineTile.countNonEmpty(scan) > 0);
    }

    /** 空列表 / null 一律判「没活干」，不抛异常。 */
    @Test
    public void degenerateListsMeanNoWork() {
        assertFalse(MekCkMachineTile.countNonEmpty(List.of()) > 0);
        assertFalse(MekCkMachineTile.countNonEmpty(null) > 0);
    }

    // ── 接线：两个 tile 的 activeWorkSlots 必须数 ingredientSlots() ────

    /**
     * 源码不变量：{@code activeWorkSlots()} 覆写里必须出现 {@code ingredientSlots()}，
     * 且 {@code hasWorkToDo()} 仍要求 {@code activeWorkSlots() > 0}。
     *
     * <p>行为断言打不到 tile 的接线（真 tile 造不出来），这一条补上那一环：
     * 谁把覆写删掉、或把门禁退化成只判订单，这里先红。</p>
     */
    @Test
    public void bothStorageFamiliesCountIngredientsAsActiveSlots() throws IOException {
        for (String path : List.of(COOKING_TILE, SKEWERING_TILE)) {
            String src = TestSourceText.read(path);
            String active = TestSourceText.methodBody(src, "protected int activeWorkSlots()");
            assertFalse(path + " 里找不到 activeWorkSlots() 覆写", active.isEmpty());
            assertTrue(path + " 的 activeWorkSlots() 必须数 ingredientSlots()（输入 + 存储）",
                    active.contains("ingredientSlots()"));

            String gate = TestSourceText.methodBody(src, "protected boolean hasWorkToDo()");
            assertFalse(path + " 里找不到 hasWorkToDo()", gate.isEmpty());
            assertTrue(path + " 的门禁必须仍然要求「有活干」",
                    gate.contains("activeWorkSlots() > 0"));
        }
    }
}
