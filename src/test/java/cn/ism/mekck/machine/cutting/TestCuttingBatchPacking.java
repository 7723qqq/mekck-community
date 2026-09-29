package cn.ism.mekck.machine.cutting;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 切菜批量执行里<b>不依赖 Farmer's Delight</b>的那一半 —— 真行为测试。
 *
 * <h3>为什么这一半能跑真行为测试</h3>
 * 产出容量判定与产物落槽只用到 {@link ItemStack} 与 {@link IInventorySlot}，
 * 两者都不沾 Farmer's Delight。裸 JVM 里把它们跑起来只需要两步（见 {@link #boot()}）：
 * <pre>
 *   1. net.minecraft.SharedConstants.tryDetectVersion();
 *   2. net.minecraft.server.Bootstrap.bootStrap();
 * </pre>
 * 第 2 步会在 Forge 网络钩子阶段抛
 * {@code NoSuchMethodException: net.minecraftforge.network.NetworkEvent.&lt;init&gt;()}
 * （测试 classpath 上的 Minecraft 没经 Forge 的 mixin/ASM 处理），
 * 但 {@code BuiltInRegistries} 与原版物品此时已注册完毕，<b>吞掉这个异常即可</b>。
 * 实测吞掉之后 {@code ItemStack} / {@code Items} / {@code ForgeRegistries} 全部可用。
 *
 * <p>另一半（配方匹配）做不到，原因见
 * {@code cn.ism.mekck.factory.TestCuttingRecipeInvariants} 的类注释：
 * {@code CuttingBoardRecipe} 在测试期是 raw 未重映射 jar，字节码里 MC 成员仍是 SRG 名
 * （{@code f_41583_} / {@code m_7983_}），构造即 {@code NoSuchFieldError}。
 * 本类与那个类合起来覆盖执行器的全部逻辑。</p>
 *
 * <h3>本测试直接调用生产代码，不是复刻</h3>
 * 被测的 {@code canFitAll} / {@code insertOutput} / {@code stackMultiplier} 是
 * {@link CuttingFactoryExecutor} 上的包级 {@code static} 方法。构造一台真的机器需要
 * {@code BlockEntityType} 注册表（{@code UniversalCuttingMachine.FACTORY_BLOCK_ENTITIES
 * .get(tier).get()} 在裸 JVM 里是 {@code NullPointerException: Registry Object not present}），
 * 所以把纯逻辑做成静态入口，而不是在测试里抄一遍。
 * 本类与被测类同包，正是为了让 {@code OUTPUT_SLOT_CAPACITY} 这个包级常量可见。
 */
public class TestCuttingBatchPacking {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    /**
     * 最小可用的 {@link IInventorySlot}。
     *
     * <p>{@code getLimit} 返回什么不影响被测逻辑：产出容量在本执行器里由
     * {@link CuttingFactoryExecutor#OUTPUT_SLOT_CAPACITY} 统一决定，
     * 不问槽自己的上限（原因见该常量的注释）。</p>
     */
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
            return CuttingFactoryExecutor.OUTPUT_SLOT_CAPACITY;
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

    private static List<IInventorySlot> outputs(int count) {
        List<IInventorySlot> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new TestSlot(ItemStack.EMPTY));
        }
        return list;
    }

    private static List<ItemStack> results(ItemStack... stacks) {
        return List.of(stacks);
    }

    // ── canFitAll ───────────────────────────────────────────────────────

    /** 空产出区：任何批量都放得下。 */
    @Test
    public void emptyOutputsAlwaysFit() {
        assertTrue(CuttingFactoryExecutor.canFitAll(outputs(3), results(new ItemStack(Items.BREAD, 7)), 64));
    }

    /**
     * 单个产出槽能吃下远超 64 的量 —— 这正是 {@code OUTPUT_SLOT_CAPACITY}
     * 存在的原因：改用 Mek 的 {@code getLimit(stack)} 会截到
     * {@code min(limit, getMaxStackSize())}，81 并行工厂会静默变成「装不下」而降速。
     */
    @Test
    public void oneOutputSlotAbsorbsFarMoreThanAStackLimit() {
        assertTrue("单槽必须能吃下 1000 个（远超 64）",
                CuttingFactoryExecutor.canFitAll(outputs(1), results(new ItemStack(Items.BREAD, 1)), 1000));
    }

    /**
     * NBT 不同的同种物品不得并进同一个槽。
     *
     * <p>只用<b>一个</b>产出槽：槽数够多时它会被顺到后面的空槽里，判 true 才是正确行为
     * （与旧实现一致）。要让「串味」显形，必须让它无处可去。</p>
     */
    @Test
    public void sameItemDifferentTagsDoesNotShareASlot() {
        ItemStack plain = new ItemStack(Items.BREAD, 10);
        ItemStack named = new ItemStack(Items.BREAD, 10);
        named.getOrCreateTag().putString("k", "v");

        List<IInventorySlot> out = outputs(1);
        out.get(0).setStack(plain);

        assertFalse("NBT 不同的同种物品不得并进同一槽",
                CuttingFactoryExecutor.canFitAll(out, results(named), 64));
    }

    /** 同物同 NBT 且总量不超上限时判 true（上面那条的正面对照）。 */
    @Test
    public void sameItemSameTagsFitsIntoTheExistingSlot() {
        List<IInventorySlot> out = outputs(1);
        out.get(0).setStack(new ItemStack(Items.BREAD, 10));
        assertTrue("同物同 NBT 应当并入已有槽",
                CuttingFactoryExecutor.canFitAll(out, results(new ItemStack(Items.BREAD, 2)), 5));
    }

    /** 槽数够多时，NBT 不同的同种产物顺到后面的空槽——这是正确行为，不是缺陷。 */
    @Test
    public void mismatchedTagsSpillIntoTheNextEmptySlot() {
        ItemStack plain = new ItemStack(Items.BREAD, 10);
        ItemStack named = new ItemStack(Items.BREAD, 10);
        named.getOrCreateTag().putString("k", "v");

        List<IInventorySlot> out = outputs(2);
        out.get(0).setStack(plain);
        assertTrue("有第二个空槽时应顺延而不是判装不下",
                CuttingFactoryExecutor.canFitAll(out, results(named), 64));
    }

    /** 不同物品必须各占一槽，槽不够就判 false。 */
    @Test
    public void differentItemsNeedOneSlotEach() {
        List<ItemStack> twoKinds = results(new ItemStack(Items.BREAD, 1), new ItemStack(Items.CARROT, 1));
        assertTrue("两个槽装两种产物，应当放得下",
                CuttingFactoryExecutor.canFitAll(outputs(2), twoKinds, 3));
        assertFalse("一个槽装不下两种产物",
                CuttingFactoryExecutor.canFitAll(outputs(1), twoKinds, 3));
    }

    /** 空结果与 null 结果都必须被跳过，不能判成「装不下」。 */
    @Test
    public void emptyAndNullResultsAreSkipped() {
        List<ItemStack> withHoles = new ArrayList<>();
        withHoles.add(null);
        withHoles.add(ItemStack.EMPTY);
        withHoles.add(new ItemStack(Items.BREAD, 1));
        assertTrue(CuttingFactoryExecutor.canFitAll(outputs(1), withHoles, 5));
    }

    /**
     * 数量乘出来超过 {@code int} 上界时判装不下 —— 旧实现的溢出保护。
     *
     * <p>不在这里拦，后面 {@code multiplied.setCount((int) 溢出值)} 会造出
     * 数量为负的 ItemStack，那是「凭空造物品/凭空吞物品」级别的故障。</p>
     */
    @Test
    public void overflowingOutputCountIsRejected() {
        List<ItemStack> huge = results(new ItemStack(Items.BREAD, Integer.MAX_VALUE));
        assertFalse("result.getCount() * multiplier 溢出 int 时必须判装不下",
                CuttingFactoryExecutor.canFitAll(outputs(64), huge, 64));
    }

    /** 判定过程不得改动产出槽本身（它是纯预演，真正落槽在 insertOutput）。 */
    @Test
    public void capacityCheckDoesNotMutateOutputSlots() {
        ItemStack existing = new ItemStack(Items.BREAD, 5);
        List<IInventorySlot> out = outputs(2);
        out.get(0).setStack(existing);
        CuttingFactoryExecutor.canFitAll(out, results(new ItemStack(Items.BREAD, 3)), 4);
        assertSame("canFitAll 不得替换产出槽里的 ItemStack 实例", existing, out.get(0).getStack());
        assertEquals(5, out.get(0).getStack().getCount());
    }

    // ── insertOutput ────────────────────────────────────────────────────

    @Test
    public void insertOutputStacksIntoTheFirstEmptySlot() {
        List<IInventorySlot> out = outputs(3);
        ItemStack payload = new ItemStack(Items.BREAD, 12);
        CuttingFactoryExecutor.insertOutput(out, payload);
        assertEquals(12, out.get(0).getStack().getCount());
        assertTrue("只用掉第一个空槽", out.get(1).getStack().isEmpty() && out.get(2).getStack().isEmpty());
        assertTrue("传入的栈必须被消耗干净", payload.isEmpty());
    }

    @Test
    public void insertOutputMergesIntoAMatchingSlotFirst() {
        List<IInventorySlot> out = outputs(3);
        out.get(0).setStack(new ItemStack(Items.BREAD, 10));
        CuttingFactoryExecutor.insertOutput(out, new ItemStack(Items.BREAD, 7));
        assertEquals(17, out.get(0).getStack().getCount());
        assertTrue("同物应并入已有槽而不是新开一槽", out.get(1).getStack().isEmpty());
    }

    @Test
    public void insertOutputMovesOnWhenTheFirstSlotHoldsAnotherItem() {
        List<IInventorySlot> out = outputs(3);
        out.get(0).setStack(new ItemStack(Items.CARROT, 10));
        CuttingFactoryExecutor.insertOutput(out, new ItemStack(Items.BREAD, 3));
        assertEquals(Items.CARROT, out.get(0).getStack().getItem());
        assertEquals(10, out.get(0).getStack().getCount());
        assertEquals(Items.BREAD, out.get(1).getStack().getItem());
        assertEquals(3, out.get(1).getStack().getCount());
    }

    /** 无槽可用时不得凭空丢产物——由 canFitAll 提前拦下，这里只保证不写坏任何槽。 */
    @Test
    public void insertOutputDropsNothingWhenThereAreNoSlots() {
        ItemStack payload = new ItemStack(Items.BREAD, 5);
        CuttingFactoryExecutor.insertOutput(outputs(0), payload);
        assertEquals(5, payload.getCount());
    }

    // ── stackMultiplier ─────────────────────────────────────────────────

    /** 没有存储卡（或本档不接受）⇒ 倍增为 1。 */
    @Test
    public void noStorageUpgradeMeansNoMultiplier() {
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(0, 0, 3, 96));
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(4, 0, 3, 96));
    }

    /** 倍增是 2 的幂。 */
    @Test
    public void multiplierIsAPowerOfTwo() {
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(0, 6, 3, 9999));
        assertEquals(2, CuttingFactoryExecutor.stackMultiplier(1, 6, 3, 9999));
        assertEquals(4, CuttingFactoryExecutor.stackMultiplier(2, 6, 3, 9999));
        assertEquals(8, CuttingFactoryExecutor.stackMultiplier(3, 6, 3, 9999));
    }

    /** 已安装数超过本档上限时按上限截断，不会无限叠。 */
    @Test
    public void installedCountIsClampedByTheCap() {
        assertEquals("装 9 张、上限 3 ⇒ 与装 3 张同倍增",
                CuttingFactoryExecutor.stackMultiplier(3, 3, 3, 9999),
                CuttingFactoryExecutor.stackMultiplier(9, 3, 3, 9999));
    }

    /** 基础并行已经追平配置上限时不再叠乘。 */
    @Test
    public void baseAlreadyAtMaxMeansNoMultiplier() {
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(6, 6, 100, 100));
    }

    /** 「基础并行 × 倍增 ≤ 配置最大并行」这条封顶必须生效。 */
    @Test
    public void multiplierIsCappedByMaxParallelOverBase() {
        // maxMult = 96 / 3 = 32 ⇒ 2^5 封顶，装 6 张也只能到 32。
        assertEquals(32, CuttingFactoryExecutor.stackMultiplier(6, 6, 3, 96));
        // 封顶值至少为 1：maxParallel < base 时不能返回 0 或负数。
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(6, 6, 200, 100));
    }

    /**
     * 上限 &gt; 30 时不得让移位绕回。
     *
     * <p>Java 的 {@code 1 << n} 按 {@code n mod 32} 计算：{@code 1 << 31} 是
     * {@link Integer#MIN_VALUE}（负数），{@code 1 << 32} 绕回 {@code 1}——
     * 后者会让「装满卡」静默变成「完全不倍增」。当前 {@code STORAGE.getMax()} 是 6，
     * 走不到这条分支，但这段算术已提成可单测的纯函数，将来上限调大时会需要这道钳。</p>
     */
    @Test
    public void shiftIsClampedBeforeItWrapsAround() {
        int at32 = CuttingFactoryExecutor.stackMultiplier(32, 32, 1, Integer.MAX_VALUE);
        int at30 = CuttingFactoryExecutor.stackMultiplier(30, 30, 1, Integer.MAX_VALUE);
        assertTrue("移位结果必须为正（1<<31 是负数）", at32 > 0);
        assertEquals("cap=32 与 cap=30 应当同结果（都被钳到 2^30）", at30, at32);
    }

    /** 基础并行被配成 0 时不得除零炸服。 */
    @Test
    public void zeroBaseDoesNotDivideByZero() {
        assertEquals(1, CuttingFactoryExecutor.stackMultiplier(6, 6, 0, 96));
    }
}
