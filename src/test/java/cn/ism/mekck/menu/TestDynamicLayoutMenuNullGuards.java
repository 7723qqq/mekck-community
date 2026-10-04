package cn.ism.mekck.menu;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M34 的<b>源码形态</b>护栏：制冰工厂 / 四合一基础机器的菜单布局随机器类型动态变化
 * （IceFactory：processes 按等级 + hasCreative；SimpleMachine：usesExtendedInputSlots() 按 kind + winery 分支），
 * 客户端 BE 为 null 时无法从 pos 推导布局。
 *
 * <h3>缺陷形态</h3>
 * 服务端发出 {@code ClientboundOpenScreenPacket} 之后、客户端处理它之前，方块被破坏/替换或区块卸载
 * ⇒ 客户端 {@code getBlockEntity} 返回 null。旧写法直接强转后交给主构造器，
 * 主构造器立刻读 {@code machine.getProcesses()} / {@code machine.getItems()} ⇒ NPE 崩客户端。
 *
 * <h3>修复口径</h3>
 * 服务端在 OpenScreen 附加数据里写「pos + 布局描述」，客户端构造器读取并交给<b>显式布局构造器</b>：
 * 槽位数量/坐标/处理器下标全部由布局描述推导，与 BE 的真实布局逐位一致（不得用「默认布局」近似）；
 * BE 为 null 时用等长空处理器兜底，槽位契约不变。服务端路径（{@code BE.createMenu} → 4 参构造器）
 * 保持不变，委托时用机器派生值。
 *
 * <p>菜单是客户端/服务端共用类，单测里起不了 Minecraft 运行时，所以只能钉源码形态；
 * 每条断言都要求真的匹配到东西（找不到方法/调用点即红），避免判据空转。</p>
 */
public class TestDynamicLayoutMenuNullGuards {

    private static final String ICE_BLOCK = "src/main/java/cn/ism/mekck/block/IceFactoryBlock.java";
    private static final String ICE_MENU = "src/main/java/cn/ism/mekck/menu/IceFactoryMenu.java";
    private static final String ICE_BE = "src/main/java/cn/ism/mekck/blockentity/IceFactoryBlockEntity.java";
    private static final String SIMPLE_BLOCK = "src/main/java/cn/ism/mekck/block/SimpleMachineBlock.java";
    private static final String SIMPLE_MENU = "src/main/java/cn/ism/mekck/menu/SimpleMachineMenu.java";

    private static final String ICE_CLIENT_CTOR =
            "public IceFactoryMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)";
    private static final String ICE_SERVER_CTOR =
            "public IceFactoryMenu(int containerId, Inventory inventory, IceFactoryBlockEntity machine, ContainerData data)";
    private static final String ICE_EXPLICIT_CTOR = "int processes, boolean hasCreative)";
    private static final String SIMPLE_CLIENT_CTOR =
            "public SimpleMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)";
    private static final String SIMPLE_SERVER_CTOR =
            "public SimpleMachineMenu(int containerId, Inventory inventory, SimpleMachineBlockEntity machine, ContainerData data)";
    private static final String SIMPLE_EXPLICIT_CTOR = "boolean extended, boolean winery)";

    // ── 1. 布局描述读写一致 ──────────────────────────────────────────

    /**
     * 制冰工厂：OpenScreen 写入的操作序列（pos / processes / hasCreative）必须与客户端构造器
     * 读取的操作序列逐项一致 —— 顺序错位会让客户端按错误的字段建槽。
     */
    @Test
    public void iceFactoryLayoutDescriptionRoundTrips() throws IOException {
        assertLayoutRoundTrip(ICE_BLOCK, ICE_MENU, ICE_CLIENT_CTOR);
        String block = TestSourceText.read(ICE_BLOCK);
        assertTrue("制冰工厂必须把 processes 写进布局描述",
                block.contains("buf.writeVarInt(machine.getProcesses())"));
        assertTrue("制冰工厂必须把 hasCreative 写进布局描述",
                block.contains("buf.writeBoolean(machine.CREATIVE_SLOT >= 0)"));
    }

    /** 四合一基础机器：pos / extended / winery 的写入与读取序列一致。 */
    @Test
    public void simpleMachineLayoutDescriptionRoundTrips() throws IOException {
        assertLayoutRoundTrip(SIMPLE_BLOCK, SIMPLE_MENU, SIMPLE_CLIENT_CTOR);
        String block = TestSourceText.read(SIMPLE_BLOCK);
        assertTrue("基础机器必须把扩展输入槽标志写进布局描述",
                block.contains("buf.writeBoolean(machine.usesExtendedInputSlots())"));
        assertTrue("基础机器必须把 winery 分支写进布局描述",
                block.contains("buf.writeBoolean(machine.getMachineKind() == MachineKind.WINERY)"));
        // 两个 bool 在字节流里同型，操作序列比较无法区分 extended / winery：
        // 语义配对由「写入表达式顺序（本断言）+ 显式构造器参数名与分支用法
        // （见 simpleMachineExplicitLayoutConstructorUsesOnlyExplicitLayout）」共同钉住。
        int extendedAt = block.indexOf("buf.writeBoolean(machine.usesExtendedInputSlots())");
        int wineryAt = block.indexOf("buf.writeBoolean(machine.getMachineKind() == MachineKind.WINERY)");
        assertTrue("extended 必须先于 winery 写入（与客户端读取顺序一致）",
                extendedAt >= 0 && wineryAt > extendedAt);
    }

    // ── 2. 客户端构造器判空 + 显式布局 ───────────────────────────────

    @Test
    public void iceFactoryClientConstructorResolvesBlockEntityNullSafely() throws IOException {
        String menu = TestSourceText.read(ICE_MENU);
        String ctor = TestSourceText.methodBody(menu, ICE_CLIENT_CTOR);
        assertFalse("找不到制冰工厂客户端构造器（判据失配）", ctor.isEmpty());
        assertTrue("客户端构造器必须用 instanceof 判空（不得直接强转）",
                ctor.contains("instanceof IceFactoryBlockEntity machine ? machine : null"));
        assertFalse("客户端构造器不得再直接强转 BE",
                ctor.contains("(IceFactoryBlockEntity) inventory.player.level().getBlockEntity"));
    }

    @Test
    public void simpleMachineClientConstructorResolvesBlockEntityNullSafely() throws IOException {
        String menu = TestSourceText.read(SIMPLE_MENU);
        String ctor = TestSourceText.methodBody(menu, SIMPLE_CLIENT_CTOR);
        assertFalse("找不到基础机器客户端构造器（判据失配）", ctor.isEmpty());
        assertTrue("客户端构造器必须用 instanceof 判空（不得直接强转）",
                ctor.contains("instanceof SimpleMachineBlockEntity machine ? machine : null"));
        assertFalse("客户端构造器不得再直接强转 BE",
                ctor.contains("(SimpleMachineBlockEntity) inventory.player.level().getBlockEntity"));
    }

    /**
     * 显式布局构造器不得再读 machine 的布局字段：布局只能来自显式参数，
     * 否则 BE 为 null 时又回到「无法推导布局」。
     */
    @Test
    public void iceFactoryExplicitLayoutConstructorUsesOnlyExplicitLayout() throws IOException {
        String menu = TestSourceText.read(ICE_MENU);
        String ctor = TestSourceText.methodBody(menu, ICE_EXPLICIT_CTOR);
        assertFalse("找不到制冰工厂显式布局构造器（判据失配）", ctor.isEmpty());
        for (String forbidden : new String[]{"machine.getProcesses()", "machine.CREATIVE_SLOT",
                "machine.SPEED_UPGRADE_SLOT", "machine.ENERGY_UPGRADE_SLOT", "machine.STACK_UPGRADE_SLOT",
                "machine.CB_SLOT_1", "machine.POWER_SLOT"}) {
            assertFalse("显式布局构造器不得读 " + forbidden + "（布局必须来自显式参数）", ctor.contains(forbidden));
        }
        assertTrue("BE 为 null 时必须用等长空处理器兜底", ctor.contains("machine != null ? machine.getItems()"));
        assertTrue("输入/输出槽必须按显式 processes 建", ctor.contains("for (int i = 0; i < processes; i++)"));
        assertTrue("创造槽必须按显式 hasCreative 建", ctor.contains("if (hasCreative)"));
    }

    @Test
    public void simpleMachineExplicitLayoutConstructorUsesOnlyExplicitLayout() throws IOException {
        String menu = TestSourceText.read(SIMPLE_MENU);
        String ctor = TestSourceText.methodBody(menu, SIMPLE_EXPLICIT_CTOR);
        assertFalse("找不到基础机器显式布局构造器（判据失配）", ctor.isEmpty());
        for (String forbidden : new String[]{"machine.usesExtendedInputSlots()", "machine.getMachineKind()"}) {
            assertFalse("显式布局构造器不得读 " + forbidden + "（布局必须来自显式参数）", ctor.contains(forbidden));
        }
        assertTrue("BE 为 null 时必须用等长空处理器兜底", ctor.contains("machine != null ? machine.getItems()"));
        assertTrue("扩展输入槽必须按显式 extended 建", ctor.contains("if (extended)"));
        assertTrue("陈酿机分支必须按显式 winery 建", ctor.contains("if (isWinery())"));
    }

    /** 服务端路径（BE.createMenu → 4 参构造器）保持不变：委托时用机器派生值。 */
    @Test
    public void serverConstructorsDelegateWithMachineDerivedLayout() throws IOException {
        String iceMenu = TestSourceText.read(ICE_MENU);
        String iceServer = TestSourceText.methodBody(iceMenu, ICE_SERVER_CTOR);
        assertFalse("找不到制冰工厂 4 参构造器（判据失配）", iceServer.isEmpty());
        assertTrue("制冰工厂 4 参构造器必须用 machine.getProcesses() 委托",
                iceServer.contains("machine.getProcesses()"));
        assertTrue("制冰工厂 4 参构造器必须用 machine.CREATIVE_SLOT 委托",
                iceServer.contains("machine.CREATIVE_SLOT >= 0"));

        String simpleMenu = TestSourceText.read(SIMPLE_MENU);
        String simpleServer = TestSourceText.methodBody(simpleMenu, SIMPLE_SERVER_CTOR);
        assertFalse("找不到基础机器 4 参构造器（判据失配）", simpleServer.isEmpty());
        assertTrue("基础机器 4 参构造器必须用 machine.usesExtendedInputSlots() 委托",
                simpleServer.contains("machine.usesExtendedInputSlots()"));
        assertTrue("基础机器 4 参构造器必须用 machine.getMachineKind() 委托",
                simpleServer.contains("machine.getMachineKind() == cn.ism.mekck.MachineKind.WINERY"));
    }

    // ── 3. 所有 BE 读取判空 ──────────────────────────────────────────

    @Test
    public void iceFactoryEveryMachineReadIsNullGuarded() throws IOException {
        String menu = TestSourceText.read(ICE_MENU);
        assertMachineReadsGuarded(ICE_MENU, menu,
                "public boolean stillValid(Player player)",
                "public int getEnergyCapacity()",
                "public int getWaterCapacity()",
                "public CuttingMachineFactoryTier getTier()",
                "public BlockPos getBlockPos()",
                "public void uninstallUpgrade(byte mode, int slot)",
                "public int getSpeedUpgradeMax()",
                "public int getEnergyUpgradeMax()");
    }

    @Test
    public void simpleMachineEveryMachineReadIsNullGuarded() throws IOException {
        String menu = TestSourceText.read(SIMPLE_MENU);
        assertMachineReadsGuarded(SIMPLE_MENU, menu,
                "public boolean stillValid(Player player)",
                "public int getEnergyPerTick()",
                "public boolean isHeatingMachine()",
                "public int getFluidCapacity()",
                "public BlockPos getBlockPos()",
                "public void uninstallUpgrade(byte mode, int slot)");
        String autoPull = TestSourceText.methodBody(menu, "public boolean supportsAutoPull()");
        assertTrue("supportsAutoPull 必须先判空再 instanceof",
                autoPull.contains("machine != null && machine instanceof"));
        // 布局分支改为读显式布局描述，不得再读 BE
        String isWinery = TestSourceText.methodBody(menu, "public boolean isWinery()");
        assertFalse("isWinery 必须读布局描述而不是 BE", isWinery.contains("machine."));
        String usesExtended = TestSourceText.methodBody(menu, "public boolean usesExtendedSlots()");
        assertFalse("usesExtendedSlots 必须读布局描述而不是 BE", usesExtended.contains("machine."));
    }

    // ── 4. 布局公式与 BE 一致 ────────────────────────────────────────

    /**
     * 显式布局构造器推导的处理器下标必须与 BE 的公式同形 —— 客户端槽位下标一旦与
     * 服务端错位，同步包会把物品放进错误的格。
     */
    @Test
    public void iceFactoryDerivedSlotIndicesMatchBlockEntityFormula() throws IOException {
        String be = TestSourceText.read(ICE_BE);
        for (String line : new String[]{
                "this.INPUT_SLOTS = processes;",
                "this.OUTPUT_SLOTS = processes;",
                "this.base = INPUT_SLOTS + OUTPUT_SLOTS;",
                "this.SPEED_UPGRADE_SLOT = base;",
                "this.ENERGY_UPGRADE_SLOT = base + 1;",
                "this.STACK_UPGRADE_SLOT = base + 2;",
                "this.CREATIVE_SLOT = base + 3;",
                "int cbStart = base + 4;",
                "this.POWER_SLOT = cbStart + 5;",
                "this.TOTAL_SLOTS = cbStart + 6;"}) {
            assertTrue("BE 布局公式变了（判据失配，需同步菜单推导）：" + line, be.contains(line));
        }
        String menu = TestSourceText.read(ICE_MENU);
        String ctor = TestSourceText.methodBody(menu, ICE_EXPLICIT_CTOR);
        for (String line : new String[]{
                "int base = processes * 2;",
                "int speedSlot = base;",
                "int energySlot = base + 1;",
                "int stackSlot = base + 2;",
                "int creativeSlot = base + 3;",
                "int cbStart = base + 4;",
                "int powerSlot = cbStart + 5;",
                "new ItemStackHandler(cbStart + 6)"}) {
            assertTrue("菜单推导必须与 BE 公式同形：" + line, ctor.contains(line));
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    private static void assertLayoutRoundTrip(String blockPath, String menuPath, String ctorSignature) throws IOException {
        List<String> written = writes(TestSourceText.read(blockPath));
        String ctor = TestSourceText.methodBody(TestSourceText.read(menuPath), ctorSignature);
        assertFalse("找不到客户端构造器 " + ctorSignature + "（判据失配）", ctor.isEmpty());
        List<String> read = ops(ctor, "read");
        assertEquals("布局描述写入与读取的操作序列必须一致（写 " + written + " / 读 " + read + "）",
                written, read);
        assertTrue("布局描述至少要有 pos + 一个布局字段", written.size() >= 2);
        assertEquals("第一个操作必须是 BlockPos（客户端先按 pos 找 BE）", "BlockPos", written.get(0));
    }

    /** 从方块源码的 openScreen 调用里按顺序取出 writeXxx 操作名。 */
    private static List<String> writes(String blockSource) {
        int at = blockSource.indexOf("NetworkHooks.openScreen(");
        assertTrue("找不到 NetworkHooks.openScreen 调用（判据失配）", at >= 0);
        int end = blockSource.indexOf("});", at);
        assertTrue("openScreen 调用没有 lambda 体（判据失配）", end > at);
        return ops(blockSource.substring(at, end), "write");
    }

    /** 按顺序取出 readXxx / writeXxx 操作名。 */
    private static List<String> ops(String text, String prefix) {
        Matcher m = Pattern.compile("\\b" + prefix + "(BlockPos|VarInt|Boolean|Utf|Int|Byte|Short)\\b")
                .matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static void assertMachineReadsGuarded(String path, String source, String... signatures) {
        for (String signature : signatures) {
            String body = TestSourceText.methodBody(source, signature);
            assertFalse(path + "：找不到方法 " + signature + "（判据失配，需同步更新本护栏）", body.isEmpty());
            if (!body.contains("machine.")) {
                continue;
            }
            assertTrue(path + "：" + signature + " 读 machine 必须判空（machine == null / machine != null）",
                    body.contains("machine == null") || body.contains("machine != null"));
        }
    }
}
