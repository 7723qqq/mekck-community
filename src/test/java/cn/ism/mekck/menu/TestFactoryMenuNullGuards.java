package cn.ism.mekck.menu;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M33 的<b>源码形态</b>护栏：电力研磨机 / 种植切配工厂两个菜单的客户端构造器与读取路径
 * 对 {@code machine == null} 兜底（M25 口径）。
 *
 * <h3>缺陷形态</h3>
 * 两个菜单的 {@code (int, Inventory, FriendlyByteBuf)} 构造器把 {@code getBlockEntity}
 * 的结果直接强转后交给主构造器，主构造器随即读 {@code machine.getItems()} 建槽 ⇒
 * 服务端发出 {@code ClientboundOpenScreenPacket} 之后、客户端处理它之前，方块被破坏/替换
 * 或区块卸载（{@code getBlockEntity} 返回 null）时 NPE 崩客户端。
 *
 * <p>修法（同 M25 的 CentralKitchenMenu / SandwichAssemblerMenu）：客户端构造器用
 * {@code instanceof} 判空（同时挡掉类型不符），null 时构造「空菜单」——槽位数量与坐标
 * 照旧（客户端/服务端的槽位契约不能变），槽位绑定等长的空 {@code ItemStackHandler}，
 * 菜单内全部读取走 {@code machine == null} 兜底。</p>
 *
 * <p>菜单是服务端/客户端共用类，裸 JVM 里造不出来（构造链要 MenuType / BlockEntityType
 * 与 Mek 注册表），所以只能钉源码形态；每条断言都要求真的匹配到东西（找不到方法即红），
 * 避免判据空转。</p>
 */
public class TestFactoryMenuNullGuards {

    private static final String GRINDING =
            "src/main/java/cn/ism/mekck/menu/ElectricGrindingMachineMenu.java";
    private static final String PLANTING =
            "src/main/java/cn/ism/mekck/menu/PlantingCuttingStationMenu.java";

    // ================== 电力研磨机 ==================

    @Test
    public void electricGrindingClientConstructorNullChecksTheBlockEntity() throws IOException {
        String src = TestSourceText.read(GRINDING);
        String ctor = TestSourceText.methodBody(src,
                "public ElectricGrindingMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)");
        assertFalse(GRINDING + "：客户端构造器没找到，判据可能失配", ctor.isEmpty());
        assertTrue(GRINDING + "：客户端构造器必须对 getBlockEntity 的结果判空"
                        + "（instanceof 同时挡掉类型不符）：旧写法直接强转，BE 为 null 时"
                        + "主构造器读 machine.getItems() 崩客户端",
                ctor.contains("instanceof ElectricGrindingMachineBlockEntity machine ? machine : null"));
        assertFalse(GRINDING + "：客户端构造器不得再直接强转 getBlockEntity 的结果",
                ctor.contains("(ElectricGrindingMachineBlockEntity)"));
    }

    @Test
    public void electricGrindingMainConstructorBuildsSlotsFromFallbackHandler() throws IOException {
        String src = TestSourceText.read(GRINDING);
        String ctor = TestSourceText.methodBody(src,
                "public ElectricGrindingMachineMenu(int containerId, Inventory inventory,"
                        + " ElectricGrindingMachineBlockEntity machine, ContainerData data)");
        assertFalse(GRINDING + "：主构造器没找到，判据可能失配", ctor.isEmpty());
        assertTrue(GRINDING + "：主构造器必须给空菜单一个等长的兜底 handler（槽位数量契约不能变）",
                ctor.contains("new ItemStackHandler(ElectricGrindingMachineBlockEntity.TOTAL_SLOTS)"));
        assertTrue(GRINDING + "：兜底 handler 必须由 machine == null 分支选出",
                ctor.contains("machine == null"));
        for (String slot : new String[]{"InputSlot", "OutputSlot", "UpgradeSlot", "PowerSlot"}) {
            assertTrue(GRINDING + "：槽 " + slot + " 必须绑定兜底 handler（items），"
                            + "否则空菜单建槽时仍会解引用 machine",
                    ctor.contains("new " + slot + "(items,"));
        }
        assertTrue(GRINDING + "：能源槽下标必须对空菜单兜底（handler 常量与 getPowerSlot() 同值）",
                ctor.contains("machine == null ? ElectricGrindingMachineBlockEntity.SLOT_POWER"
                        + " : machine.getPowerSlot()"));
    }

    @Test
    public void electricGrindingReadPathsAreNullSafe() throws IOException {
        String src = TestSourceText.read(GRINDING);
        assertGuarded(src, GRINDING, "public boolean stillValid(Player player)", "machine == null");
        assertGuarded(src, GRINDING, "public int getSpeedUpgradeCount()", "machine == null");
        assertGuarded(src, GRINDING, "public int getEnergyUpgradeCount()", "machine == null");
        assertGuarded(src, GRINDING, "public BlockPos getBlockPos()", "BlockPos.ZERO");
    }

    // ================== 种植切配工厂 ==================

    @Test
    public void plantingCuttingStationClientConstructorNullChecksTheBlockEntity() throws IOException {
        String src = TestSourceText.read(PLANTING);
        String ctor = TestSourceText.methodBody(src,
                "public PlantingCuttingStationMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)");
        assertFalse(PLANTING + "：客户端构造器没找到，判据可能失配", ctor.isEmpty());
        assertTrue(PLANTING + "：客户端构造器必须对 getBlockEntity 的结果判空"
                        + "（instanceof 同时挡掉类型不符）：旧写法直接强转，BE 为 null 时"
                        + "主构造器读 machine.getItems() 崩客户端",
                ctor.contains("instanceof PlantingCuttingStationBlockEntity machine ? machine : null"));
        assertFalse(PLANTING + "：客户端构造器不得再直接强转 getBlockEntity 的结果",
                ctor.contains("(PlantingCuttingStationBlockEntity)"));
    }

    @Test
    public void plantingCuttingStationMainConstructorBuildsSlotsFromFallbackHandler() throws IOException {
        String src = TestSourceText.read(PLANTING);
        String ctor = TestSourceText.methodBody(src,
                "public PlantingCuttingStationMenu(int containerId, Inventory inventory,"
                        + " PlantingCuttingStationBlockEntity machine, ContainerData data)");
        assertFalse(PLANTING + "：主构造器没找到，判据可能失配", ctor.isEmpty());
        assertTrue(PLANTING + "：主构造器必须给空菜单一个等长的兜底 handler（槽位数量契约不能变）",
                ctor.contains("new ItemStackHandler(PlantingCuttingStationBlockEntity.TOTAL_SLOTS)"));
        assertTrue(PLANTING + "：兜底 handler 必须由 machine == null 分支选出",
                ctor.contains("machine == null"));
        for (String slot : new String[]{"InputSlot", "NutrientSlot", "OutputSlot", "UpgradeSlot",
                "PowerSlot", "GrowthSlot"}) {
            assertTrue(PLANTING + "：槽 " + slot + " 必须绑定兜底 handler（items），"
                            + "否则空菜单建槽时仍会解引用 machine",
                    ctor.contains("new " + slot + "(items,"));
        }
        assertTrue(PLANTING + "：能源槽下标必须对空菜单兜底（handler 常量与 getPowerSlot() 同值）",
                ctor.contains("machine == null ? PlantingCuttingStationBlockEntity.SLOT_POWER"
                        + " : machine.getPowerSlot()"));
    }

    @Test
    public void plantingCuttingStationReadPathsAreNullSafe() throws IOException {
        String src = TestSourceText.read(PLANTING);
        assertGuarded(src, PLANTING, "public boolean stillValid(Player player)", "machine == null");
        assertGuarded(src, PLANTING, "public ItemStack quickMoveStack(Player player, int index)", "machine == null");
        assertGuarded(src, PLANTING, "public int getSpeedUpgradeCount()", "machine == null");
        assertGuarded(src, PLANTING, "public int getEnergyUpgradeCount()", "machine == null");
        assertGuarded(src, PLANTING, "public boolean hasNutrient()", "machine != null");
        assertGuarded(src, PLANTING, "public int getGasUpgradeCount()", "machine == null");
        assertGuarded(src, PLANTING, "public BlockPos getBlockPos()", "BlockPos.ZERO");
    }

    // ================== 判据不许空转 ==================

    /** 确认上面的判据在本仓确实还能匹配到东西（防改名后静默恒真）。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        String grinding = TestSourceText.read(GRINDING);
        assertTrue(GRINDING + "：判据失效，已找不到 machine == null",
                grinding.contains("machine == null"));
        assertTrue(GRINDING + "：判据失效，已找不到 instanceof 判空",
                grinding.contains("instanceof ElectricGrindingMachineBlockEntity"));

        String planting = TestSourceText.read(PLANTING);
        assertTrue(PLANTING + "：判据失效，已找不到 machine == null",
                planting.contains("machine == null"));
        assertTrue(PLANTING + "：判据失效，已找不到 instanceof 判空",
                planting.contains("instanceof PlantingCuttingStationBlockEntity"));
    }

    /** 取方法体并断言其中出现兜底形态；找不到方法体直接红（判据失配不许静默通过）。 */
    private static void assertGuarded(String src, String path, String signature, String needle) {
        String body = TestSourceText.methodBody(src, signature);
        assertFalse(path + "：找不到方法 " + signature + "（判据失配，需同步更新本护栏）", body.isEmpty());
        assertTrue(path + "：" + signature + " 缺少兜底形态 " + needle, body.contains(needle));
    }
}
