package cn.ism.mekck.menu;

import cn.ism.mekck.machine.MekCkSlot;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/**
 * 容器里的「悬浮窗槽位」——按类别分成输入 / 输出 / 存储三组。
 *
 * <h3>为什么需要「捞」而不是直接持有引用</h3>
 * 这些槽的容器槽是 {@code MekanismTileContainer.addSlots()} <b>自动建的</b>：
 * 它遍历 {@code tile.getInventorySlots(null)} 对每个槽调 {@code createContainerSlot()}
 * （真源码 78-84 行）。我们的槽覆写了该方法返回 {@link VirtualInventoryContainerSlot}
 * （见 {@link MekCkSlot#windowInput} / {@link MekCkSlot#windowOutput} / {@link MekCkSlot#storage}），
 * 所以基类会把它们建成窗口槽 —— <b>但基类不保留引用</b>。窗口要绑定
 * {@code GuiVirtualSlot} 就必须拿到这些对象，因此只能回头从 {@code menu.slots} 里筛。
 *
 * <h3>判据为什么要三重</h3>
 * <ol>
 *   <li>{@code instanceof VirtualInventoryContainerSlot}：只挑虚拟槽。Mek 会给
 *       <b>升级槽与升级输出槽</b>也建虚拟槽（{@code MekanismTileContainer} 71-75 行），
 *       它们同样是虚拟槽，所以这一重不够。</li>
 *   <li>{@code getInventorySlot() instanceof MekCkSlot}：排除 Mek 的
 *       {@code UpgradeInventorySlot}。</li>
 *   <li>{@link MekCkSlot#isWindowSlot()}：排除本模组自己的主面板槽
 *       （它们虽然也是 {@code MekCkSlot}，但 {@code windowData == null}，
 *       容器槽是普通槽而非虚拟槽）。</li>
 * </ol>
 * 三重都满足的只可能是窗口槽，再按 {@link MekCkSlot#kind()} 归到三组。
 */
public final class MekCkWindowSlotHolder {

    private final List<VirtualInventoryContainerSlot> inputs;
    private final List<VirtualInventoryContainerSlot> outputs;
    private final List<VirtualInventoryContainerSlot> storage;

    /**
     * 在容器构造完成后收集。
     *
     * <p>必须晚于 {@code super(...)}（槽位在那时建出来）。写成字段初始化器即可：
     * Java 的字段初始化器在父构造器之后执行。</p>
     */
    public MekCkWindowSlotHolder(AbstractContainerMenu menu) {
        List<VirtualInventoryContainerSlot> in = new ArrayList<>();
        List<VirtualInventoryContainerSlot> out = new ArrayList<>();
        List<VirtualInventoryContainerSlot> store = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot instanceof VirtualInventoryContainerSlot virtual
                    && virtual.getInventorySlot() instanceof MekCkSlot mekCk
                    && mekCk.isWindowSlot()) {
                if (mekCk.isWindowInput()) {
                    in.add(virtual);
                } else if (mekCk.isWindowOutput()) {
                    out.add(virtual);
                } else if (mekCk.isWindowExtra()) {
                    store.add(virtual);
                }
            }
        }
        this.inputs = List.copyOf(in);
        this.outputs = List.copyOf(out);
        this.storage = List.copyOf(store);
    }

    /** 进悬浮窗的输入槽（高档工厂才有；其余为空）。 */
    public List<VirtualInventoryContainerSlot> inputs() {
        return inputs;
    }

    /** 进悬浮窗的输出槽。 */
    public List<VirtualInventoryContainerSlot> outputs() {
        return outputs;
    }

    /** 进悬浮窗的存储槽（烹饪 144 / 穿串 81）。 */
    public List<VirtualInventoryContainerSlot> storage() {
        return storage;
    }

    /** 三组都空？用于决定要不要加标签页。 */
    public boolean isEmpty() {
        return inputs.isEmpty() && outputs.isEmpty() && storage.isEmpty();
    }
}
