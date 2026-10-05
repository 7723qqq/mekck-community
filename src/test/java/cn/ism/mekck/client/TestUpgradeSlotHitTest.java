package cn.ism.mekck.client;

import mekanism.common.inventory.container.IGUIWindow;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.IntSupplier;

import static org.junit.Assert.assertEquals;

/**
 * M4-6 护栏（纯逻辑）：升级槽的 {@code getActualX/Y} 必须<b>实时跟随</b>
 * {@code GuiVirtualSlot} 传入的坐标供给器，而不是绑定时的坐标快照。
 *
 * <h3>缺陷形态</h3>
 * 7 个菜单的 {@code UpgradeSlot.updatePosition} 曾把供给器<b>求值成快照</b>
 * （{@code actualX = xSupplier.getAsInt()}）。Mek 的 {@code GuiVirtualSlot.updateVirtualSlot}
 * 传的是 {@code () -> this.relativeX + 1 / this.relativeY + 1}（javap 实测；relativeX/relativeY
 * 是 GuiElement 的 GUI 相对字段，{@code GuiWindow.onDrag → move(dx,dy)} 会同步更新它并递归移动
 * 子 widget）⇒ 拖拽升级窗口后物品画到新位置，而 {@code GuiMekanism.isMouseOverSlot}
 * 对 {@code IVirtualSlot} 走 {@code getActualX/Y}（javap 实测）⇒ 命中框留在拖拽前的位置。
 * Mek 自家 {@code VirtualInventoryContainerSlot} 存的是供给器（javap 实测），本仓照抄。
 *
 * <h3>为什么用反射</h3>
 * {@code UpgradeSlot} 是各菜单的 private 静态内部类，测试无法直接引用；反射实例化后
 * 用可变供给器模拟窗口拖拽，断言 getActualX/Y 跟着变——快照实现会在这里变红。
 *
 * <p>IceFactoryMenu 的同型修复按任务书推迟到后续轮次，故不在本清单内。</p>
 */
public class TestUpgradeSlotHitTest {

    /**
     * 仍带自研 {@code UpgradeSlot} 的菜单（IceFactoryMenu 按任务书推迟）。
     *
     * <p><b>ElectricGrindingMachineMenu 已于阶段 3 样板迁移中移出本清单</b>：
     * 它随整机迁到 Mek 的 {@code MekanismTileContainer}，升级槽不再由它自己实现
     * （私有静态内部类 {@code UpgradeSlot} 整个删除），而是由 Mek 的
     * {@code TileComponentUpgrade} 承担 —— 「栏位跟随位置供给器」这条断言对它已无对象。
     * 本测试的类注释说明了这条清单会随迁移收缩，本行就是第一次收缩。</p>
     *
     * <p><b>NutRoasterMenu 于 2026-10-06 同型收缩（6 → 5）</b>：坚果爆炒机迁到
     * Mek 原生体系后，菜单里那四个手写槽类（含 {@code UpgradeSlot}）与自研升级窗
     * 一并删除，升级槽改由 {@code MekanismTileContainer.getUpgradeSlot()} 承担。
     * 断言的落点（自研 UpgradeSlot 的 getActualX/Y）随迁移消失，不是放宽判据。</p>
     */
    private static final List<String> MENUS = List.of(
            "cn.ism.mekck.menu.SimpleMachineMenu",
            "cn.ism.mekck.menu.SkeweringMachineMenu",
            "cn.ism.mekck.menu.SmartCookingPotMenu",
            "cn.ism.mekck.menu.IceMakerMenu",
            "cn.ism.mekck.menu.ChocolateCannonMenu");

    /**
     * 裸 JVM 里启用 {@link net.minecraft.world.item.ItemStack} / 注册表。
     *
     * <p><b>必须逐字照抄 {@code TestKitchenOutputMergeOverflow#boot} 的配方，且必须在
     * {@code @BeforeClass} 里</b>：漏掉 {@code SharedConstants.tryDetectVersion()} 会让
     * {@code ItemStack.<clinit>} 抛 {@code ExceptionInInitializerError}，而 JVM 会缓存
     * 「初始化失败」——之后任何碰到 ItemStack 的测试都变成 {@code NoClassDefFoundError}，
     * 一次失败毒化整个测试会话（本测试第一版实测带崩 102 个用例）。</p>
     */
    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪。
        }
    }

    @Test
    public void upgradeSlotFollowsItsPositionSupplier() throws Exception {
        for (String menuName : MENUS) {
            // 只加载不初始化：本测试不需要菜单类的任何静态状态。
            Class<?> menuClass = Class.forName(menuName, false, TestUpgradeSlotHitTest.class.getClassLoader());
            Class<?> slotClass = Class.forName(menuName + "$UpgradeSlot", false,
                    TestUpgradeSlotHitTest.class.getClassLoader());
            Constructor<?> ctor = slotClass.getDeclaredConstructor(
                    ItemStackHandler.class, int.class, int.class, int.class, menuClass);
            ctor.setAccessible(true);
            Object slot = ctor.newInstance(new ItemStackHandler(1), 0, 40, 46, null);

            Method update = slotClass.getMethod("updatePosition",
                    IGUIWindow.class, IntSupplier.class, IntSupplier.class);
            Method getX = slotClass.getMethod("getActualX");
            Method getY = slotClass.getMethod("getActualY");
            update.setAccessible(true);
            getX.setAccessible(true);
            getY.setAccessible(true);

            IGUIWindow window = (IGUIWindow) Proxy.newProxyInstance(
                    IGUIWindow.class.getClassLoader(),
                    new Class<?>[]{IGUIWindow.class},
                    (proxy, method, args) -> null);

            // 未绑定供给器：回落到构造坐标（Mek 同款 xSupplier == null ? x : ...）
            Field linkedWindow = slotClass.getDeclaredField("linkedWindow");
            linkedWindow.setAccessible(true);
            linkedWindow.set(slot, window);
            assertEquals(menuName + "：未绑定供给器时应回落到构造坐标 x", 40, getX.invoke(slot));
            assertEquals(menuName + "：未绑定供给器时应回落到构造坐标 y", 46, getY.invoke(slot));

            // 绑定可变供给器，模拟 GuiVirtualSlot 的 () -> getX()/getY()
            int[] pos = {100, 200};
            update.invoke(slot, window, (IntSupplier) () -> pos[0], (IntSupplier) () -> pos[1]);
            assertEquals(menuName + "：绑定后 getActualX 应取供给器当前值", 100, getX.invoke(slot));
            assertEquals(menuName + "：绑定后 getActualY 应取供给器当前值", 200, getY.invoke(slot));

            // 模拟 GuiWindow.onDrag → move(dx,dy)：供给器值变化，命中坐标必须实时跟随
            pos[0] = 140;
            pos[1] = 260;
            assertEquals(menuName + "：窗口拖拽后 getActualX 必须实时跟随供给器（不得留快照）",
                    140, getX.invoke(slot));
            assertEquals(menuName + "：窗口拖拽后 getActualY 必须实时跟随供给器（不得留快照）",
                    260, getY.invoke(slot));
        }
    }
}
