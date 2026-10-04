package cn.ism.mekck.menu;

/**
 * 工厂 GUI 的布局几何 —— 菜单侧与屏幕侧<b>共用同一份公式</b>。
 *
 * <h3>为什么要有这个类</h3>
 * 玩家背包的槽位坐标由<b>容器</b>决定（{@code MekanismContainer.addSlots()} 读
 * {@link #inventoryYOffset(int)} 的值，子类通过覆写 {@code getInventoryYOffset()} 提供），
 * 而「Inventory」文字的位置由<b>屏幕</b>决定（{@code inventoryLabelY}）。
 * 两者一旦各算各的，就会出现「文字在一个地方、背包槽在另一个地方」。
 *
 * <p>本模组此前正是如此：屏幕写 {@code inventoryLabelY = 89 + extraHeight}
 * （随等级往下走），而菜单<b>从未覆写</b> {@code getInventoryYOffset()}
 * （恒为 Mek 的 {@code BASE_Y_OFFSET = 84}，永不移动）。后果是面板越高、错位越大：
 * 从 ELITE 档起机器槽就压进玩家背包，SINGULARITY 档重叠达 119px（约 6.5 行槽）。</p>
 *
 * <h3>公式是从 Mek 自己的工厂反推的，不是拍出来的</h3>
 * 对照 Mekanism 1.20.1-10.4.6.20 真源码
 * （{@code mekanism.common.inventory.container.tile.FactoryContainer} 与
 * {@code mekanism.client.gui.machine.GuiFactory}）：
 * <pre>
 *   场景          imageHeight   getInventoryYOffset   inventoryLabelY
 *   普通工厂          166              85                  75
 *   有副资源条        177              95                  85
 *   锯木工厂          187             105                  95
 * </pre>
 * 三组数据给出两条规则，本类采用的正是它们：
 * <ul>
 *   <li>{@code inventoryYOffset}：166 那一档是 {@code imageHeight - 81}，另两档是
 *       {@code imageHeight - 82} —— Mek 自己就不一致，所以 166 照抄数值、其余套公式；</li>
 *   <li>{@code inventoryLabelY = inventoryYOffset - 10}（三档都成立）。</li>
 * </ul>
 * 于是「面板长高多少，背包就下移多少」，机器槽区永远不会压到玩家背包上。
 *
 * <h3>为什么不直接照抄 Mek 的固定值 85 / 95 / 105</h3>
 * Mek 的工厂最多 9 并行，面板高度只有三档；MekCK 有 12 档、最高 81 并行，
 * 面板高度随并行数连续变化，所以按公式算而不是写死。公式的<b>来源</b>与 Mek
 * 的三档实测完全吻合，等价于把 Mek 的三点曲线延长。
 */
public final class MekCkFactoryLayout {

    private MekCkFactoryLayout() {
    }

    /** 面板底边到玩家背包的固定余量（Mek 三组实测 81/82/82，取 82）。 */
    private static final int BOTTOM_MARGIN = 82;
    /** 背包标签相对背包首行的偏移（Mek 三组实测都是 -10）。 */
    private static final int LABEL_ABOVE_INVENTORY = 10;

    /** 所有工厂面板的基础高度（改自旧自研 GUI，Mek 的三档都是在这个量级上做加减）。 */
    private static final int BASE_PANEL_HEIGHT = 184;

    /**
     * 面板最小宽度 —— <b>这条是硬下限，不是美观选择</b>。
     *
     * <p>Mek 把玩家背包固定放在 {@code getInventoryXOffset()}（默认 8），9 列 × 18px
     * ⇒ 占用 {@code 8 .. 170}（见 {@code MekanismContainer.addInventorySlots} 真源码）。
     * 面板比 170 窄，第 9 列背包就会画到面板外面。Mek 自己的工厂面板是 <b>176</b>，
     * 正好留出余量。</p>
     *
     * <p><b>本模组此前没有这条下限</b>：4 个方阵家族各自写
     * {@code imageWidth = 38 + 列*18 + 30 + 列*18 + 20}，列数为 2（BASIC，3 并行）时算出
     * <b>160</b> ⇒ 背包溢出 10px。这是「基础切菜工厂界面扭曲」的直接原因，
     * 也解释了为什么只有基础档出问题（列数 ≥3 时宽度 ≥196，够用）。</p>
     */
    private static final int PANEL_MIN_WIDTH = 176;

    /** 方阵家族：输入方阵起始 x（与 {@code MekCkMachineTile.INPUT_START_X} 同值）。 */
    private static final int GRID_START_X = 38;
    /** 方阵家族：输入方阵与输出方阵的水平间隔（与 {@code MekCkMachineTile.GRID_GAP} 同值）。 */
    private static final int GRID_GAP = 30;
    /** 槽位间距（标准 18px）。 */
    private static final int SLOT_STEP = 18;
    /** 方阵右侧留白。 */
    private static final int PANEL_RIGHT_PAD = 20;

    /**
     * 方阵家族的面板宽度 —— <b>全家族唯一公式</b>，并带 Mek 的硬下限。
     *
     * <p>「输入方阵在左、输出方阵在右」是本模组的既定拓扑（见 {@link #gridImageHeight} 上方的
     * 说明：高并行档竖排会超出屏幕高度，并排是必然）。本方法只统一「宽度怎么算」：
     * {@code max(Mek 的 176, 内容实际所需)}。</p>
     */
    public static int gridPanelWidth(int processes) {
        int columns = columns(processes);
        int content = GRID_START_X + columns * SLOT_STEP + GRID_GAP + columns * SLOT_STEP + PANEL_RIGHT_PAD;
        return Math.max(PANEL_MIN_WIDTH, content);
    }

    /** 方阵内第 {@code i} 个输入槽的 x（与 tile 侧 {@code addSlotGrid} 同式）。 */
    public static int inputSlotX(int index, int processes) {
        return GRID_START_X + (index % columns(processes)) * SLOT_STEP;
    }

    /** 方阵内第 {@code i} 个输入槽的 y。 */
    public static int inputSlotY(int index, int processes) {
        return GRID_START_Y + (index / columns(processes)) * SLOT_STEP;
    }

    /** 方阵内第 {@code i} 个输出槽的 x（输出方阵整体右移「输入宽度 + 间隔」）。 */
    public static int outputSlotX(int index, int processes) {
        int columns = columns(processes);
        return GRID_START_X + columns * SLOT_STEP + GRID_GAP + (index % columns) * SLOT_STEP;
    }

    /** 方阵内第 {@code i} 个输出槽的 y。 */
    public static int outputSlotY(int index, int processes) {
        return GRID_START_Y + (index / columns(processes)) * SLOT_STEP;
    }

    // ── Mek / MekExtras 的「一行式」布局（≤17 并行）────────────────────
    //
    // 真源码出处（不是估的）：
    //   · Mek 1.20.1-10.4.6.20
    //       TileEntityItemToItemFactory.addSlots：
    //         baseX     = BASIC:55 / ADVANCED:35 / ELITE:29 / else:27
    //         baseXMult = BASIC:38 / ADVANCED:26 / else:19
    //         输入 y=13、输出 y=57（同一 x 上下成对）
    //       GuiFactory 构造器：普通工厂面板 176，ULTIMATE 时 imageWidth += 34 → 210
    //   · Mekanism Extras 1.20.1-1.5.0
    //       GuiExtraFactory 构造器：imageWidth += 36*(ordinal+2) + 2*ordinal
    //         （ABSOLUTE 0 → 248、SUPREME 1 → 286、COSMIC 2 → 324、INFINITE 3 → 362）
    //       ExtraFactoryTier 的 processes：11 / 13 / 15 / 17
    //
    // 我们的前 8 档并行数（3/5/7/9 + 11/13/15/17）与这两套**完全一致**，
    // 所以这一段是「照抄现成参照」，不是自创。

    /** 一行式最多支持多少并行（= MekExtras 的 INFINITE 档）。超过它只能换行/方阵。 */
    public static final int ONE_ROW_MAX_PROCESSES = 17;

    /** 一行式：输入槽 y（Mek 的 13）。 */
    public static final int ONE_ROW_INPUT_Y = 13;
    /** 一行式：输出槽 y（Mek 的 57 = 输入下方 44px）。 */
    public static final int ONE_ROW_OUTPUT_Y = 57;
    /** 一行式：面板高度（Mek 工厂的 166）。 */
    public static final int ONE_ROW_PANEL_HEIGHT = 166;

    /**
     * 一行式 + 家族有额外槽时的面板高度 —— <b>187 = 166 + 21</b>。
     *
     * <p>加 21 是照 Mek 的锯木工厂（它的次级输出槽放在 y=77，比普通工厂多一行）：
     * {@code GuiFactory} 里 {@code imageHeight += 21}、{@code inventoryLabelY = 95}，
     * 而 {@code FactoryContainer} 给它的 {@code getInventoryYOffset()} 是 105 ——
     * 正好等于本类 {@code inventoryYOffset(187) = 187 - 82 = 105}。</p>
     */
    public static final int ONE_ROW_PANEL_HEIGHT_WITH_EXTRAS = 187;

    /** 本并行数是否走「一行式」（≤17，即 Mek + MekExtras 覆盖的 8 档）。 */
    public static boolean useOneRow(int processes) {
        return processes >= 1 && processes <= ONE_ROW_MAX_PROCESSES;
    }

    /** 一行式的输入起始 x —— 照 Mek 的 baseX 表按并行数就近映射。 */
    public static int oneRowBaseX(int processes) {
        if (processes <= 3) {
            return 55;   // Mek BASIC
        }
        if (processes <= 5) {
            return 35;   // Mek ADVANCED
        }
        if (processes <= 7) {
            return 29;   // Mek ELITE
        }
        return 27;       // Mek ULTIMATE 与 MekExtras 全部四档
    }

    /** 一行式的槽间距 —— 照 Mek 的 baseXMult 表，随档位压缩 38 → 26 → 19。 */
    public static int oneRowStep(int processes) {
        if (processes <= 3) {
            return 38;
        }
        if (processes <= 5) {
            return 26;
        }
        return 19;
    }

    /** 一行式第 {@code i} 个槽的 x（输入与输出同 x，上下成对）。 */
    public static int oneRowSlotX(int index, int processes) {
        return oneRowBaseX(processes) + index * oneRowStep(processes);
    }

    /**
     * 一行式进度条的 y —— 上游 {@code GuiFactory} 与 {@code GuiExtraFactory} 都是 33。
     *
     * <p>输入行占 13..31、输出行占 57..75，33 正落在中间那条空带里。</p>
     */
    public static final int ONE_ROW_PROGRESS_Y = 33;

    /**
     * 一行式第 {@code index} 条进度条的 x —— 上游的 {@code 4 + baseX + i * baseXMult}。
     *
     * <p>上游<b>每个并行槽一条</b>进度条（{@code GuiFactory.addGuiElements} 的循环里
     * {@code addProgress(new GuiProgress(..., ProgressType.DOWN, this, 4 + baseX + (i * baseXMult), 33))}），
     * 不是一条居中的箭头。{@code ProgressType.DOWN} 是 8×20 的竖条，正好落在对应槽位的正下方。</p>
     */
    public static int oneRowProgressX(int index, int processes) {
        return 4 + oneRowSlotX(index, processes);
    }

    /**
     * 一行式的面板宽度 —— 照 Mek（176 / ULTIMATE 210）与 MekExtras（每档 +38）的实测值。
     *
     * @param processes 并行数；&gt;17 时返回 {@code -1}（表示「一行式不适用」）
     */
    public static int oneRowPanelWidth(int processes) {
        if (processes > ONE_ROW_MAX_PROCESSES) {
            return -1;
        }
        if (processes <= 7) {
            return 176;   // Mek BASIC / ADVANCED / ELITE
        }
        if (processes == 9) {
            return 210;   // Mek ULTIMATE（176 + 34）
        }
        int ordinal = extrasOrdinal(processes);
        // MekExtras：imageWidth += 36 * (ordinal + 2) + 2 * ordinal
        return ordinal < 0 ? 176 : 176 + 36 * (ordinal + 2) + 2 * ordinal;
    }

    /** 11/13/15/17 → MekExtras 的 ordinal 0/1/2/3；其余返回 -1。 */
    public static int extrasOrdinal(int processes) {
        return switch (processes) {
            case 11 -> 0;
            case 13 -> 1;
            case 15 -> 2;
            case 17 -> 3;
            default -> -1;
        };
    }

    // ── 家族专属槽（额外槽）的几何：单一出处 ──────────────────────────
    //
    // 为什么必须收在这里：这些坐标原先在 tile 与屏幕里**各写一份**
    // （SEASONING_* 两处、STORAGE_* 三处），于是出现三类漂移：
    //   · 种植切配的额外槽放在 x=26，占 26..44；而机器槽区从 x=27/29/38 起 ——
    //     **压住第一个输入槽 6px**。
    //   · 烧烤的调味料槽是竖排 (8, 55+i*18)，屏幕上的开关却是横排 (8+i*18, 109) —— 一一对不上。
    //   · 穿串屏幕按旧的负数坐标去画「存储」标签，标签落在面板外
    //     （存储搬进悬浮窗后那已是死 UI）。
    //
    // 统一规则：**额外槽永远是左边缘的一列**（x = {@value #EXTRA_SLOT_X}，步长 18）。
    // 为什么只能是「一列」而不是「一行」：机器槽区最左也在 x=27（一行式）或 x=38（方阵），
    // 而 x=26 那一格占 26..44 —— 任何第二个列位都会与机器槽区重叠。

    /** 额外槽的 x（面板左边缘；机器槽区最左 x=27，中间留 1px 缝）。 */
    public static final int EXTRA_SLOT_X = 8;

    /** 额外槽间距（标准 18px）。 */
    public static final int EXTRA_SLOT_STEP = 18;

    /**
     * 额外槽列的起始 y。
     *
     * <ul>
     *   <li>一行式取 <b>41</b>：能源槽固定在 (7,13) 占 {@code 7..25 × 13..31}，
     *       41 正好在它下面，且与输入行 y=13、输出行 y=57 都不冲突。</li>
     *   <li>方阵沿用旧值 <b>55</b>：保持已上线的观感，且此时面板高 ≥238，
     *       背包在 y≥156，不会碰到。</li>
     * </ul>
     */
    public static int extraSlotY0(boolean oneRow) {
        return oneRow ? 41 : 55;
    }

    /** 第 {@code index} 个额外槽的 y。 */
    public static int extraSlotY(int index, boolean oneRow) {
        return extraSlotY0(oneRow) + index * EXTRA_SLOT_STEP;
    }

    /** 额外槽列下方第 {@code row} 行的 y（烧烤的调味料开关就放这一行）。 */
    public static int extraSlotRowBelow(int extraCount, int row, boolean oneRow) {
        return extraSlotY0(oneRow) + (extraCount + row) * EXTRA_SLOT_STEP;
    }

    /**
     * 「一行式 + 额外槽」的面板高度 —— <b>由内容算出来</b>，不再用魔法值。
     *
     * <p>约束只有一条：<b>额外槽列（及其下方的开关行）必须整块落在玩家背包之上</b>。
     * 背包的 y 由 {@link #inventoryYOffset} 决定（面板高 &gt; 166 时 = 面板高 − 82），
     * 所以面板高 ≥ 内容底 + 82 即可。下限仍取 {@value #ONE_ROW_PANEL_HEIGHT}（Mek 的 166）。</p>
     *
     * @param extraCount      额外槽个数
     * @param rowsBelowExtras 额外槽下方还要留几行（烧烤 3 个开关占 1 行；其余家族传 0）
     */
    public static int oneRowPanelHeightWithExtras(int extraCount, int rowsBelowExtras) {
        int contentBottom = extraSlotY0(true) + (extraCount + rowsBelowExtras) * EXTRA_SLOT_STEP;
        return Math.max(ONE_ROW_PANEL_HEIGHT, contentBottom + 82);
    }

    /** 烹饪工厂面板宽（144 格存储已改为悬浮窗虚拟槽，不再挂在面板外侧）。 */
    public static final int COOKING_PANEL_WIDTH = 204;
    /** 穿串工厂面板宽（= Mek 工厂默认宽 176）。 */
    public static final int SKEWERING_PANEL_WIDTH = 176;

    // ── 悬浮窗布局（输入/输出整块进窗口）的面板尺寸 ────────────────────
    //
    // 为什么必须单独一套：>17 并行且输入输出对称的四个档（烈焰炽焱 25 / 晶钛矩阵 36 /
    // 星云塑造 49 / 奇点创世 81）把输入与输出**整块搬进了悬浮窗**，主面板上
    // 一个机器槽都没有。若仍按方阵公式算面板，奇点创世会得到 412×310 的空面板 ——
    // 比整个游戏窗口还高，玩家背包被顶到屏幕外，而面板里什么都没有。
    //
    // 尺寸取 Mek 工厂的标准 176×166：面板上只剩能量槽 (7,13)、家族额外槽列（x=8）
    // 与进度条，176 宽足够；高度由额外槽列的底边反推，保证它不压进玩家背包。

    /** 悬浮窗布局的面板宽 —— Mek 工厂的标准 176（= {@link #PANEL_MIN_WIDTH}）。 */
    public static final int WINDOW_PANEL_WIDTH = PANEL_MIN_WIDTH;

    /**
     * 该 tile 是否把输入/输出整块放进悬浮窗（判据在 tile 侧，见
     * {@code MekCkMachineTile.windowLayout}）。
     *
     * <p>屏幕与菜单<b>必须</b>用同一个判据决定面板尺寸：两边一旦不一致，
     * 就会出现「文字在一个地方、背包槽在另一个地方」——这正是本类存在的理由。</p>
     */
    public static boolean usesSlotWindow(cn.ism.mekck.machine.MekCkMachineTile tile) {
        return tile != null && tile.usesWindowLayout();
    }

    /**
     * 并行方阵家族（切菜 / 研磨 / 烧烤 / 种植切配）的面板高度 —— <b>屏幕与菜单共用</b>。
     *
     * <h3>为什么必须收成一处</h3>
     * 此前屏幕按「一行式 / 方阵」两分支算，而菜单<b>只按方阵公式</b>算
     * （{@code inventoryYOffset(gridImageHeight(processes))}）。一行式档位下
     * 屏幕的面板是 166，菜单却按 184 算 ⇒ 背包槽比标签低 18px。
     * 现在两边都调本方法，不可能再漂移。
     *
     * @param tile            机器（{@code null} 时按最小面板算）
     * @param extraCount      家族额外槽个数（切菜/研磨 0、种植切配 2、烧烤 3）
     * @param rowsBelowExtras 额外槽下方还要留几行（烧烤 1、其余 0）
     * @param gridExtraHeight 方阵分支额外要加的高度（种植切配 18、其余 0）
     */
    public static int gridFamilyPanelHeight(cn.ism.mekck.machine.MekCkMachineTile tile,
                                            int extraCount, int rowsBelowExtras, int gridExtraHeight) {
        return gridFamilyPanelHeight(processesOf(tile), usesSlotWindow(tile),
                extraCount, rowsBelowExtras, gridExtraHeight);
    }

    /**
     * {@link #gridFamilyPanelHeight(cn.ism.mekck.machine.MekCkMachineTile, int, int, int)} 的纯函数形态。
     *
     * <p>拆出这一层是为了<b>可测</b>：真 tile 在裸 JVM 里造不出来（构造链要方块与注册表），
     * 而「屏幕面板高」与「菜单背包偏移」必须永远满足
     * {@code inventoryYOffset(panelHeight) == 屏幕侧算出的偏移}。
     * 见 {@code TestFactoryPanelGeometry}。</p>
     */
    public static int gridFamilyPanelHeight(int processes, boolean windowLayout,
                                            int extraCount, int rowsBelowExtras, int gridExtraHeight) {
        if (windowLayout || useOneRow(processes)) {
            // 两种「紧凑」布局共用一条约束：额外槽列（及其下方的开关行）必须整块落在
            // 玩家背包之上。背包首行 y = 面板高 − 82，所以面板高 ≥ 内容底 + 82。
            // 额外槽列的起点由 extraSlotY0 给出（一行式 41 / 其余 55），与 tile 侧同源。
            int extraY0 = extraSlotY0(useOneRow(processes) && !windowLayout);
            int contentBottom = extraY0 + (extraCount + rowsBelowExtras) * EXTRA_SLOT_STEP;
            return Math.max(ONE_ROW_PANEL_HEIGHT, contentBottom + BOTTOM_MARGIN);
        }
        return gridImageHeight(processes) + gridExtraHeight;
    }

    /**
     * 并行方阵家族的面板宽度 —— <b>屏幕与菜单共用</b>。见
     * {@link #gridFamilyPanelHeight} 的说明。
     */
    public static int gridFamilyPanelWidth(cn.ism.mekck.machine.MekCkMachineTile tile) {
        return gridFamilyPanelWidth(processesOf(tile), usesSlotWindow(tile));
    }

    /** {@link #gridFamilyPanelWidth(cn.ism.mekck.machine.MekCkMachineTile)} 的纯函数形态。 */
    public static int gridFamilyPanelWidth(int processes, boolean windowLayout) {
        if (windowLayout) {
            return WINDOW_PANEL_WIDTH;
        }
        return useOneRow(processes) ? oneRowPanelWidth(processes) : gridPanelWidth(processes);
    }

    /** 方阵起始 y（与 {@code MekCkMachineTile.GRID_START_Y} 同值）。 */
    private static final int GRID_START_Y = 41;

    // ── 面板高度：按家族形态计算（菜单与屏幕共用）──────────────────────

    /** 并行方阵家族（切菜 / 研磨 / 烧烤）：行数超过 2 的部分各占 18px。 */
    public static int gridImageHeight(int processes) {
        int columns = columns(processes);
        int rows = rows(processes, columns);
        return BASE_PANEL_HEIGHT + Math.max(0, (rows - 2) * 18);
    }

    /** 种植切配：并行方阵 + 一行额外槽（营养液 / 生长土）。 */
    public static int plantingImageHeight(int processes) {
        return gridImageHeight(processes) + 18;
    }

    /**
     * 烹饪的 3 个流体条几何 —— <b>屏幕与面板高度公式共用同一份</b>。
     *
     * <p>{@code GuiCkFluidGauge} 用 {@code GaugeType.STANDARD}（javap 实测
     * {@code GaugeOverlay.STANDARD} = 16×58，{@code GuiGauge} 构造器再加 2 ⇒ 18×60），
     * 所以条占 {@code y = 88..148}。</p>
     */
    public static final int COOKING_FLUID_GAUGE_Y = 88;
    public static final int COOKING_FLUID_GAUGE_H = 60;

    /**
     * 烹饪：输入固定 6 格（3 列 2 行）、输出固定 12 格（3 列 4 行），
     * 与档位无关，所以面板高度是常量 184 + (4-2)*18 = 220 —— <b>但 220 不够</b>：
     * 3 个流体条占 {@code 88..148}，而 220 档的玩家背包首行是 138、「Inventory」标签是 128，
     * 条的下缘压住背包首行 10px、标签整行落在条内。
     *
     * <p>所以面板高度取「条底 + 标签余量 + 背包余量」：
     * {@code 88 + 60 + 10 + 82 = 240}。背包首行随之下移到 158、标签到 148，
     * 与条底（148）齐平，两者都不再与条重叠。</p>
     */
    public static int cookingImageHeight() {
        return Math.max(BASE_PANEL_HEIGHT + (4 - 2) * 18,
                COOKING_FLUID_GAUGE_Y + COOKING_FLUID_GAUGE_H + LABEL_ABOVE_INVENTORY + BOTTOM_MARGIN);
    }

    /** 穿串：输入固定 3 格 + 产物/返还 2 格，面板高度与档位无关。 */
    public static int skeweringImageHeight() {
        return BASE_PANEL_HEIGHT;
    }

    // ── 背包几何 ────────────────────────────────────────────────────────

    /**
     * 玩家背包首行的 y —— 覆写 {@code MekanismContainer.getInventoryYOffset()} 时返回它。
     *
     * @param imageHeight 本屏幕的面板高度（与 {@code AbstractContainerScreen.imageHeight} 同值）
     */
    public static int inventoryYOffset(int imageHeight) {
        // 166 那一档照抄 Mek 的 85：它的余量是 81，而 177 / 187 两档是 82。
        // 套 -82 会得到 84 —— 比上游高 1px，背包槽与「Inventory」标签会一起上移。
        if (imageHeight <= ONE_ROW_PANEL_HEIGHT) {
            return 85;
        }
        return Math.max(imageHeight - BOTTOM_MARGIN, 85);
    }

    /** 「Inventory」文字标签的 y —— 赋给 {@code inventoryLabelY}。 */
    public static int inventoryLabelY(int imageHeight) {
        return inventoryYOffset(imageHeight) - LABEL_ABOVE_INVENTORY;
    }

    /** 玩家背包的总宽度：9 列 × 18px。 */
    private static final int INVENTORY_WIDTH = 9 * SLOT_STEP;

    // ── 「不覆写 getInventoryYOffset 的菜单」的背包几何 ──────────────────
    //
    // Mek 的 MekanismContainer 默认 BASE_Y_OFFSET = 84、getInventoryXOffset() 默认 8。
    // 继承它却**不覆写**的菜单（UniversalCuttingMachineMenu / GrillMenu 等）拿到的就是这两个值。
    // 把它们在此处显式命名，是为了让屏幕侧不必再写裸数字 ——
    // 此前 GrillScreen 与 UniversalCuttingMachineScreen 都把 inventoryLabelY 写成 84
    // （= 背包首行本身），标签于是压在第一行槽位上。

    /** 未覆写 {@code getInventoryYOffset} 的菜单：玩家背包首行 y（Mek 的 BASE_Y_OFFSET）。 */
    public static final int MEK_DEFAULT_INVENTORY_Y = 84;
    /** 同上：玩家背包首列 x（Mek 的 {@code getInventoryXOffset()} 默认值）。 */
    public static final int INVENTORY_X_OFFSET = 8;
    /**
     * 「Inventory」标签的 y —— 用于<b>不覆写</b> {@code getInventoryYOffset} 的菜单。
     *
     * <p>与 {@link #inventoryLabelY(int)} 的区别只在于基准：那边按本类公式算出的
     * 背包首行（166 档是 85），这边是 Mek 的默认 84。</p>
     */
    public static final int INVENTORY_LABEL_Y = MEK_DEFAULT_INVENTORY_Y - LABEL_ABOVE_INVENTORY;

    /**
     * 玩家背包首列的 x —— 覆写 {@code MekanismContainer.getInventoryXOffset()} 时返回它。
     *
     * <h3>为什么必须随面板宽度变化</h3>
     * Mek 的 {@code MekanismContainer} 把背包放在 {@code getInventoryXOffset()}（默认 <b>8</b>），
     * 而 Mek 自己的 {@code FactoryContainer} <b>会按面板宽度改它</b>：
     * <pre>
     *   @Override protected int getInventoryXOffset() {
     *       return tile.tier == FactoryTier.ULTIMATE ? 26 : 8;   // ULTIMATE 面板 210 宽（176+34）
     *   }
     * </pre>
     * 即「面板变宽 ⇒ 背包右移」。
     *
     * <p><b>本模组此前零覆写</b>，恒为 8。于是面板越宽、背包越贴左：SINGULARITY 面板 412 宽，
     * 背包却只占最左 162px，右侧空出 250px（实测截图：面板 460 宽、背包区间实测 x=0..162）。
     * 这就是「全部工厂的物品栏位置/比例不对」的根因。</p>
     *
     * <p><b>一行式档位（前 8 档）照抄上游，不套「居中」公式</b>：Mek 与 MekExtras 给的都不是
     * 居中的整数解 —— 面板 176 居中给 7 而上游是 8，面板 210 居中给 24 而上游是 26。
     * 表里的宽度全部来自 {@link #oneRowPanelWidth}，一一对应上游 8 档。</p>
     *
     * <p>其余布局（烹饪 / 穿串 / 悬浮窗 / 方阵）上游没有对应物，按面板内居中。</p>
     */
    public static int inventoryXOffset(int imageWidth) {
        return switch (imageWidth) {
            case 210 -> 26;   // Mek ULTIMATE
            case 248 -> 44;   // MekExtras ABSOLUTE：22 * (0 + 2) - 3 * 0
            case 286 -> 63;   // SUPREME：22 * (1 + 2) - 3 * 1
            case 324 -> 82;   // COSMIC：22 * (2 + 2) - 3 * 2
            case 362 -> 101;  // INFINITE：22 * (3 + 2) - 3 * 3
            default -> Math.max(8, (imageWidth - INVENTORY_WIDTH) / 2);
        };
    }

    /**
     * 从 tile 读本机并行数（= 输入槽数）。
     *
     * <p>档位未知时返回 1：宁可画成最小面板，也不要在 GUI 构造期抛异常
     * （{@code getTier()} 在方块被换成非工厂方块的异常路径上可能为 null）。</p>
     */
    public static int processesOf(cn.ism.mekck.machine.MekCkMachineTile tile) {
        cn.ism.mekck.CuttingMachineFactoryTier tier = tile == null ? null : tile.getTier();
        return tier == null ? 1 : tier.processes;
    }

    // ── 并行方阵行列数（与 {@code MekCkMachineTile} 的槽位排布同源）──────

    /** 列数 = ⌈√N⌉，与 {@code MekCkMachineTile.inputSlotColumns} 同口径。 */
    public static int columns(int processes) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, processes))));
    }

    /** 行数 = ⌈N / 列数⌉。 */
    public static int rows(int processes, int columns) {
        return Math.max(1, (int) Math.ceil((double) Math.max(1, processes) / Math.max(1, columns)));
    }
}
