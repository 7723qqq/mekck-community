package cn.ism.mekck.menu;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M32 的<b>源码形态</b>护栏：6 个独立机器菜单的客户端构造器与读取路径对 {@code machine} 判空。
 *
 * <h3>这个缺陷为什么必须用源码形态钉</h3>
 * 服务端发出 {@code ClientboundOpenScreenPacket} 之后、客户端处理它之前，方块被破坏/替换
 * 或区块卸载 ⇒ 客户端 {@code getBlockEntity} 返回 null。这 6 个菜单的客户端构造器
 * 旧写法把结果直接强转后交给主构造器，主构造器第一行 {@code machine.getItems()} 建槽
 * ⇒ NPE 崩客户端（菜单构造路径无 catch，屏幕根本不会被创建）。
 *
 * <p>M25 已给 {@code CentralKitchenMenu} / {@code SandwichAssemblerMenu} 立了「空菜单」口径：
 * {@code instanceof} 判空（同时挡掉类型不符）、空菜单用等长的空 handler 兜底
 * （槽位数量与坐标契约不变）、所有读取走 {@code machine == null} 分支。
 * 本护栏把这套口径钉在其余 6 个独立机器菜单上。</p>
 *
 * <p>菜单构造器依赖 Minecraft 运行时（{@code Inventory} / {@code Level}），单测里起不了，
 * 所以只能钉源码形态；每条断言都要求真的匹配到东西（找不到方法/调用点即红），
 * 避免判据空转。</p>
 */
public class TestMenuNullGuards {

    private static final String CHOCOLATE = "src/main/java/cn/ism/mekck/menu/ChocolateCannonMenu.java";
    private static final String COOKING_POT = "src/main/java/cn/ism/mekck/menu/SmartCookingPotMenu.java";
    private static final String SKEWERING = "src/main/java/cn/ism/mekck/menu/SkeweringMachineMenu.java";
    private static final String BIOREACTOR = "src/main/java/cn/ism/mekck/menu/BioreactorMenu.java";

    // 登记表 6 → 5（2026-10-06）：坚果爆炒机菜单随整机迁到 Mek 原生体系 ——
    // NutRoasterMenu 不再有自研的「客户端构造器 + 空菜单兜底 handler」这一对，
    // 而是 `extends MekanismTileContainer`（客户端构造由 Mek 的容器工厂承担：
    // 取不到 BE 时工厂直接抛「Missing tile」，不会构造出 machine == null 的空菜单）。
    // 判据的对象（可能为 null 的 machine 字段）在这台机器上不存在了 ——
    // 与 ElectricGrindingMachineMenu / WineCellarMenu / GrillMenu 迁走时同型，
    // **不是**为了让红灯变绿而放宽：其余 5 个菜单仍逐个断言两条判据，
    // 且 criteriaStillMatchSomething 的清单同步收缩并保留「至少一项」的防空转检查。
    //
    // 登记表 5 → 4（2026-10-06，急冻制冰机同批迁移）：IceMakerMenu 同型迁到
    // `MekanismTileContainer`，本文件对它的两条断言随之移出，理由与上段逐字相同。

    // ================== 客户端构造器：instanceof 判空 ==================

    @Test
    public void chocolateCannonClientConstructorNullChecksTheBlockEntity() throws IOException {
        assertClientCtorGuards(CHOCOLATE,
                "public ChocolateCannonMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)",
                "ChocolateCannonBlockEntity");
    }

    @Test
    public void cookingPotClientConstructorNullChecksTheBlockEntity() throws IOException {
        assertClientCtorGuards(COOKING_POT,
                "public SmartCookingPotMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)",
                "SmartCookingPotBlockEntity");
    }

    @Test
    public void skeweringClientConstructorNullChecksTheBlockEntity() throws IOException {
        assertClientCtorGuards(SKEWERING,
                "public SkeweringMachineMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)",
                "SkeweringMachineBlockEntity");
    }

    @Test
    public void bioreactorClientConstructorNullChecksTheBlockEntity() throws IOException {
        assertClientCtorGuards(BIOREACTOR,
                "public BioreactorMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer)",
                "BioreactorBlockEntity");
    }

    // ================== 主构造器：空菜单兜底 handler + 读取路径判空 ==================

    @Test
    public void chocolateCannonReadPathsAreNullSafe() throws IOException {
        assertReadPathsGuarded(CHOCOLATE,
                "public ChocolateCannonMenu(int containerId, Inventory inventory,"
                        + " ChocolateCannonBlockEntity machine, ContainerData data)",
                "ChocolateCannonBlockEntity.TOTAL_SLOTS",
                "public boolean stillValid(Player player)",
                "public BlockPos getBlockPos()",
                "public void uninstallUpgrade(byte mode, int slot)");
    }

    @Test
    public void cookingPotReadPathsAreNullSafe() throws IOException {
        assertReadPathsGuarded(COOKING_POT,
                "public SmartCookingPotMenu(int containerId, Inventory inventory,"
                        + " SmartCookingPotBlockEntity machine, ContainerData data)",
                "SmartCookingPotBlockEntity.TOTAL_SLOTS",
                "public boolean stillValid(Player player)",
                "public int getSpeedUpgradeCount()",
                "public int getEnergyUpgradeCount()",
                "public ResourceLocation getOrderRecipeId()",
                "public int getMaxOrderQuantity()",
                "public BlockPos getBlockPos()");
    }

    @Test
    public void skeweringReadPathsAreNullSafe() throws IOException {
        assertReadPathsGuarded(SKEWERING,
                "public SkeweringMachineMenu(int containerId, Inventory inventory,"
                        + " SkeweringMachineBlockEntity machine, ContainerData data)",
                "SkeweringMachineBlockEntity.TOTAL_SLOTS",
                "public boolean stillValid(Player player)",
                "public int getSpeedUpgradeCount()",
                "public int getEnergyUpgradeCount()",
                "public ResourceLocation getOrderRecipeId()",
                "public int getMaxOrderQuantity()",
                "public BlockPos getBlockPos()");
    }

    @Test
    public void bioreactorReadPathsAreNullSafe() throws IOException {
        assertReadPathsGuarded(BIOREACTOR,
                "public BioreactorMenu(int containerId, Inventory inventory,"
                        + " BioreactorBlockEntity machine, ContainerData data)",
                "BioreactorBlockEntity.TOTAL_SLOTS",
                "public boolean stillValid(Player player)",
                "public BlockPos getBlockPos()");
    }

    /**
     * 生物反应堆的 3 参构造器（客户端构造器与 BE 的 MenuProvider 共用）必须给空菜单
     * 一个等长的空数据槽：屏幕侧会读这些槽，不兜底就是「构造器不崩了、第一帧渲染崩」。
     */
    @Test
    public void bioreactorDataSlotsFallBackForEmptyMenu() throws IOException {
        String src = TestSourceText.read(BIOREACTOR);
        String ctor = TestSourceText.methodBody(src,
                "public BioreactorMenu(int containerId, Inventory inventory, BioreactorBlockEntity machine)");
        assertTrue("3 参构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue("空菜单必须用等长的空数据槽兜底（SimpleContainerData(DATA_SIZE)）",
                ctor.contains("machine == null")
                        && ctor.contains("new SimpleContainerData(BioreactorBlockEntity.DATA_SIZE)"));
    }

    // ================== 判据不许空转 ==================

    /** 确认上面的判据在本仓确实还能匹配到东西（防改名后静默恒真）。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        String[][] menus = {
                {CHOCOLATE, "ChocolateCannonBlockEntity"},
                {COOKING_POT, "SmartCookingPotBlockEntity"},
                {SKEWERING, "SkeweringMachineBlockEntity"},
                {BIOREACTOR, "BioreactorBlockEntity"},
        };
        // 阈值 5 → 4（2026-10-06）：急冻制冰机菜单随整机迁到 Mek 原生体系 ——
        // IceMakerMenu 不再有自研的「客户端构造器 + 空菜单兜底 handler」这一对，
        // 而是 `extends MekanismTileContainer`（客户端构造由 Mek 的容器工厂承担：
        // 取不到 BE 时工厂直接抛「Missing tile」，不会构造出 machine == null 的空菜单）。
        // 判据的对象（可能为 null 的 machine 字段）在这台机器上不存在了 —— 与坚果爆炒机同型，
        // **不是**为了让红灯变绿而放宽：其余 4 个菜单仍逐个断言两条判据。
        assertTrue("清单被清空了，本类断言全部空转", menus.length >= 4);
        for (String[] menu : menus) {
            String src = TestSourceText.read(menu[0]);
            assertTrue("判据失效：" + menu[0] + " 里已找不到 machine == null",
                    src.contains("machine == null"));
            assertTrue("判据失效：" + menu[0] + " 里已找不到 instanceof 判空",
                    src.contains("instanceof " + menu[1] + " machine ? machine : null"));
        }
    }

    // ================== 断言实现 ==================

    /** 客户端构造器必须用 {@code instanceof} 判空（同时挡掉类型不符），不得再直接强转。 */
    private static void assertClientCtorGuards(String path, String signature, String beType) throws IOException {
        String src = TestSourceText.read(path);
        String ctor = TestSourceText.methodBody(src, signature);
        assertTrue(path + "：客户端构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue(path + "：客户端构造器必须对 getBlockEntity 的结果判空（instanceof 同时挡掉类型不符）："
                        + "旧写法直接强转，BE 为 null 时主构造器第一行 machine.getItems() 就 NPE",
                ctor.contains("instanceof " + beType + " machine ? machine : null"));
        assertFalse(path + "：客户端构造器不得再出现直接强转 getBlockEntity 的旧写法",
                ctor.contains("(" + beType + ") inventory.player.level().getBlockEntity("));
    }

    /**
     * 主构造器必须给空菜单一个等长的兜底 handler，且每个读取方法都走 {@code machine == null} 分支。
     *
     * <p>每个方法体都要求至少一处 {@code machine.} 解引用：找不到解引用说明判据失配
     * （方法被改名/挪走），直接红，避免「方法不存在 ⇒ 断言恒真」的空转。</p>
     */
    private static void assertReadPathsGuarded(String path, String mainCtorSignature, String totalSlotsExpr,
                                               String... readSignatures) throws IOException {
        String src = TestSourceText.read(path);
        String ctor = TestSourceText.methodBody(src, mainCtorSignature);
        assertTrue(path + "：主构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue(path + "：主构造器必须给空菜单一个等长的兜底 handler（槽位数量契约不能变）",
                ctor.contains("machine == null"));
        assertTrue(path + "：兜底 handler 必须按 " + totalSlotsExpr + " 建（槽位数量契约不能变）",
                ctor.contains("new ItemStackHandler(" + totalSlotsExpr + ")"));
        for (String signature : readSignatures) {
            String body = TestSourceText.methodBody(src, signature);
            assertTrue(path + "：找不到方法 " + signature + "（判据失配，需同步更新本护栏）", !body.isEmpty());
            assertTrue(path + "：" + signature + " 预期至少一处 machine. 解引用（判据失配）",
                    body.contains("machine."));
            assertTrue(path + "：" + signature + " 的 machine 解引用必须走 machine == null / != null 兜底",
                    body.contains("machine == null") || body.contains("machine != null"));
        }
    }
}
