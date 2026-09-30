package cn.ism.mekck.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 把「一行式」几何钉死在 Mek / Mekanism Extras 的**实测真值**上。
 *
 * <h3>为什么这些数字必须被钉住</h3>
 * 它们不是自创的排版偏好，而是从两个上游 mod 的真源码里读出来的：
 * <ul>
 *   <li>Mek 1.20.1-10.4.6.20 `TileEntityItemToItemFactory.addSlots`：
 *       `baseX = BASIC:55 / ADVANCED:35 / ELITE:29 / else:27`、
 *       `baseXMult = BASIC:38 / ADVANCED:26 / else:19`，输入 y=13、输出 y=57；
 *       `GuiFactory` 里 ULTIMATE 使面板 176 → 210。</li>
 *   <li>Mekanism Extras 1.20.1-1.5.0 `GuiExtraFactory`：
 *       `imageWidth += 36 * (ordinal + 2) + 2 * ordinal`，
 *       `ExtraFactoryTier` 的 processes 为 11 / 13 / 15 / 17。</li>
 * </ul>
 * 任何一处改动都会让本模组的前 8 档**偏离上游**，而那种偏离在游戏里只表现为
 * 「看着不太对」，没有报错——所以只能靠测试守住。
 */
public class TestMekCkFactoryLayout {

    /** Mek 的 baseX 表（前 4 档）。 */
    @Test
    public void oneRowBaseXMatchesMek() {
        assertEquals("Mek BASIC baseX", 55, MekCkFactoryLayout.oneRowBaseX(3));
        assertEquals("Mek ADVANCED baseX", 35, MekCkFactoryLayout.oneRowBaseX(5));
        assertEquals("Mek ELITE baseX", 29, MekCkFactoryLayout.oneRowBaseX(7));
        assertEquals("Mek ULTIMATE baseX", 27, MekCkFactoryLayout.oneRowBaseX(9));
        // MekExtras 四档沿用 ULTIMATE 的 27
        assertEquals(27, MekCkFactoryLayout.oneRowBaseX(11));
        assertEquals(27, MekCkFactoryLayout.oneRowBaseX(13));
        assertEquals(27, MekCkFactoryLayout.oneRowBaseX(15));
        assertEquals(27, MekCkFactoryLayout.oneRowBaseX(17));
    }

    /** Mek 的 baseXMult 表（间距随档位压缩 38 → 26 → 19）。 */
    @Test
    public void oneRowStepMatchesMek() {
        assertEquals("Mek BASIC step", 38, MekCkFactoryLayout.oneRowStep(3));
        assertEquals("Mek ADVANCED step", 26, MekCkFactoryLayout.oneRowStep(5));
        assertEquals("Mek ELITE step", 19, MekCkFactoryLayout.oneRowStep(7));
        assertEquals("Mek ULTIMATE step", 19, MekCkFactoryLayout.oneRowStep(9));
        assertEquals(19, MekCkFactoryLayout.oneRowStep(17));
    }

    /** Mek 的输入/输出 y。 */
    @Test
    public void oneRowYsMatchMek() {
        assertEquals(13, MekCkFactoryLayout.ONE_ROW_INPUT_Y);
        assertEquals(57, MekCkFactoryLayout.ONE_ROW_OUTPUT_Y);
        assertEquals("Mek 普通工厂面板高", 166, MekCkFactoryLayout.ONE_ROW_PANEL_HEIGHT);
    }

    /** 面板宽：Mek 176 / ULTIMATE 210；MekExtras 248 / 286 / 324 / 362。 */
    @Test
    public void oneRowPanelWidthMatchesUpstream() {
        assertEquals("Mek BASIC/ADVANCED/ELITE", 176, MekCkFactoryLayout.oneRowPanelWidth(3));
        assertEquals(176, MekCkFactoryLayout.oneRowPanelWidth(5));
        assertEquals(176, MekCkFactoryLayout.oneRowPanelWidth(7));
        assertEquals("Mek ULTIMATE = 176 + 34", 210, MekCkFactoryLayout.oneRowPanelWidth(9));
        assertEquals("MekExtras ABSOLUTE", 248, MekCkFactoryLayout.oneRowPanelWidth(11));
        assertEquals("MekExtras SUPREME", 286, MekCkFactoryLayout.oneRowPanelWidth(13));
        assertEquals("MekExtras COSMIC", 324, MekCkFactoryLayout.oneRowPanelWidth(15));
        assertEquals("MekExtras INFINITE", 362, MekCkFactoryLayout.oneRowPanelWidth(17));
        // 超出 MekExtras 量程：一行式不适用
        assertEquals(-1, MekCkFactoryLayout.oneRowPanelWidth(25));
        assertEquals(-1, MekCkFactoryLayout.oneRowPanelWidth(81));
    }

    /** 最后一格必须落在面板内（否则槽位会画到面板外）。 */
    @Test
    public void lastSlotFitsInsidePanel() {
        for (int processes : new int[]{3, 5, 7, 9, 11, 13, 15, 17}) {
            int width = MekCkFactoryLayout.oneRowPanelWidth(processes);
            int lastX = MekCkFactoryLayout.oneRowSlotX(processes - 1, processes);
            org.junit.Assert.assertTrue(
                    "并行 " + processes + "：最后一格 x=" + lastX + " + 18 应 <= 面板宽 " + width,
                    lastX + 18 <= width);
        }
    }

    /** 一行式只覆盖 ≤17 并行（前 8 档），更高档必须走别的布局。 */
    @Test
    public void oneRowOnlyCoversTheFirstEightTiers() {
        for (int processes : new int[]{3, 5, 7, 9, 11, 13, 15, 17}) {
            org.junit.Assert.assertTrue("并行 " + processes + " 应走一行式",
                    MekCkFactoryLayout.useOneRow(processes));
        }
        for (int processes : new int[]{25, 36, 49, 81}) {
            org.junit.Assert.assertFalse("并行 " + processes + " 不应走一行式",
                    MekCkFactoryLayout.useOneRow(processes));
        }
    }

    /** MekExtras 的 ordinal 映射（决定面板加宽量）。 */
    @Test
    public void extrasOrdinalMatchesMekExtras() {
        assertEquals(0, MekCkFactoryLayout.extrasOrdinal(11));
        assertEquals(1, MekCkFactoryLayout.extrasOrdinal(13));
        assertEquals(2, MekCkFactoryLayout.extrasOrdinal(15));
        assertEquals(3, MekCkFactoryLayout.extrasOrdinal(17));
        assertEquals(-1, MekCkFactoryLayout.extrasOrdinal(9));
        assertEquals(-1, MekCkFactoryLayout.extrasOrdinal(25));
    }

    // ── 面板几何：屏幕与菜单必须永远一致 ────────────────────────────────

    /** 全部 12 档的并行数（与 {@code CuttingMachineFactoryTier} 的 processes 同值）。 */
    private static final int[] ALL_PROCESSES = {3, 5, 7, 9, 11, 13, 15, 17, 25, 36, 49, 81};

    /**
     * 悬浮窗布局（&gt;17 并行且输入输出对称）的面板必须收窄到 Mek 的标准尺寸。
     *
     * <p>修复前这里走方阵公式：奇点创世得到 <b>412×310</b> 的空面板——
     * 输入与输出整块在悬浮窗里，主面板上一个机器槽都没有，而面板比整个游戏窗口还高，
     * 玩家背包被顶到屏幕外。</p>
     */
    @Test
    public void slotWindowLayoutUsesCompactPanel() {
        for (int processes : new int[]{25, 36, 49, 81}) {
            assertEquals("并行 " + processes + " 的悬浮窗面板宽",
                    MekCkFactoryLayout.WINDOW_PANEL_WIDTH,
                    MekCkFactoryLayout.gridFamilyPanelWidth(processes, true));
            int height = MekCkFactoryLayout.gridFamilyPanelHeight(processes, true, 0, 0, 0);
            assertEquals("无额外槽时取 Mek 的 166", 166, height);
            org.junit.Assert.assertTrue("悬浮窗面板不该比屏幕还高：" + height, height <= 240);
        }
    }

    /**
     * 面板高度必须容得下家族额外槽列 —— 否则额外槽（或它下方的开关行）会压进玩家背包。
     *
     * <p>约束：额外槽列底边 ≤ 背包首行 y（= {@code inventoryYOffset(面板高)}）。
     * 额外槽列的起点由 {@code extraSlotY0(oneRow)} 给出（一行式 41 / 其余 55），
     * 与 tile 侧 {@code appendExtraSlots} 同源。</p>
     */
    @Test
    public void extraSlotsStayAboveThePlayerInventory() {
        // {额外槽数, 下方行数, 方阵额外高度}：切菜/研磨 0、种植切配 2、烧烤 3+1 行开关
        int[][] families = {{0, 0, 0}, {2, 0, 18}, {3, 1, 0}};
        for (int[] family : families) {
            for (int processes : ALL_PROCESSES) {
                for (boolean window : new boolean[]{false, true}) {
                    if (window && MekCkFactoryLayout.useOneRow(processes)) {
                        continue; // 悬浮窗布局只出现在 >17 并行
                    }
                    int height = MekCkFactoryLayout.gridFamilyPanelHeight(
                            processes, window, family[0], family[1], family[2]);
                    int inventoryY = MekCkFactoryLayout.inventoryYOffset(height);
                    boolean oneRow = MekCkFactoryLayout.useOneRow(processes) && !window;
                    int contentBottom = MekCkFactoryLayout.extraSlotY0(oneRow)
                            + (family[0] + family[1]) * MekCkFactoryLayout.EXTRA_SLOT_STEP;
                    org.junit.Assert.assertTrue(
                            "并行 " + processes + " / 悬浮窗 " + window + "：额外槽底边 " + contentBottom
                                    + " 压进了背包首行 " + inventoryY,
                            contentBottom <= inventoryY);
                }
            }
        }
    }

    /** 玩家背包必须整块落在面板内（9 列 × 18px = 162 宽）。 */
    @Test
    public void playerInventoryFitsInsidePanel() {
        for (int processes : ALL_PROCESSES) {
            for (boolean window : new boolean[]{false, true}) {
                if (window && MekCkFactoryLayout.useOneRow(processes)) {
                    continue;
                }
                int width = MekCkFactoryLayout.gridFamilyPanelWidth(processes, window);
                int x = MekCkFactoryLayout.inventoryXOffset(width);
                org.junit.Assert.assertTrue(
                        "并行 " + processes + " / 悬浮窗 " + window + "：背包 " + x + ".." + (x + 162)
                                + " 越出面板宽 " + width,
                        x + 162 <= width);
            }
        }
    }

    /**
     * 一行式档位的背包 x 必须与上游逐像素一致。
     *
     * <p>上游两套都不是「居中」的整数解：Mek 的 {@code FactoryContainer} 给 ULTIMATE 26
     * （居中会算成 24），MekExtras 的 {@code ExtraFactoryContainer} 给
     * {@code 22 * (ordinal + 2) - 3 * ordinal} = 44 / 63 / 82 / 101（居中会各少 1）。</p>
     */
    @Test
    public void oneRowInventoryXMatchesUpstream() {
        int[] processes = {3, 5, 7, 9, 11, 13, 15, 17};
        int[] upstream = {8, 8, 8, 26, 44, 63, 82, 101};
        for (int i = 0; i < processes.length; i++) {
            int width = MekCkFactoryLayout.oneRowPanelWidth(processes[i]);
            assertEquals("并行 " + processes[i] + "（面板宽 " + width + "）的背包 x",
                    upstream[i], MekCkFactoryLayout.inventoryXOffset(width));
        }
    }

    /**
     * 一行式进度条必须与上游逐条对齐 —— <b>每并行槽一条</b>，不是一条居中的箭头。
     *
     * <p>上游 {@code GuiFactory} 与 {@code GuiExtraFactory} 的 {@code addGuiElements} 都是
     * {@code 4 + baseX + i * baseXMult}、y=33、{@code ProgressType.DOWN}（8×20 竖条）。
     * 表里的 baseX / baseXMult 就是 {@code oneRowBaseX} / {@code oneRowStep} 的上游原值。</p>
     */
    @Test
    public void oneRowProgressBarsMatchUpstream() {
        assertEquals("上游两套都是 y=33", 33, MekCkFactoryLayout.ONE_ROW_PROGRESS_Y);
        int[] processes = {3, 5, 7, 9, 11, 13, 15, 17};
        int[] baseX = {55, 35, 29, 27, 27, 27, 27, 27};
        int[] mult = {38, 26, 19, 19, 19, 19, 19, 19};
        for (int t = 0; t < processes.length; t++) {
            int n = processes[t];
            for (int i = 0; i < n; i++) {
                assertEquals("并行 " + n + " 第 " + i + " 条进度条的 x",
                        4 + baseX[t] + i * mult[t], MekCkFactoryLayout.oneRowProgressX(i, n));
            }
            // 最后一条必须落在面板内（DOWN 是 8 宽）
            int last = MekCkFactoryLayout.oneRowProgressX(n - 1, n);
            int width = MekCkFactoryLayout.oneRowPanelWidth(n);
            org.junit.Assert.assertTrue(
                    "并行 " + n + "：最后一条进度条 " + last + ".." + (last + 8) + " 越出面板宽 " + width,
                    last + 8 <= width);
        }
    }

    /**
     * 一行式档位的面板高必须由「额外槽列底边 + 背包余量」算出来，<b>不能</b>再走方阵公式。
     *
     * <p>修复前屏幕按 166 / 187 算、菜单按 {@code gridImageHeight} 算（BASIC 是 184），
     * 背包槽因此比「Inventory」标签低 18px；而烧烤的开关行（109..127）更是直接压进
     * 一行式面板 187 的背包首行（105）。</p>
     */
    @Test
    public void oneRowPanelHeightIsNotTheGridFormula() {
        for (int processes : new int[]{3, 5, 7, 9, 11, 13, 15, 17}) {
            assertEquals("并行 " + processes + " 无额外槽：取 Mek 的 166",
                    MekCkFactoryLayout.ONE_ROW_PANEL_HEIGHT,
                    MekCkFactoryLayout.gridFamilyPanelHeight(processes, false, 0, 0, 0));
            // 种植切配：2 个额外槽从 y=41 起 ⇒ 底边 77，背包在 85 ⇒ 166 够用
            assertEquals("并行 " + processes + " 种植切配",
                    MekCkFactoryLayout.ONE_ROW_PANEL_HEIGHT,
                    MekCkFactoryLayout.gridFamilyPanelHeight(processes, false, 2, 0, 18));
            // 烧烤：3 个槽 + 1 行开关从 y=41 起 ⇒ 底边 113，背包必须 ≥ 113 ⇒ 195
            assertEquals("并行 " + processes + " 烧烤（含开关行）", 195,
                    MekCkFactoryLayout.gridFamilyPanelHeight(processes, false, 3, 1, 0));
        }
    }

    /**
     * 背包偏移必须与上游逐像素一致。
     *
     * <p>上游 {@code FactoryContainer.getInventoryYOffset()} 给的是 <b>85 / 95 / 105</b>
     * （对应面板高 166 / 177 / 187），而 166 那一档的余量是 <b>81</b>、另两档才是 82。
     * 套 {@code 面板高 - 82} 会在 166 上得到 84 —— 背包槽与「Inventory」标签一起上移 1px。</p>
     */
    @Test
    public void inventoryYOffsetMatchesUpstream() {
        assertEquals("Mek 普通工厂面板 166", 85, MekCkFactoryLayout.inventoryYOffset(166));
        assertEquals("Mek 有副资源条面板 177", 95, MekCkFactoryLayout.inventoryYOffset(177));
        assertEquals("Mek 锯木工厂面板 187", 105, MekCkFactoryLayout.inventoryYOffset(187));
        // 标签恒在背包首行上方 10px（上游三档都是）
        assertEquals(75, MekCkFactoryLayout.inventoryLabelY(166));
        assertEquals(85, MekCkFactoryLayout.inventoryLabelY(177));
        assertEquals(95, MekCkFactoryLayout.inventoryLabelY(187));
        // 本模组自有面板（≥184）仍按「面板高 - 82」外推，且不得低于上游最小值 85
        for (int processes : ALL_PROCESSES) {
            for (boolean window : new boolean[]{false, true}) {
                if (window && MekCkFactoryLayout.useOneRow(processes)) {
                    continue;
                }
                int height = MekCkFactoryLayout.gridFamilyPanelHeight(processes, window, 3, 1, 0);
                org.junit.Assert.assertTrue("面板高 " + height + " 的背包偏移低于 85",
                        MekCkFactoryLayout.inventoryYOffset(height) >= 85);
            }
        }
    }
}
