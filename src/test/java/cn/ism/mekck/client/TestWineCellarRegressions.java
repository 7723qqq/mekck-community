package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 陈化窖（WineCellar）迁移回归护栏 —— M6 报告 5 条。
 *
 * <h3>背景：槽位顺序变了，所有「按下标读槽」的代码都要跟着改</h3>
 * 迁移前菜单是自研 {@code AbstractContainerMenu}，槽顺序 = 存储 9 → 电源 1 → 背包；
 * 迁移后走 Mek 的 {@code MekanismTileContainer.addSlots()}，顺序变成
 * <b>升级 2 → 存储 9 → 电源 1 → 背包</b>（javap 实测：{@code supportsUpgrades()} 为真时先
 * {@code addSlot(upgradeSlot)} + {@code addSlot(upgradeOutputSlot)}，再遍历
 * {@code tile.getInventorySlots(null)}，最后 {@code addInventorySlots}）。
 * 于是：
 * <ul>
 *   <li>屏幕按 {@code menu.slots.get(0..8)} 读存储格 ⇒ 实际读到「升级 0,1 + 存储 0..6」，
 *       漏掉存储 7、8（①）；</li>
 *   <li>菜单的 {@code getPowerSlotIndex()/getStorageSlotIndex()} 返回的是 tile 库存下标，
 *       不是 {@code menu.slots} 下标（④）；</li>
 *   <li>Mek 默认 {@code quickMoveStack} 按 {@code inventoryContainerSlots} 顺序插入，
 *       存储格排在电源槽之前 ⇒ 红石落进存储 0（⑤）。</li>
 * </ul>
 *
 * <h3>另外两条是渲染层</h3>
 * ② 自动 {@code GuiSlot} 不调 {@code setRenderAboveSlots}：槽贴图在 {@code super.render}
 * 阶段画，而 widget 的 {@code drawBackground} 要到 {@code renderLabels} 阶段才跑 ——
 * 黑屏背板作为 widget 挂进来会把 3×3 存储格整片盖没；
 * ③ 电源槽的槽型/覆盖图标由 {@code InventoryContainerSlot} 的 slotType/slotOverlay 决定，
 * 迁移后不再由屏幕手画 {@code GuiVirtualSlot.with(SlotOverlay.POWER)}，必须在建槽时补上。
 *
 * <h3>为什么用源码形态</h3>
 * 这些类（菜单/方块实体）在裸 JVM 里造不出来（构造链要方块与注册表），
 * 与 {@code TestGuiInventoryLabels} 同款：断言看结构，配套变异测试。
 */
public class TestWineCellarRegressions {

    private static final String SCREEN = "src/main/java/cn/ism/mekck/client/WineCellarScreen.java";
    private static final String MENU = "src/main/java/cn/ism/mekck/menu/WineCellarMenu.java";
    private static final String BE = "src/main/java/cn/ism/mekck/blockentity/WineCellarBlockEntity.java";

    // ── ① 能耗估算读存储格必须走菜单下标换算 ────────────────────────────

    /**
     * {@code currentEnergyDraw()} 必须走 {@code menu.getStorageSlotIndex(i)}。
     *
     * <p>直接 {@code menu.slots.get(i)}（i = 0..8）读到的是升级槽 0,1 与存储 0..6 ——
     * 能耗读数漏掉存储 7、8，且把升级槽里的物品也当成酒去判。</p>
     */
    @Test
    public void energyDrawReadsStorageSlotsThroughMenuIndices() throws IOException {
        String src = TestSourceText.read(SCREEN);
        String body = TestSourceText.methodBody(src, "private double currentEnergyDraw()");
        assertTrue("currentEnergyDraw 找不到（判据失配）", !body.isEmpty());
        assertTrue("能耗估算必须走 menu.getStorageSlotIndex(i)（菜单槽顺序是 升级2→存储9→电源1→背包）",
                body.contains("getStorageSlotIndex("));
        assertFalse("能耗估算不得直接按 0..8 读 menu.slots（会读到升级槽与存储 0..6，漏存储 7、8）",
                body.contains("menu.slots.get(i)"));
    }

    // ── ② 黑屏背板必须画在 renderBg ────────────────────────────────────

    /**
     * 背板不得再作为 widget 挂进来，必须画在 {@code renderBg}。
     *
     * <p>widget 的绘制阶段（{@code renderLabels}）晚于槽贴图（{@code super.render}）与
     * 物品（{@code renderSlot}），背板会把 3×3 存储格整片盖没。</p>
     */
    @Test
    public void innerScreenPlateIsDrawnInRenderBg() throws IOException {
        String src = TestSourceText.read(SCREEN);
        assertFalse("背板不得再作为 widget 挂进来（widget 在 renderLabels 阶段绘制，会盖没 3×3 存储格）",
                src.contains("new GuiInnerScreen("));
        String bg = TestSourceText.methodBody(src, "protected void renderBg");
        assertTrue("renderBg 找不到（判据失配）", !bg.isEmpty());
        assertTrue("背板必须画在 renderBg 里（GuiUtils.renderBackgroundTexture）",
                bg.contains("GuiUtils.renderBackgroundTexture("));
    }

    // ── ③ 电源槽槽型与覆盖图标 ─────────────────────────────────────────

    /**
     * 电源槽建槽后必须补 POWER 槽型与覆盖图标。
     *
     * <p>{@code MekCkSlot.inputFiltered} 建出来的是 INPUT 槽型；GUI 的槽位外观由
     * {@code GuiMekanism.addSlots} 读 {@code InventoryContainerSlot.getSlotType()/getSlotOverlay()}
     * 决定（javap 实测），不补就画成普通输入槽、闪电图标丢失。</p>
     */
    @Test
    public void powerSlotKeepsPowerTypeAndOverlay() throws IOException {
        String src = TestSourceText.read(BE);
        String body = TestSourceText.methodBody(src, "protected IInventorySlotHolder getInitialInventory");
        assertTrue("getInitialInventory 找不到（判据失配）", !body.isEmpty());
        assertTrue("电源槽必须 setSlotType(ContainerSlotType.POWER)（否则 GUI 画成普通输入槽）",
                body.contains("setSlotType(ContainerSlotType.POWER)"));
        assertTrue("电源槽必须 setSlotOverlay(SlotOverlay.POWER)（否则闪电覆盖图标丢失）",
                body.contains("setSlotOverlay(SlotOverlay.POWER)"));
    }

    // ── ④ 菜单下标换算 ─────────────────────────────────────────────────

    /**
     * {@code getStorageSlotIndex(i)} = 升级槽个数 + i；{@code getPowerSlotIndex()} = 升级槽个数 + SLOT_POWER。
     *
     * <p>升级槽个数由 Mek 的 {@code MekanismTileContainer.addSlots()} 决定：升级输入 + 升级输出 = 2。</p>
     */
    @Test
    public void menuSlotIndicesIncludeUpgradeSlots() throws IOException {
        String src = TestSourceText.read(MENU);
        String be = TestSourceText.read(BE);

        assertEquals("升级槽个数必须是 2（Mek 的升级输入 + 升级输出两槽）",
                2, intConstant(src, "UPGRADE_SLOT_COUNT"));
        assertEquals("tile 库存里的电源槽下标是 9（9 个存储格之后）",
                9, intConstant(be, "SLOT_POWER"));
        assertTrue("存储格基准下标必须 = 升级槽个数",
                src.contains("STORAGE_SLOT_BASE = UPGRADE_SLOT_COUNT"));
        assertTrue("电源槽菜单下标必须 = 升级槽个数 + SLOT_POWER",
                src.contains("POWER_SLOT_INDEX = UPGRADE_SLOT_COUNT + WineCellarBlockEntity.SLOT_POWER"));

        String storage = TestSourceText.methodBody(src, "public int getStorageSlotIndex");
        assertTrue("getStorageSlotIndex 找不到（判据失配）", !storage.isEmpty());
        assertTrue("getStorageSlotIndex 必须加升级槽偏移（返回 STORAGE_SLOT_BASE + storageIndex）",
                storage.contains("STORAGE_SLOT_BASE + storageIndex"));
        String power = TestSourceText.methodBody(src, "public int getPowerSlotIndex");
        assertTrue("getPowerSlotIndex 找不到（判据失配）", !power.isEmpty());
        assertTrue("getPowerSlotIndex 必须返回 POWER_SLOT_INDEX（不是 tile 的 SLOT_POWER）",
                power.contains("POWER_SLOT_INDEX"));
    }

    // ── ⑤ shift-click 能量物品优先进电源槽 ─────────────────────────────

    /**
     * {@code quickMoveStack} 必须覆写，且「玩家背包 → 机器」的能量物品先试电源槽。
     *
     * <p>Mek 默认按 {@code inventoryContainerSlots} 顺序插入，存储格排在电源槽之前 ⇒
     * 红石/能量立方落进存储 0，永远到不了电源槽（旧菜单是「能量物品 → 电源槽，其余 → 存储格」）。
     * 现在的实现只拦这一条，其余交回 {@code super}（Mek 默认路由）。</p>
     */
    @Test
    public void shiftClickRoutesEnergyItemsToPowerSlotFirst() throws IOException {
        String src = TestSourceText.read(MENU);
        String body = TestSourceText.methodBody(src, "public ItemStack quickMoveStack");
        assertTrue("quickMoveStack 覆写缺失（Mek 默认会把红石塞进存储 0）", !body.isEmpty());
        int energy = body.indexOf("isValidEnergyItem");
        int power = body.indexOf("POWER_SLOT_INDEX");
        assertTrue("quickMoveStack 必须先判能量物品（PowerSlotUtil.isValidEnergyItem）", energy >= 0);
        assertTrue("能量物品必须优先进电源槽（POWER_SLOT_INDEX 出现在能量判定之后）", power > energy);
        assertTrue("只拦玩家背包侧（index >= MACHINE_SLOT_COUNT），机器槽的 shift-click 不得被截",
                body.contains("MACHINE_SLOT_COUNT"));
        assertTrue("其余物品必须交回 super（Mek 默认路由）", body.contains("super.quickMoveStack("));
    }

    // ── 判据不许空转 ───────────────────────────────────────────────────

    /** 确认判据在本仓确实还能匹配到东西。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        assertFalse("WineCellarScreen.currentEnergyDraw 判据失配",
                TestSourceText.methodBody(TestSourceText.read(SCREEN), "private double currentEnergyDraw()").isEmpty());
        assertFalse("WineCellarScreen.renderBg 判据失配",
                TestSourceText.methodBody(TestSourceText.read(SCREEN), "protected void renderBg").isEmpty());
        assertFalse("WineCellarBlockEntity.getInitialInventory 判据失配",
                TestSourceText.methodBody(TestSourceText.read(BE), "protected IInventorySlotHolder getInitialInventory").isEmpty());
        assertFalse("WineCellarMenu.quickMoveStack 判据失配",
                TestSourceText.methodBody(TestSourceText.read(MENU), "public ItemStack quickMoveStack").isEmpty());
    }

    /** 从源码里取一个 {@code NAME = <数字>;} 常量的值。 */
    private static int intConstant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)").matcher(source);
        assertTrue("源码里找不到常量 " + name + " 的字面量定义，判据可能失配", m.find());
        return Integer.parseInt(m.group(1));
    }
}
