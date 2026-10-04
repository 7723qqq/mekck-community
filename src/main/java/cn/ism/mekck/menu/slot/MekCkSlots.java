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
}
