package cn.ism.mekck.menu.slot;

/**
 * 一个槽位的<b>唯一定义</b>：身份、类别、坐标、覆盖图标名。
 *
 * <h3>为什么要有这个类型（2026-10-03 实机定位）</h3>
 * 重写前，同一个槽存在<b>多套互不相干的坐标</b>：
 * <pre>
 *   能源槽：菜单 7,13 ／ 屏幕 6,12 ／ 屏幕 7,12 ／ 基类常量 7,13     —— 4 套值
 *   输入槽：菜单 INPUT_X,INPUT_Y ／ 屏幕 INPUT_X-1,INPUT_Y-1        —— 差 (1,1)
 *   输出槽：菜单 OUTPUT_X,OUTPUT_Y ／ 屏幕 OUTPUT_X,OUTPUT_Y-1      —— 差 (0,1)
 * </pre>
 * 原因是菜单用手写的 {@code SlotItemHandler implements IVirtualSlot}，Mek 的
 * {@code GuiMekanism.addSlots()} 不认这种槽（它只对 {@code InventoryContainerSlot} 建 widget），
 * 于是屏幕必须用 {@code GuiVirtualSlot} <b>再画一份</b>，两份坐标靠人工 ±1 凑合。
 * 而偏移规则本身也不统一——同一个「输出槽」，制冰机系是 {@code (0,-1)}、
 * 穿串系是 {@code (-1,-1)}，纯属各写各的。
 *
 * <p>本记录把「一个槽」收敛成<b>一个对象</b>：坐标只在槽位表里写一次，
 * 菜单注册与屏幕渲染都读它。{@code -1} 这类凑合偏移从此没有存在的理由。</p>
 *
 * <h3>为什么覆盖图标存「名字」而不是 {@code SlotOverlay} 实例</h3>
 * {@code SlotOverlay} 的类初始化链是
 * {@code SlotOverlay → MekanismUtils → ItemStack}，<b>需要完整的 Minecraft 运行时</b>。
 * 而槽位表必须能在裸 JVM 的单测里被加载（护栏测试要检查坐标唯一性），
 * 一旦表里直接持有 {@code SlotOverlay} 常量，类初始化就抛
 * {@code ExceptionInInitializerError}（实测：{@code ItemStack.<clinit>} 在 {@code RecordCodecBuilder}）。
 *
 * <p>所以这里只存枚举<b>名</b>（{@code "POWER"} 等），由菜单在使用时
 * 经 {@code SlotOverlay.valueOf(...)} 解析。代价是一次字符串查找（仅在菜单构造期），
 * 换来的是「坐标表与 MC 运行时解耦」——纯数据可以被纯逻辑单测覆盖。</p>
 *
 * @param key        槽位标识（同一台机器内唯一，如 {@code "input"}），用于报错定位
 * @param kind       槽位类别，决定用哪一族 Mek 槽（见 {@link SlotKind}）
 * @param x          面板内 x（<b>与菜单注册坐标完全相同</b>）
 * @param y          面板内 y（同上）
 * @param overlayName 覆盖图标名（{@code SlotOverlay} 的常量名）；{@code null} 表示无覆盖
 */
public record SlotDef(String key, SlotKind kind, int x, int y, String overlayName) {

    public SlotDef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("SlotDef.key 不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("SlotDef.kind 不能为空（槽 " + key + "）");
        }
    }

    /** 无覆盖图标的槽位定义（多数输入/输出槽不需要覆盖图标）。 */
    public static SlotDef of(String key, SlotKind kind, int x, int y) {
        return new SlotDef(key, kind, x, y, null);
    }

    /**
     * 带覆盖图标的槽位定义。
     *
     * @param overlayName {@code SlotOverlay} 的常量名，如 {@code "POWER"} / {@code "INPUT"}
     */
    public static SlotDef withOverlay(String key, SlotKind kind, int x, int y, String overlayName) {
        return new SlotDef(key, kind, x, y, overlayName);
    }

    /** 是否有覆盖图标。 */
    public boolean hasOverlay() {
        return overlayName != null;
    }
}
