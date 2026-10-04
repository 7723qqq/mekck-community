package cn.ism.mekck.menu.slot;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 槽位表 —— <b>一台机器一张</b>，是该机器全部槽位坐标的唯一出处。
 *
 * <h3>契约（评审时逐条核对）</h3>
 * <ol>
 *   <li>同一台机器的坐标只在它的槽位表里出现一次；菜单注册与屏幕读取都从表里拿，
 *       <b>任何地方不得再写一份坐标</b>。</li>
 *   <li>同一张表内 (x,y) <b>不得重复</b>——重复即「两个框叠在一起」，
 *       正是重写前 {@code 6,12} 与 {@code 7,13} 那类问题的根源。</li>
 *   <li>坐标不得为负（面板左上角为原点）。</li>
 *   <li>槽位的 <b>{@code addSlot} 顺序不可随意调换</b>：Mek 的 byte 下标存档按加入顺序编号，
 *       换序会让旧存档的槽位错位（见 {@code MekCkMachineTile} 里「先全部输入、再全部输出」的铁律）。
 *       本类只描述坐标，<b>不描述顺序</b>；顺序由各菜单自己保持。</li>
 * </ol>
 *
 * <h3>为什么用「每机器一个静态内部类」而不是一个大 Map</h3>
 * 槽位表要能在<b>编译期</b>被菜单直接引用（{@code IceMaker.INPUT.x()}），
 * 而不是运行期查表——后者拼错 key 只会在开界面时才炸。静态常量对拼写错误是编译错误。
 */
public final class MekCkSlotTable {

    private MekCkSlotTable() {
    }

    /**
     * 校验一张表：坐标唯一、非负。
     *
     * <p>供各机器的静态初始化块与护栏单测调用。<b>不通过就抛</b>——宁可启动即崩，
     * 也不要上线后靠肉眼发现两个框叠在一起。</p>
     *
     * @param owner 机器名，仅用于报错定位
     * @param defs  该机器的全部槽位
     */
    public static void validate(String owner, List<SlotDef> defs) {
        if (defs == null || defs.isEmpty()) {
            throw new IllegalArgumentException(owner + " 的槽位表为空");
        }
        Set<String> seenKeys = new TreeSet<>();
        Set<String> seenPos = new TreeSet<>();
        for (SlotDef d : defs) {
            if (!seenKeys.add(d.key())) {
                throw new IllegalStateException(owner + " 的槽位表里 key 重复：" + d.key());
            }
            if (d.x() < 0 || d.y() < 0) {
                throw new IllegalStateException(owner + " 的槽位 " + d.key()
                        + " 坐标为负：(" + d.x() + "," + d.y() + ")。"
                        + "面板左上角是原点，负坐标会画到面板外。");
            }
            // 同一坐标两个槽 = 两个框叠在一起，玩家会看到一个框、实际有两条命中区
            String pos = d.x() + "," + d.y();
            if (!seenPos.add(pos)) {
                SlotDef other = defs.stream()
                        .filter(s -> s.x() == d.x() && s.y() == d.y() && !s.key().equals(d.key()))
                        .findFirst().orElse(null);
                throw new IllegalStateException(owner + " 的槽位 " + d.key()
                        + " 与 " + (other == null ? "另一槽" : other.key())
                        + " 坐标相同 (" + pos + ")：一个框只能对应一个槽。");
            }
        }
    }
}
