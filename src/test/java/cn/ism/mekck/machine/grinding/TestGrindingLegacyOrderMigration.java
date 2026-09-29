package cn.ism.mekck.machine.grinding;

import cn.ism.mekck.machine.MekCkLegacyMachineNbt;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 旧研磨工厂存档的<b>订单三键</b> → 新执行器子标签的翻译测试。
 *
 * <h3>为什么单独立一个类而不是并进 {@code TestLegacyMachineNbtMigration}</h3>
 * 那 771 行是<b>切菜</b>旧存档的形状样本（实测：旧的
 * {@code CuttingMachineFactoryBlockEntity.saveAdditional} 里根本没有订单键）。
 * 订单键只有研磨工厂写过，塞进去会让「这是切菜的样本」这句话当场失效。
 * 这里只断言订单这一条线，其余键的翻译仍由那个类守着。
 *
 * <h3>不搬会怎样</h3>
 * 新格式里订单归 {@link MekCkRecipeExecutor} 所有，而基类只把
 * {@code tag.getCompound("mekckExecutor")} 交给执行器——根标签它根本不看。
 * 于是不搬的话，玩家在旧存档里下的那一单会在换机器那一刻静默消失：
 * 机器照常加工（订单门禁失效），但玩家以为自己还锁着配方。
 * 不报错、不留日志。
 */
public class TestGrindingLegacyOrderMigration {

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    /** 旧研磨工厂的槽位排布：2 输入 + 2 输出 + 3 张升级卡 + 1 个能源槽。 */
    private static CompoundTag legacyWithOrder(String recipeId, int quantity, int completed) {
        CompoundTag root = new CompoundTag();
        root.putString("id", "mekck:basic_grinding_factory");
        root.putString(MekCkLegacyMachineNbt.LEGACY_ORDER_RECIPE, recipeId);
        root.putInt(MekCkLegacyMachineNbt.LEGACY_ORDER_QUANTITY, quantity);
        root.putInt(MekCkLegacyMachineNbt.LEGACY_ORDER_COMPLETED, completed);
        return root;
    }

    private static CompoundTag executorTagOf(CompoundTag migrated) {
        return migrated.getCompound(MekCkLegacyMachineNbt.NATIVE_EXECUTOR_TAG);
    }

    /** 三个旧键必须整体搬进 {@code mekckExecutor} 子标签，且换成新词表。 */
    @Test
    public void legacyOrderMovesIntoTheExecutorSubTag() {
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(
                legacyWithOrder("kaleidoscope_cookery:millstone", 6, 2), Direction.NORTH, 2);

        CompoundTag executor = executorTagOf(migrated);
        assertTrue("执行器子标签必须被创建", migrated.contains(MekCkLegacyMachineNbt.NATIVE_EXECUTOR_TAG, Tag.TAG_COMPOUND));
        assertEquals("kaleidoscope_cookery:millstone",
                executor.getString(GrindingFactoryExecutor.TAG_ORDER_RECIPE));
        assertEquals(6, executor.getInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY));
        assertEquals(2, executor.getInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED));
    }

    /**
     * 翻译完的旧键必须从根标签消失。
     *
     * <p>留着任何一个都意味着「两套格式并存」：下一次 {@code save} 写回的是新格式，
     * 而某个下次的 bug 却能同时被旧键影响。订单是这里最危险的一个——
     * 旧键在根、新键在子标签，两边同名的地方最容易读错。</p>
     */
    @Test
    public void legacyOrderKeysDisappearFromTheRoot() {
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(
                legacyWithOrder("mekck:grinding", 3, 0), Direction.NORTH, 2);

        assertFalse(migrated.contains(MekCkLegacyMachineNbt.LEGACY_ORDER_RECIPE));
        assertFalse(migrated.contains(MekCkLegacyMachineNbt.LEGACY_ORDER_QUANTITY));
        assertFalse(migrated.contains(MekCkLegacyMachineNbt.LEGACY_ORDER_COMPLETED));
    }

    /**
     * 旧存档没有订单时<b>不创建</b>空的执行器子标签。
     *
     * <p>凭空造一个空 CompoundTag 会让「这台机器下过单」与「这个键是空」再也分不开，
     * 而 {@code MekCkRecipeExecutor#load} 对空标签的语义恰恰是「清掉旧订单」——
     * 两者叠起来，每次读档都在赌一把。</p>
     */
    @Test
    public void noLegacyOrderMeansNoExecutorSubTagIsCreated() {
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "mekck:basic_grinding_factory");
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(legacy, Direction.NORTH, 2);
        assertFalse(migrated.contains(MekCkLegacyMachineNbt.NATIVE_EXECUTOR_TAG, Tag.TAG_COMPOUND));
    }

    /** 旧存档只有订单、没有槽位/能量/侧配时也要照搬（这几段互相独立）。 */
    @Test
    public void legacyOrderSurvivesWithoutTheOtherLegacyBlocks() {
        CompoundTag legacy = legacyWithOrder("kaleidoscope_cookery:millstone", 1, 0);
        assertFalse(legacy.contains("Items"));
        assertFalse(legacy.contains("Energy"));
        assertFalse(legacy.contains("SideConfig"));

        CompoundTag executor = executorTagOf(MekCkLegacyMachineNbt.migrate(legacy, Direction.NORTH, 2));
        assertEquals("kaleidoscope_cookery:millstone",
                executor.getString(GrindingFactoryExecutor.TAG_ORDER_RECIPE));
        assertEquals(1, executor.getInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY));
    }

    /** 负的份数 / 完成数在迁移这一层就夹到 0，不把非法值带进新格式。 */
    @Test
    public void negativeLegacyOrderNumbersAreClampedDuringMigration() {
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(
                legacyWithOrder("mekck:grinding", -4, -9), Direction.NORTH, 2);
        CompoundTag executor = executorTagOf(migrated);
        assertEquals(0, executor.getInt(GrindingFactoryExecutor.TAG_ORDER_QUANTITY));
        assertEquals(0, executor.getInt(GrindingFactoryExecutor.TAG_ORDER_COMPLETED));
    }

    /**
     * 迁移后的标签喂给执行器，必须真的还原出那一单。
     *
     * <p>这是整条链的<b>端到端</b>断言：{@code migrate} 的产物就是
     * {@code MekCkMachineTile.load} 最后交给 {@code executor().load(...)} 的那一份，
     * 两步之间没有别的转换。</p>
     */
    @Test
    public void theMigratedTagActuallyRestoresTheOrder() {
        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(
                legacyWithOrder("kaleidoscope_cookery:millstone", 5, 3), Direction.NORTH, 2);

        GrindingFactoryExecutor executor = new GrindingFactoryExecutor();
        executor.load(executorTagOf(migrated));

        assertEquals(new net.minecraft.resources.ResourceLocation("kaleidoscope_cookery", "millstone"),
                executor.getOrderRecipeId());
        assertEquals(5, executor.getOrderQuantity());
        assertEquals(3, executor.getOrderCompleted());
    }

    /** 迁移是纯函数：不得就地改传入的标签（Mek 读档链路上同一份还带着 ForgeCaps）。 */
    @Test
    public void migrationDoesNotMutateTheLegacyTag() {
        CompoundTag legacy = legacyWithOrder("mekck:grinding", 2, 1);
        CompoundTag before = legacy.copy();
        MekCkLegacyMachineNbt.migrate(legacy, Direction.NORTH, 2);
        assertEquals("传入的旧标签必须原封不动", before, legacy);
    }

    /**
     * 迁移只换位置：键名逐字不变，但旧键必须从根标签消失。
     *
     * <p>两条合起来才是完整的不变式：旧词表→新词表是“同名换位置”。
     * 只满足其中一半都有误：只改名字会让读侧找不到旧数据（订单静默丢失），
     * 只换位置而留着根键则会让同一份数据存两份。</p>
     */
    @Test
    public void theOnlyChangeIsTheTagLocation() {
        assertEquals("迁移只换位置，键名不变",
                MekCkLegacyMachineNbt.LEGACY_ORDER_RECIPE, GrindingFactoryExecutor.TAG_ORDER_RECIPE);
        assertEquals(MekCkLegacyMachineNbt.LEGACY_ORDER_QUANTITY, GrindingFactoryExecutor.TAG_ORDER_QUANTITY);
        assertEquals(MekCkLegacyMachineNbt.LEGACY_ORDER_COMPLETED, GrindingFactoryExecutor.TAG_ORDER_COMPLETED);

        CompoundTag migrated = MekCkLegacyMachineNbt.migrate(
                legacyWithOrder("mekck:grinding", 1, 0), Direction.NORTH, 2);
        assertFalse("旧键必须从根标签消失",
                migrated.contains(MekCkLegacyMachineNbt.LEGACY_ORDER_RECIPE));
        assertTrue("同名的键必须出现在执行器子标签里",
                migrated.contains(MekCkLegacyMachineNbt.NATIVE_EXECUTOR_TAG, Tag.TAG_COMPOUND));
    }
}
