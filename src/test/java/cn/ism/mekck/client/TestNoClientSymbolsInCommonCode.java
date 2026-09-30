package cn.ism.mekck.client;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/**
 * <b>专用服务器符号隔离</b>护栏 —— common 侧的类不得出现客户端符号引用。
 *
 * <h3>为什么需要它</h3>
 * 本模组绝大多数代码跑在双端，但它是一个 Forge 模组：专用服务端运行在 vanilla
 * <b>server jar</b> 上，classpath 里<b>根本没有 {@code net.minecraft.client.*}</b>。
 * 只要任何一个「双端都要链接」的类里出现了对客户端类的<b>符号引用</b>，
 * 专用服务端就可能在类链接期 {@code NoClassDefFoundError} 而<b>起不来</b>。
 *
 * <p>第三轮审查实测到两处漏网：{@code network/NetworkRecipeListPacket} 与
 * {@code network/NetworkMissingPacket} 的 {@code handle} 里直接写了
 * {@code Minecraft.getInstance()}。它们的危害等级最高，因为网络包是在
 * {@code FMLCommonSetupEvent}（<b>客户端与专用服务端都触发</b>）里统一注册的，
 * 也就是说这两个类在服务端一定会被链接。</p>
 *
 * <h3>判据：包名，不是 {@code @OnlyIn} 标记</h3>
 * 本测试按**包**划分，而不是去找 {@code @OnlyIn} 注解：
 * <ul>
 *   <li>必须隔离的包：{@code network}、{@code machine}、{@code blockentity}、{@code menu}、
 *       {@code block}、{@code item}、{@code recipe}、{@code upgrade}、{@code util}、
 *       {@code kitchen}、{@code entity}、{@code effect}、{@code advancement}、
 *       {@code integration}、{@code config}、{@code command}、{@code api}、{@code ae2}、
 *       {@code fluid}、{@code world}、以及根包 {@code cn.ism.mekck}；</li>
 *   <li>允许出现客户端符号的包：{@code client} 及其子包。</li>
 * </ul>
 * 判据按包走而不是按 {@code @OnlyIn} 走，是因为本仓库<b>已有明确的成文规矩</b>——
 * {@code item/ItemAtomicKnife.java:67} 写着「专用服务器安全：客户端渲染逻辑全部隔离在
 * {@code @OnlyIn(CLIENT)} 的实现类」，而 {@code util/GuideMECompat} +
 * {@code client/GuideMECompatImpl} 就是它的样板。规矩既然存在，就该有测试钉住。
 *
 * <h3>已知的、刻意放行的两处</h3>
 * <ul>
 *   <li>{@code item/MekCkBlockItem} 与 {@code item/BioreactorBlockItem}：仍直接 import
 *       {@code mekanism.client.key.*}，只靠 HotSpot 的<b>惰性解析</b>侥幸不崩。
 *       已列入 {@link #KNOWN_VIOLATIONS}，等把它们挪到客户端门面后从该清单里删掉。
 *       这类「今天能跑、明天可能被一个 coremod 或剥离式构建打爆」的状态正是本测试
 *       想逐步消灭的：<b>清单非空就说明债还在</b>。</li>
 *   <li>{@code mixin} 包：Mixin 目标由 {@code mekck.mixins.json} 声明，与本判据正交，
 *       整包排除。</li>
 * </ul>
 */
public class TestNoClientSymbolsInCommonCode {

    /** 允许持有 {@code net.minecraft.client.*} / {@code com.mojang.blaze3d.*} 符号的包前缀。 */
    private static final List<String> CLIENT_PACKAGE_PREFIXES = List.of(
            "cn.ism.mekck.client"
    );

    /** 整包排除的目录前缀（与本判据正交），每条都要写明理由。 */
    private static final List<String> EXCLUDED_PATH_PREFIXES = List.of(
            // Mixin 的目标由 mekck.mixins.json 声明，remap 策略与本判据正交。
            "src/main/java/cn/ism/mekck/mixin",
            // JEI 插件：JEI 本身是纯客户端模组，@JeiPlugin 类只被 JEI 在**客户端**扫描加载，
            // 服务端根本不会碰它们（所以这些类引用客户端 API 是安全的、也是 JEI 的固有形状）。
            // 真正要防的是「双端都要链接的注册路径上出现客户端引用」—— 那是 network/ 的形态，
            // 由上面的主断言覆盖。
            "src/main/java/cn/ism/mekck/integration/jei"
    );

    /** common 侧禁止出现的客户端类引用前缀。 */
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "net.minecraft.client.",
            "com.mojang.blaze3d.",
            "cn.ism.mekck.client.",
            // Mekanism 把按键处理放在 client 包下，而 MekCkBlockItem 这类**注册期就要加载**
            // 的 Item 直接 import 了它（见 KNOWN_VIOLATIONS）。专用服务器同样没有
            // mekanism.client.*，所以它和上面三个是同一类风险，必须一并检查。
            "mekanism.client."
    );

    /**
     * 已知违规 —— 债的清单，<b>非空即表示仍有代码靠「类加载器惰性解析」侥幸不崩</b>。
     *
     * <p>每一条都记着为什么还没修、修起来为什么不是一行的事，修完请连同本清单一起删。</p>
     *
     * <p><b>为什么本测试按「import」而不是「字节码引用」判</b>：Java 的 import 是
     * <b>文件作用域</b>的，所以本文件只要有一个<b>嵌套的</b> {@code @OnlyIn(CLIENT)} 类用了客户端
     * 符号，外层就必须 import 它——哪怕外层类体里一次都没用到，import 也删不掉（删了编译不过）。
     * 也就是说「有 import」不等于「有符号引用」。逐类做字节码常量池分析成本太高，
     * 这里的取舍是：<b>用 import 当廉价代理，再用下面的 {@link #doubleSidedRegistrationPathIsClean()}
     * 把真正会崩的那条路径单独、精确地钉住</b>。</p>
     */
    private static final List<String> KNOWN_VIOLATIONS = List.of(
            // ① Item 基类直接 import mekanism.client.key.*。只靠 HotSpot 对方法体里类引用的
            //    惰性解析才不崩（appendHoverText 只在客户端被调）。项目自己在
            //    item/ItemAtomicKnife.java:67 写了规矩「客户端渲染逻辑全部隔离在 @OnlyIn(CLIENT)
            //    的实现类」，这两处违反了它。修法：挪到 client 侧门面（现成样板：
            //    util/GuideMECompat + client/GuideMECompatImpl）。
            "src/main/java/cn/ism/mekck/item/MekCkBlockItem.java",
            "src/main/java/cn/ism/mekck/item/BioreactorBlockItem.java",

            // ② 5 个遗留 BE 的 clientTick 静态方法里 import mekanism.client.sound.SoundHandler。
            //    **这一类比 ① 更难修**：clientTick 是 Forge BlockEntityType.getTicker 契约要求的
            //    静态方法，必须留在 BE 类上；而 Mek 自己的 tile 类里查不到同型写法
            //    （实测映射 jar：引用 SoundHandler 的 tile 类只有 2 个，都不含 clientTick），
            //    也就是说 Mek 走了另一条路。改它需要先给这 5 个机器换一套 ticker 兼容类。
            //    在那之前它同样是「靠惰性解析侥幸不崩」的债。
            //    （电力烧烤架已从本清单移除：它迁到 TileEntityConfigurableMachine 之后
            //      clientTick 整个删掉，音效改由方块的 AttributeSound 接管。）
            "src/main/java/cn/ism/mekck/blockentity/SmartCookingPotBlockEntity.java",
            "src/main/java/cn/ism/mekck/blockentity/SkeweringMachineBlockEntity.java",
            "src/main/java/cn/ism/mekck/blockentity/PlantingCuttingStationBlockEntity.java",
            "src/main/java/cn/ism/mekck/blockentity/UniversalCuttingMachineBlockEntity.java",
            "src/main/java/cn/ism/mekck/blockentity/ElectricGrindingMachineBlockEntity.java",

            // ③ 根包注册类 import 了 7 个 client 类的 Screen / Renderer，但**只**在嵌套的
            //    @Mod.EventBusSubscriber(Dist.CLIENT) 类（ClientWorldEvents / ClientEvents）里用。
            //    外层类体一次都没用到 ⇒ 不产生任何符号引用 ⇒ 运行时安全；import 是嵌套类
            //    编译所必需、删不掉。列入清单只是为了「知道它在」，不是缺陷。
            "src/main/java/cn/ism/mekck/UniversalCuttingMachine.java"
    );

    /**
     * <b>双端注册路径必须完全干净</b> —— 这条才是会真的把专用服打崩的形态。
     *
     * <p>{@code network/} 下的包在 {@code FMLCommonSetupEvent}（客户端与专用服务端都触发）
     * 里统一注册，所以这些类在服务端<b>一定</b>会被链接。第三轮审查实测到
     * {@code NetworkRecipeListPacket} 与 {@code NetworkMissingPacket} 的 {@code handle} 里
     * 直接写了 {@code Minecraft.getInstance()}。本断言对该目录<b>不给任何例外</b>：
     * 真要放客户端逻辑，走 {@code util/ClientPacketBridge} 那个反射门面。</p>
     */
    @Test
    public void doubleSidedRegistrationPathIsClean() throws IOException {
        Path root = Paths.get("src", "main", "java", "cn", "ism", "mekck", "network");
        assertTrue("network 源码目录不存在：护栏在空转", Files.isDirectory(root));

        List<String> violations = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORTS) {
                        if (trimmed.contains(forbidden)) {
                            violations.add(path.getFileName() + "  →  " + trimmed);
                            break;
                        }
                    }
                }
            }
        }
        assertTrue("没有扫到任何 network 包：护栏在空转", scanned > 0);
        assertTrue("network/ 的包在双端注册路径上，不得 import 客户端类；"
                        + "真要放客户端逻辑请走 util/ClientPacketBridge 反射门面：\n  "
                        + String.join("\n  ", violations),
                violations.isEmpty());
    }

    @Test
    public void commonCodeReferencesNoClientClasses() throws IOException {
        Path root = Paths.get("src", "main", "java", "cn", "ism", "mekck");
        assertTrue("源码根不存在：护栏在空转", Files.isDirectory(root));

        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String rel = path.toString().replace('\\', '/');
                if (isExcluded(rel) || isClientPackage(rel) || KNOWN_VIOLATIONS.contains(rel)) {
                    continue;
                }
                scanned++;
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    // 只看 import：全限定名的使用（rare）由人工评审把守，import 是主要入口，
                    // 也是编译器必然保留的符号引用。
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN_IMPORTS) {
                        if (trimmed.contains(forbidden)) {
                            violations.add(rel + "  →  " + trimmed);
                            break;
                        }
                    }
                }
            }
        }

        assertTrue("没有扫到任何 common 侧 .java：护栏在空转（检查路径）", scanned > 0);
        assertTrue("common 侧代码引用了客户端类（专用服务端会在类链接期 NoClassDefFoundError）：\n  "
                        + String.join("\n  ", violations),
                violations.isEmpty());
    }

    /**
     * 已知违规清单必须被逐条登记 —— 有人从清单里删条目时，至少要看见这个断言。
     *
     * <p>它不检查「清单为空」，而是检查「清单里的每一条都真的还存在违规」：
     * 删掉一条却没修代码，意图是收敛债；可一旦代码被修好却忘了删条目，
     * 清单会骗你说「还有债」从而掩盖新违规。</p>
     */
    @Test
    public void everyKnownViolationStillActuallyViolates() throws IOException {        List<String> stale = new ArrayList<>();
        for (String rel : KNOWN_VIOLATIONS) {
            Path path = Paths.get(rel);
            if (!Files.isRegularFile(path)) {
                stale.add(rel + "  →  文件已不存在，请从 KNOWN_VIOLATIONS 里删掉");
                continue;
            }
            boolean stillViolates = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> l.startsWith("import "))
                    .anyMatch(l -> FORBIDDEN_IMPORTS.stream().anyMatch(l::contains));
            if (!stillViolates) {
                stale.add(rel + "  →  已不再违规，请从 KNOWN_VIOLATIONS 里删掉");
            }
        }
        assertTrue("KNOWN_VIOLATIONS 里有陈旧条目：\n  " + String.join("\n  ", stale), stale.isEmpty());
    }

    private static boolean isExcluded(String relPath) {
        for (String prefix : EXCLUDED_PATH_PREFIXES) {
            if (relPath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClientPackage(String relPath) {
        for (String prefix : CLIENT_PACKAGE_PREFIXES) {
            if (relPath.contains(prefix.replace('.', '/') + "/")) {
                return true;
            }
        }
        return false;
    }
}
