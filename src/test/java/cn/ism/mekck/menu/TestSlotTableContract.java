package cn.ism.mekck.menu;

import cn.ism.mekck.menu.slot.MekCkSlots;
import cn.ism.mekck.menu.slot.MekCkSlotTable;
import cn.ism.mekck.menu.slot.SlotDef;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 槽位表护栏 —— 钉死「一个框只能有一个坐标」。
 *
 * <h3>为什么需要它（2026-10-03 实机定位）</h3>
 * 重写前同一个槽有多套坐标：能源槽有 {@code 7,13}（菜单）、{@code 6,12}（屏幕）、
 * {@code 7,12}（另两个屏幕）、{@code 7,13}（基类常量）四套值；输入/输出槽在菜单与屏幕间
 * 差 {@code (1,1)} 与 {@code (0,1)}；而偏移规则本身也不统一——同一个「输出槽」，
 * 制冰机系是 {@code (0,-1)}、穿串系是 {@code (-1,-1)}。
 *
 * <p>根因是菜单用手写的 {@code SlotItemHandler}（Mek 不认，不建 widget），
 * 屏幕只好用 {@code GuiVirtualSlot} 再画一份，两份坐标靠人工 ±1 凑合。
 * {@link MekCkSlotTable} 把坐标收敛成唯一出处，本测试守住这条契约不被重新破坏。</p>
 *
 * <h3>本文件的判据分三层</h3>
 * <ol>
 *   <li><b>表内自洽</b>：坐标不重复、不为负（{@link MekCkSlotTable#validate} 已实现，此处复验）；</li>
 *   <li><b>表被真正使用</b>：已迁移的菜单必须从表里取坐标，不得再写死；</li>
 *   <li><b>不再出现凑合偏移</b>：已迁移的屏幕不得再有 {@code GuiVirtualSlot} 手画槽。</li>
 * </ol>
 *
 * <p>第 2、3 层按「已迁移清单」逐台推进——每迁一台就往清单里加一条，
 * 未迁移的机器暂不纳入，避免一次性红一片。</p>
 */
public class TestSlotTableContract {

    private static final Path MENU_DIR = Path.of("src/main/java/cn/ism/mekck/menu");
    private static final Path CLIENT_DIR = Path.of("src/main/java/cn/ism/mekck/client");

    /**
     * 已迁移到槽位表的机器 —— <b>每迁一台就在这里加一行</b>。
     *
     * <p>格式：{@code 菜单类名 | 屏幕类名}。屏幕名为空表示该机器没有独立屏幕。</p>
     */
    private static final String[][] MIGRATED = {
            {"WineCellarMenu", "WineCellarScreen"},
    };

    private static String read(Path p) throws IOException {
        assertTrue("找不到源文件：" + p + "（本测试需在仓库根目录运行）", Files.isRegularFile(p));
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ── 1. 表内自洽 ──────────────────────────────────────────────────

    /**
     * 陈酿机的槽位表必须自洽：9 个存储格 + 1 个能源槽，坐标互不相同。
     *
     * <p>不变量由 {@code MekCkSlots.WineCellar} 的静态块调用
     * {@link MekCkSlotTable#validate} 保证；本测试把「表被正确构造」钉成断言，
     * 免得将来有人把静态块里的 validate 删掉而无人察觉。</p>
     */
    @Test
    public void wineCellarTableIsConsistent() {
        List<SlotDef> all = MekCkSlots.WineCellar.ALL;
        assertEquals("陈酿机应有 9 存储 + 1 能源 = 10 个槽", 10, all.size());
        assertEquals("存储格应为 9 个", 9, MekCkSlots.WineCellar.STORAGE.size());

        // 复验坐标唯一（validate 之外再钉一次，防 validate 被删）
        List<String> positions = new ArrayList<>();
        for (SlotDef d : all) {
            String pos = d.x() + "," + d.y();
            assertFalse("槽位坐标重复：" + pos + "（涉及 " + d.key() + "）", positions.contains(pos));
            positions.add(pos);
        }
    }

    /**
     * 陈酿机的能源槽坐标必须是 {@code (6,12)} —— 与迁移前的菜单侧值逐位一致。
     *
     * <p>钉这一条的理由：迁移的原则是「以菜单侧为准、不动已上线手感」。
     * 若有人顺手把它改成工厂那套 {@code (7,13)}，所有玩家的酒窖能源槽会平移 1px，
     * 那是一次没有理由的行为变更。真要统一，应当由独立提交处理并说明原因。</p>
     */
    @Test
    public void wineCellarPowerSlotKeepsMenuSideCoordinate() {
        SlotDef power = MekCkSlots.WineCellar.POWER;
        assertEquals("能源槽 x 必须保持菜单侧原值 6", 6, power.x());
        assertEquals("能源槽 y 必须保持菜单侧原值 12", 12, power.y());
    }

    /**
     * 存储网格首格必须是 {@code (62,18)}，且行优先（i/3 行、i%3 列）。
     */
    @Test
    public void wineCellarStorageGridIsRowMajorFrom62_18() {
        List<SlotDef> s = MekCkSlots.WineCellar.STORAGE;
        assertEquals(62, s.get(0).x());
        assertEquals(18, s.get(0).y());
        // 第 1 格（i=1）应在同一行右移 18
        assertEquals(80, s.get(1).x());
        assertEquals(18, s.get(1).y());
        // 第 3 格（i=3）应换行：回到首列、下移 18
        assertEquals(62, s.get(3).x());
        assertEquals(36, s.get(3).y());
    }

    // ── 2. 表被真正使用（已迁移的菜单不得再写死坐标）──────────────────

    /**
     * 已迁移的菜单必须从槽位表读坐标。
     *
     * <p><b>判据为什么不是「菜单源码里出现 MekCkSlots」</b>：坐标的唯一出处是
     * <b>tile</b> 的 {@code getInitialInventory}（Mek 的槽在那里建、坐标在那里写），
     * 菜单只是把它装配起来，本身可能一处都不直接引用槽位表。
     * 早期版本的本断言就是按「菜单里有没有 MekCkSlots」判的，结果：
     * 坐标明明已收敛到 tile，菜单却因为只字未提要报错——那是<b>假阳性</b>。</p>
     *
     * <p>真正该守的是「坐标只有一个出处」，所以判据改为：
     * <b>菜单里不得再出现硬编码的槽位坐标</b>（形如 {@code addSlot(new ...Slot(..., 数字, 数字))}）。
     * 迁移后的菜单根本不该有 {@code addSlot} 调用——槽由 Mek 自动装配。</p>
     */
    @Test
    public void migratedMenusDoNotHardcodeSlotCoordinates() throws IOException {
        for (String[] pair : MIGRATED) {
            String menuName = pair[0];
            String src = read(MENU_DIR.resolve(menuName + ".java"));
            // 逐行找 addSlot 调用（排除注释行）
            for (String line : src.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                    continue; // 注释里提到 addSlot 是解释性的，不算违规
                }
                assertFalse(menuName + " 里仍有手写的槽位装配：\n    " + trimmed
                                + "\n迁移后槽由 Mek 的 MekanismTileContainer 自动装配，"
                                + "菜单不应再有 addSlot —— 否则坐标又出现第二个出处",
                        trimmed.contains("addSlot("));
            }
        }
    }

    // ── 3. 已迁移的屏幕不得再手画槽（消除 ±1 凑合）────────────────────

    /**
     * 已迁移的屏幕不得再手画槽。
     *
     * <p>{@code new GuiVirtualSlot(...)} 是「Mek 不画这个槽、我手动补一份」的补偿措施，
     * 它正是两套坐标（菜单 7,13 / 屏幕 6,12）的来源。</p>
     *
     * <p><b>判据为什么必须排除注释</b>：迁移说明本身就要提到 {@code GuiVirtualSlot}
     * （「原先手画的 GuiVirtualSlot 与菜单的 Slot 各有一套坐标」），
     * 早期版本的本断言用 {@code src.contains("new GuiVirtualSlot")} 判定，
     * 被自己写的解释性注释判为违规——<b>假阳性</b>。
     * 现在逐行扫、跳过注释行，并要求出现的是<b>构造调用</b>而非仅是类名。</p>
     */
    @Test
    public void migratedScreensDoNotHandDrawSlots() throws IOException {
        for (String[] pair : MIGRATED) {
            String screenName = pair[1];
            if (screenName.isEmpty()) {
                continue;
            }
            Path p = CLIENT_DIR.resolve(screenName + ".java");
            if (!Files.isRegularFile(p)) {
                continue; // 该机器没有独立屏幕
            }
            String src = read(p);
            for (String line : src.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("import ")) {
                    continue; // 注释与 import 里的类名是解释性的，不算手画
                }
                assertFalse(screenName + " 仍在手画槽：\n    " + trimmed
                                + "\n迁移后槽应由 Mek 自动渲染（GuiMekanism.addSlots），"
                                + "手画会重新引入「一个框两套坐标」",
                        trimmed.contains("new GuiVirtualSlot("));
            }
        }
    }

    // ── 4. 判据自身不许空转 ──────────────────────────────────────────

    /**
     * 已迁移清单不能是空的 —— 否则上面三条断言全部空转、本文件变成永远绿的摆设。
     */
    @Test
    public void migratedListIsNotEmpty() {
        assertTrue("已迁移清单为空，本测试的三条断言都在空转", MIGRATED.length > 0);
    }
}
