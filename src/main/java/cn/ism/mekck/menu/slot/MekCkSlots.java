package cn.ism.mekck.menu.slot;

import java.util.ArrayList;
import java.util.List;

/**
 * 各机器的槽位表 —— <b>坐标的唯一出处</b>。
 *
 * <h3>怎么用</h3>
 * <pre>
 *   // 菜单注册
 *   addSlot(new StoreSlot(items, i, WineCellar.STORAGE[i].x(), WineCellar.STORAGE[i].y()));
 *   // 屏幕若仍需虚拟槽（过渡期），也读同一份：
 *   new GuiVirtualSlot(..., WineCellar.POWER.x(), WineCellar.POWER.y());
 * </pre>
 *
 * <h3>坐标从哪来</h3>
 * <b>一律取重写前「菜单侧」的值</b>——那是决定点击命中的一套，也是玩家已经熟悉的手感。
 * 屏幕侧那套（少了 1px 的）被废弃：它本就是为凑合而对齐出来的，没有独立意义。
 *
 * <h3>新增机器时</h3>
 * 加一个静态内部类 + 一个 {@code static { MekCkSlotTable.validate(...); } }。
 * 校验会在类加载时立刻暴露「坐标重复」这类错误。
 */
public final class MekCkSlots {

    private MekCkSlots() {
    }

    // ==================== 陈酿机（Wine Cellar） ====================

    /**
     * 陈酿机：3×3 存储网格 + 能源槽。
     *
     * <p>存储 9 格按行优先排布（{@code i / 3} 行、{@code i % 3} 列），
     * 与 {@code WineCellarBlockEntity.SLOT_COUNT = 9} 一一对应；
     * 能源槽 `SLOT_POWER = 9` 是第 10 格，坐标 {@code (6,12)}。</p>
     *
     * <p><b>注意能源槽坐标是 (6,12)，与工厂/其它单机的 (7,13) 不同</b>——
     * 这是重写前就存在的差异（{@code WineCellarMenu.POWER_X/Y}），
     * 本次保持不动以免移动已上线机器的点击热区。是否统一留待阶段 3 评估。</p>
     */
    public static final class WineCellar {

        /** 存储网格：行优先，索引与 {@code WineCellarBlockEntity} 的槽下标 0..8 对应。 */
        public static final List<SlotDef> STORAGE;

        /** 能源槽（槽下标 9）。覆盖图标名 {@code "POWER"} 由菜单侧解析成 SlotOverlay。 */
        public static final SlotDef POWER =
                SlotDef.withOverlay("power", SlotKind.POWER, 6, 12, "POWER");

        /** 全部槽位，顺序 = 菜单 addSlot 顺序（存储 0..8 在前，能源槽最后）。 */
        public static final List<SlotDef> ALL;

        static {
            List<SlotDef> storage = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                int row = i / 3;
                int col = i % 3;
                storage.add(SlotDef.of("storage[" + i + "]", SlotKind.STORAGE,
                        62 + col * 18, 18 + row * 18));
            }
            STORAGE = List.copyOf(storage);

            List<SlotDef> all = new ArrayList<>(storage);
            all.add(POWER);
            ALL = List.copyOf(all);

            MekCkSlotTable.validate("WineCellar", ALL);
        }
    }

    // ==================== 电力研磨机（Electric Grinding Machine） ====================

    /**
     * 电力研磨机：输入 + 输出 + 能源槽。
     *
     * <h3>坐标口径：逐字对齐 Mek 的基础电力机器（{@code TileEntityElectricMachine}）</h3>
     * 本机在 MekCK 里的定位就是「一台基础电力机器」（处理 <i>一样进、一样出</i> 的配方），
     * 所以槽位位置<b>不该沿用旧自研屏的坐标</b>，而应与 Mek 的粉碎机 / 富集仓完全一致。
     * 三个值都是从 {@code TileEntityElectricMachine.getInitialInventory} 与
     * {@code TileEntityMekanism} 建能源槽处读出来的：
     * <pre>
     *   输入槽   (64, 17)    InputInventorySlot.at(...)  ← TileEntityElectricMachine 偏移 25/27
     *   输出槽   (116, 35)   OutputInventorySlot.at(...) ← 偏移 54/56
     *   能源槽   (64, 53)    EnergyInventorySlot         ← 与 Mek 基础机器同款
     * </pre>
     *
     * <p><b>迁移前的 (38,41) / (56,41) / (7,13) 是旧自研屏的坐标，已废弃</b>：
     * 那是自研体系自己定的紧凑摆法，与 Mek 的机器不一致 —— 沿用它会得到
     * 「外观是 Mek 的底板、槽位却挤在左上角」的四不像。</p>
     *
     * <p><b>顺序是存档契约</b>：输入 0 → 输出 1 → 能源槽 2。与
     * {@code GrindingMachineTile.INPUT_SLOT / OUTPUT_SLOT} 一致。</p>
     */
    public static final class GrindingMachine {

        /** 输入槽（槽下标 0）。坐标同 Mek 基础电力机器。 */
        public static final SlotDef INPUT =
                SlotDef.of("input", SlotKind.INPUT, 64, 17);

        /** 输出槽（槽下标 1）。坐标同 Mek 基础电力机器。 */
        public static final SlotDef OUTPUT =
                SlotDef.of("output", SlotKind.OUTPUT, 116, 35);

        /** 能源槽（槽下标 2）。坐标同 Mek 基础电力机器。 */
        public static final SlotDef POWER =
                SlotDef.withOverlay("power", SlotKind.POWER, 64, 53, "POWER");

        /** 全部槽位，顺序 = 槽下标顺序 = 输入 → 输出 → 能源。 */
        public static final List<SlotDef> ALL = List.of(INPUT, OUTPUT, POWER);

        static {
            MekCkSlotTable.validate("GrindingMachine", ALL);
        }

        private GrindingMachine() {
        }
    }

    // ==================== 坚果爆炒机（Nut Roaster） ====================

    /**
     * 坚果爆炒机：输入 + 输出 + 创造升级 + 能源槽。
     *
     * <h3>坐标口径：对齐 Mek 的基础电力机器，创造升级槽按本模组「额外槽」约定</h3>
     * <pre>
     *   输入槽     (64, 17)   同 Mek 基础电力机器（TileEntityElectricMachine）
     *   输出槽     (116, 35)  同上
     *   能源槽     (64, 53)   同上（EnergyInventorySlot）
     *   创造升级槽 (8, 17)    本模组额外槽的既有约定（MekCkFactoryLayout.EXTRA_SLOT_X = 8，
     *                        GrillBlockEntity 的创造槽也是 (8,17)）
     * </pre>
     * 迁移前的旧自研屏坐标是 {@code (40,40) / (108,40) / (7,13)} + 两个升级槽
     * {@code (40,46)/(40,72)}，已随「升级交给 Mek 组件」一并废弃。
     *
     * <p><b>顺序是存档契约</b>：输入 0 → 输出 1 → 创造升级 2 → 能源 3，与
     * {@code NutRoasterTile.INPUT_SLOT / OUTPUT_SLOT / CREATIVE_SLOT / POWER_SLOT} 一致。</p>
     */
    public static final class NutRoaster {

        /** 输入槽（槽下标 0）。 */
        public static final SlotDef INPUT = SlotDef.of("input", SlotKind.INPUT, 64, 17);

        /** 输出槽（槽下标 1）—— 同时是攻击弹药库。 */
        public static final SlotDef OUTPUT = SlotDef.of("output", SlotKind.OUTPUT, 116, 35);

        /** 创造升级槽（槽下标 2，单格）。 */
        public static final SlotDef CREATIVE = SlotDef.of("creative", SlotKind.EXTRA, 8, 17);

        /** 能源槽（槽下标 3）。 */
        public static final SlotDef POWER =
                SlotDef.withOverlay("power", SlotKind.POWER, 64, 53, "POWER");

        /** 全部槽位，顺序 = 槽下标顺序。 */
        public static final List<SlotDef> ALL = List.of(INPUT, OUTPUT, CREATIVE, POWER);

        static {
            MekCkSlotTable.validate("NutRoaster", ALL);
        }

        private NutRoaster() {
        }
    }
}
