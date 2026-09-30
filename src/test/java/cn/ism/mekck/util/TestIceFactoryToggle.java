package cn.ism.mekck.util;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 制冰工厂注册开关的护栏。
 *
 * <h3>为什么它曾是 {@code static final boolean = false}，以及为什么不能就这么留着</h3>
 * 写死的常量有个隐蔽坏处：<b>把它改成 {@code true} 只能靠改源码 + 重新发版</b>，
 * 服务器管理员无法在配置里调整；而更糟的是它旁边那段长注释<b>把开关说成了不敢开</b>，
 * 理由是「缺 12 张战利品表」与「byte 下标存档会丢数据」。第四轮逐条查证后，
 * 这两条<b>都不成立</b>：
 * <ul>
 *   <li>破坏掉落走 {@code IceFactoryBlock.onRemove}（{@code saveToItem} + {@code dropItemStack}），
 *       且 {@code getDrops} 被覆写成空 ⇒ <b>战利品表永不被查询</b>，补表只会变成死文件。
 *       1.20.1 的破坏链路是 {@code Block.dropResources} → {@code BlockStateBase.getDrops(Builder)}
 *       → {@code BlockBehaviour.getDrops(BlockState, LootParams.Builder)}，正是被覆写的那个。</li>
 *   <li>{@code BigStackItemHandler} 存槽下标用的是 {@code putInt/getInt} + 越界检查，
 *       <b>不是 byte</b>。{@code MekCkSlotNbt} 那层兜底针对的是 Mek 自己的
 *       {@code mekanism.api.DataHandlerUtils}（{@code putByte/getByte}），
 *       遗留 BE 路径根本不经过它，也就没有那个病。</li>
 * </ul>
 * 于是开关被改成配置项。下面几条断言把「改成配置项之后仍然成立」这件事钉住。
 */
public class TestIceFactoryToggle {

    private static final Path REGISTRY = Path.of("src/main/java/cn/ism/mekck/UniversalCuttingMachine.java");
    private static final Path CONFIG = Path.of("src/main/java/cn/ism/mekck/config/MekckConfig.java");

    private static String read(Path path) throws IOException {
        assertTrue("找不到源文件：" + path + "（源码测试需要在仓库根目录跑）", Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 剥掉行注释与块注释，只留代码。
     *
     * <p><b>为什么需要</b>：本类大量断言是在「某个标识符不该出现」，
     * 而 {@code UniversalCuttingMachine} 里恰好有一段<b>勘误注释</b>要引述
     * {@code public static final boolean ICE_FACTORY_ENABLED = false} 这句旧代码。
     * 不剥注释的话，判据会被自己的说明文字命中 —— 写测试的人自己也踩过一次。
     * 字符串字面量与字符字面量里的 {@code //} 不做处理：本仓这批文件里没有
     * 含这两个字符的字面量，真出现时也只是让判据偏保守（更容易判失败，不会漏）。</p>
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder out = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    /**
     * 开关必须来自配置，而不是那个 {@code static final} 常量。
     *
     * <p>这条同时挡住两种回退：改回写死常量，以及「加了配置项但没人读」
     * ——后者更难发现，因为功能表面看已经配置化了，实际仍锁死在编译期。</p>
     */
    @Test
    public void toggleComesFromConfigNotAConstant() throws IOException {
        String config = read(CONFIG);
        String code = stripComments(read(REGISTRY));

        assertTrue("MekckConfig 里没有 ice_factory.enable_ice_factory 这个配置项",
                stripComments(config).contains("\"enable_ice_factory\""));
        assertTrue("MekckConfig 没有暴露 isIceFactoryEnabled() 读取器",
                stripComments(config).contains("isIceFactoryEnabled()"));

        // 写死的常量必须已经消失：它是「开关只能改源码重发版」的根源
        assertTrue("static final ICE_FACTORY_ENABLED 还在 —— 开关仍然锁死在编译期",
                !code.contains("ICE_FACTORY_ENABLED"));
    }

    /**
     * 三个使用点（注册 / 创造栏 / 客户端屏幕绑定）都必须现读配置。
     *
     * <p>刻意<b>不留</b> static 缓存字段：字段初始化式在类首次加载时求值，
     * 可能早于配置文件被读取，那样即使加了配置项也永远拿到默认值。
     * 护栏钉住「直接调用读取器」这个写法，防止有人图方便把结果缓存回字段。</p>
     */
    @Test
    public void allThreeUseSitesReadTheAccessorDirectly() throws IOException {
        String code = stripComments(read(REGISTRY));
        int sites = count(code, "MekckConfig.isIceFactoryEnabled()) {");
        assertEquals("读配置的使用点应为 3 处（方块注册 / 创造栏 / 客户端屏幕绑定）", 3, sites);
    }

    /**
     * {@code ICE_FACTORY_MENU} 的空哨兵必须与开关绑定：
     * 开 ⇒ 走 {@code MENUS.register}，关 ⇒ 显式置 {@code null}。
     *
     * <p>曾经担心的是「{@code null} 会被 {@link IceFactoryMenu} 的 {@code super(...)} 解引用成
     * NPE」。实测那不可达：禁用时 {@code IceFactoryBlock} 根本没注册（所以服务端
     * {@code createMenu} 不会被调），{@code MenuType} 也没注册（所以客户端反射构造不了）。
     * 这条断言保证「两者真的同时成立」，别让将来的改动只动一边。</p>
     */
    @Test
    public void menuSentinelStaysBoundToTheToggle() throws IOException {
        String code = stripComments(read(REGISTRY));
        assertTrue("启用分支里没有注册制冰工厂菜单",
                code.contains("ICE_FACTORY_MENU = MENUS.register(\"ice_factory\""));
        assertTrue("禁用分支里没有把 ICE_FACTORY_MENU 置 null —— 空哨兵会变成未初始化",
                code.contains("ICE_FACTORY_MENU = null;"));
    }

    /**
     * 关掉整族时，消费者必须能容忍空的家族表 —— 否则地图上残留的制冰工厂
     * 会在存档重载时炸掉。
     *
     * <p>{@code TierInstallerHandler} 的两处查表都写了
     * {@code ro == null ? null : ro.get()}；这条断言逐字核对那个空守卫，
     * 而不是只看「有没有判空」，因为
     * {@code map.get(tier).get()} 这种写法在关闭开关后会对每个档位抛
     * {@code Registry Object not present}。</p>
     */
    @Test
    public void installerToleratesAnEmptyFamilyMap() throws IOException {
        String installer = read(Path.of("src/main/java/cn/ism/mekck/util/TierInstallerHandler.java"));

        Set<String> missing = new TreeSet<>();
        // 两处查表：basicOf(...) 与 nextBlockOf(...)
        int lookups = count(installer, "map.get(");
        int guards = count(installer, "ro == null ? null : ro.get()");
        if (lookups < 2) {
            missing.add("查表点少于预期（map.get( 出现 " + lookups + " 次），护栏可能已空转");
        }
        if (guards < 2) {
            missing.add("查表点的空守卫不足（ro == null ? null : ro.get() 出现 " + guards + " 次）");
        }
        assertEquals("TierInstallerHandler 对空家族表不再安全：\n" + String.join("\n", missing), Set.of(), missing);
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            total++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
