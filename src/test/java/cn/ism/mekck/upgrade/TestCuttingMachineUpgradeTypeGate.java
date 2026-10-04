package cn.ism.mekck.upgrade;

import cn.ism.mekck.TestSourceText;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.Upgrade;
import mekanism.common.inventory.slot.UpgradeInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 切菜机潜行安装的类型闸门（finding 20261004-fix-upgrade-dup-bug-c1）。
 *
 * <h3>缺陷形态</h3>
 * {@code UpgradeInstallHandler} 的切菜机分支直接调
 * {@code TileComponentUpgrade.addUpgrades(SPEED, 1)} —— 该签名里没有 ItemStack，
 * 既不校验类型也不消耗物品。而 {@code upgradeLike} 闸门放行任意升级（能量/堆叠/创造/
 * 气体/冷萃/费列罗），于是任意 upgradeLike 物品都会被装成速度卡；C1 补上 shrink 后
 * 变成「错物品被吃掉换速度卡」。
 *
 * <h3>修法与护栏口径</h3>
 * 分支改为把手持物品交给 {@code UniversalCuttingMachineTile.addUpgradesFromHand}，
 * 由它路由进组件的升级输入槽（{@code UpgradeInventorySlot.input}，canInsert 谓词只收
 * {@code getSupportedUpgrade()} 集合内的 {@code IUpgradeItem}），走 20 tick 正常安装路径。
 * 本测试钉三件事：
 * <ol>
 *   <li><b>源码形态</b>：分支不得出现 {@code addUpgrades(}，必须把 held 交给类型感知入口；
 *       入口必须先取类型（{@code getUpgradeType(held)}）再插入槽位，且不得绕过槽位直接改计数。</li>
 *   <li><b>自动化类型</b>：源码必须用 {@code AutomationType.MANUAL} —— 与 GUI 路径一致
 *       （{@code InventoryContainerSlot.insertItem} 字节码实测就是 MANUAL）。
 *       <b>这不是功能必需</b>：槽的 canInsert 是类型谓词、完全不看 automation（INTERNAL 同样能插），
 *       manualOnly 落在 canExtract 上（自动化抽不走卡）。</li>
 *   <li><b>槽位契约（真行为）</b>：拿真的 {@code UpgradeInventorySlot} 跑一遍 ——
 *       非升级物品被拒收（返回 0 = 调用方不消耗）。</li>
 * </ol>
 *
 * <h3>为什么「类型不匹配的升级卡被拒收」只能钉源码</h3>
 * 该断言需要一枚 {@code IUpgradeItem} 物品：Mek 的升级物品在裸 JVM 里注册不出来
 * （{@code MekanismItems} 走 {@code DeferredRegister}，注册事件不触发），而自造
 * {@code Item} 子类会在 {@code Item} 构造器里撞上
 * {@code IllegalStateException: Registry is already frozen}（Forge 在 bootstrap 时冻结注册表，
 * 实测）。所以「canInsert 只认 supported 集合」这条由源码断言
 * （{@code supports(} + {@code getUpgradeType(held)}）与 Mek 的字节码证据共同兜底，
 * 真行为只覆盖「非升级物品」这一档。
 *
 * <h3>为什么真 tile 造不出来</h3>
 * {@code UniversalCuttingMachineTile} 的构造链要方块与 Mek 的 Attribute 注册表，
 * 裸 JVM 里造不出来（同 {@code TestMekCkSlot} 的类注释）。
 */
public class TestCuttingMachineUpgradeTypeGate {

    private static final String HANDLER =
            "src/main/java/cn/ism/mekck/upgrade/UpgradeInstallHandler.java";
    private static final String TILE =
            "src/main/java/cn/ism/mekck/machine/cutting/UniversalCuttingMachineTile.java";

    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Forge 网络钩子在未变换的 classpath 上必然失败；注册表此时已就绪（同 TestMekCkSlot）。
        }
    }

    private static final IContentsListener NOOP = () -> {
    };

    // ── 源码形态：分支不得类型盲装 ──────────────────────────────────────

    @Test
    public void handlerBranchRoutesHeldIntoTypeAwareEntryPoint() throws Exception {
        String src = TestSourceText.read(HANDLER);
        String method = TestSourceText.methodBody(src,
                "public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {");
        assertFalse("找不到 onRightClickBlock 方法体（改名了就同步更新本测试）", method.isEmpty());
        String branch = TestSourceText.methodBody(method,
                "if (be instanceof cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile m) {");
        assertFalse("找不到切菜机分支（改名了就同步更新本测试）", branch.isEmpty());

        assertFalse("切菜机分支不得直接改组件计数：addUpgrades(Upgrade, int) 签名里没有 ItemStack，"
                        + "既不校验类型也不消耗物品 —— 任意 upgradeLike 物品都会被装成速度卡",
                branch.contains("addUpgrades("));
        assertTrue("切菜机分支必须把手持物品交给 tile 的类型感知入口：addUpgradesFromHand(held)",
                branch.contains("addUpgradesFromHand(held)"));
    }

    @Test
    public void tileEntryPointExtractsTypeBeforeRoutingIntoUpgradeSlot() throws Exception {
        String src = TestSourceText.read(TILE);
        String method = TestSourceText.methodBody(src,
                "public int addUpgradesFromHand(ItemStack held) {");
        assertFalse("找不到 addUpgradesFromHand（改名了就同步更新本测试）", method.isEmpty());

        int typeAt = method.indexOf("getUpgradeType(held)");
        int insertAt = method.indexOf("insertItem(");
        assertTrue("必须先取类型：IUpgradeItem.getUpgradeType(held)", typeAt >= 0);
        assertTrue("必须路由进组件的升级输入槽（20 tick 正常安装路径）：getUpgradeSlot()",
                method.contains("getUpgradeSlot()"));
        assertTrue("必须走槽位插入（类型校验由槽自己的 canInsert 谓词兜底）", insertAt >= 0);
        assertTrue("取类型必须在插入之前（先判类型、再安装）", typeAt < insertAt);
        assertTrue("类型必须在 supported 集合内（类型闸门）", method.contains("supports("));
        assertTrue("插入必须用 AutomationType.MANUAL —— 与 GUI 路径一致"
                        + "（InventoryContainerSlot.insertItem 字节码实测就是 MANUAL）；"
                        + "注意这不是功能必需：槽的 canInsert 是类型谓词、不看 automation",
                method.contains("AutomationType.MANUAL"));
        assertFalse("不得绕过槽位直接改组件计数", method.contains("addUpgrades("));
    }

    // ── 槽位契约：真 UpgradeInventorySlot 跑一遍 ────────────────────────

    /**
     * 复刻 tile 的插入调用（同一 Action/AutomationType），返回移入数量。
     * 槽位对象是真的 —— 这条断言把「路由进升级槽 = 有类型校验的槽」从源码读数
     * 升级成可执行证据：换成不校验的槽（如 {@code alwaysTrueBi}），本断言会红。
     */
    private static int insertInto(UpgradeInventorySlot slot, ItemStack held, AutomationType automation) {
        ItemStack toInsert = held.copy();
        ItemStack leftover = slot.insertItem(toInsert, Action.EXECUTE, automation);
        return toInsert.getCount() - leftover.getCount();
    }

    @Test
    public void upgradeSlotRejectsNonUpgradeItems() {
        UpgradeInventorySlot speedOnly = UpgradeInventorySlot.input(NOOP, Set.of(Upgrade.SPEED));
        assertEquals("非升级物品 → 拒收（返回 0 = 调用方不消耗）",
                0, insertInto(speedOnly, new ItemStack(Items.BREAD), AutomationType.MANUAL));
    }
}
