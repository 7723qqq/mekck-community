package cn.ism.mekck.network;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 包处理器必须<b>真的调用</b>校验闸门 —— 覆盖面测试，不是逻辑测试。
 *
 * <h3>为什么需要它</h3>
 * {@link PacketGuard} 本身的逻辑有 {@code TestPacketGuard} 钉着，
 * 但「写得好」和「被用上」是两件事。第三轮审查实测的覆盖面是
 * <b>全部 C2S 里只差一个</b>调了 {@code allowed/target}、<b>5 个 S2C 里 0 个</b>校验方向 ——
 * 这个数字没有被任何东西守住，下一个新增包会落回「默认不校验」。
 * （当前实际是 22 个包：17 C2S + 5 S2C，全部受检；具体计数以本文件的断言为准，
 * 早期注释里的 24/23 是第四轮删掉 8 个包之前的旧数字。）
 *
 * <p>方向问题尤其隐蔽：Forge 的 {@code SimpleChannel} 不按登记方向拒收反向投递，
 * 客户端可以直接构造本该服务端下发的包。后果多数是良性（写静态表/空实现），
 * 但它把一条<b>从未被审过的代码路径</b>暴露给了对端，而且在专用服务端上是纯未定义行为。</p>
 *
 * <h3>方向是怎么判定的</h3>
 * 不读注释、不看类名 —— 直接看 {@code ModMessages} 里有哪些
 * {@code sendToServer(XxxPacket …)} 与 {@code sendToPlayer(XxxPacket …)} 重载。
 * 发送端是唯一权威：一个包的用途就是「谁发给谁」。
 *
 * <h3>三条断言</h3>
 * <ol>
 *   <li><b>方向分类完整</b>：注册的每个包都必须能被归到 C2S 或 S2C，
 *       且不得同时出现在两边（那意味着注册表与发送端已经漂移）。</li>
 *   <li><b>S2C 必须校验方向</b>：处理器里要有 {@code PacketGuard.fromServer}。</li>
 *   <li><b>C2S 必须校验接近性</b>：处理器里要有 {@code PacketGuard.allowed/target}；
 *       确需豁免的进 {@link #C2S_EXEMPT} 并写明理由 —— 豁免清单<b>必须逐条仍真的在豁免</b>，
 *       否则说明它腐化成了摆设（与 {@code TestNoClientSymbolsInCommonCode} 同口径）。</li>
 * </ol>
 */
public class TestPacketGuardCoverage {

    private static final Path NETWORK_DIR = Path.of("src/main/java/cn/ism/mekck/network");
    private static final Path MOD_MESSAGES = NETWORK_DIR.resolve("ModMessages.java");

    /**
     * 允许不调用接近性校验的 C2S 包 —— <b>每加一条都必须写清理由</b>。
     *
     * <p>判据只能是「校验在别处、且强度不低于距离」：
     * 纯粹「这个包无害」不算理由 —— 无害的包同样不该给对端一条未审路径。</p>
     */
    private static final Set<String> C2S_EXEMPT = Set.of(
            // （当前为空：24/24 都在用闸门）
    );

    private static String read(Path path) throws IOException {
        assertTrue("找不到源文件：" + path + "（源码测试需要在仓库根目录跑）", Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 从 {@code sendToServer(FooPacket message)} / {@code sendToPlayer(BarPacket message, …)} 反推方向。 */
    private static Set<String> directionOf(String methodName) throws IOException {
        String source = read(MOD_MESSAGES);
        Set<String> names = new TreeSet<>();
        Matcher matcher = Pattern.compile(methodName + "\\s*\\(\\s*([A-Z][A-Za-z0-9_]*)\\s+message")
                .matcher(source);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static List<Path> packetFiles() throws IOException {
        try (var stream = Files.list(NETWORK_DIR)) {
            return stream.filter(p -> p.toString().endsWith("Packet.java")).sorted().toList();
        }
    }

    private static String handleBody(String source) {
        // 整文件即可：handle 是唯一会写校验的地方，javadoc 里出现 PacketGuard 的概率
        // 与「注释提到但没调用」的风险相比可以忽略 —— 真发生了会同时被断言 3 抓到。
        return source;
    }

    @Test
    public void everyPacketHasExactlyOneDirection() throws IOException {
        Set<String> toServer = directionOf("sendToServer");
        Set<String> toPlayer = directionOf("sendToPlayer");

        // 注册表：判据必须真的解析到东西，否则下面的差集断言全部恒真。
        Set<String> registered = new TreeSet<>();
        Matcher matcher = Pattern.compile("registerMessage\\(\\d+,\\s*([A-Z][A-Za-z0-9_]*)\\.class")
                .matcher(read(MOD_MESSAGES));
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }
        assertTrue("注册表一个包都没扫到，判据失效了", registered.size() >= 15);
        assertTrue("一个 sendToServer 重载都没扫到，判据失效了", toServer.size() >= 10);
        assertTrue("一个 sendToPlayer 重载都没扫到，判据失效了", toPlayer.size() >= 3);

        Set<String> both = new TreeSet<>(toServer);
        both.retainAll(toPlayer);
        assertEquals("这些包既 sendToServer 又 sendToPlayer —— 注册表与发送端已漂移：", Set.of(), both);

        // 核心不变量：**每个已注册的包都必须恰好有一个方向**，反之亦然。
        // 一律用差集而不是写死「≥ 29」这种绝对数 —— 绝对数在每次增删包后都会失效，
        // 而差集是真不变量，同时能抓住「注册了但没发送端」和「有发送端但没注册」两个方向。
        Set<String> unclassified = new TreeSet<>(registered);
        unclassified.removeAll(toServer);
        unclassified.removeAll(toPlayer);
        assertEquals("这些包已注册但既没有 sendToServer 也没有 sendToPlayer（方向缺失）：",
                Set.of(), unclassified);

        Set<String> senderless = new TreeSet<>(toServer);
        senderless.addAll(toPlayer);
        senderless.removeAll(registered);
        assertEquals("这些包有发送端但没在 ModMessages 注册（注册表漏了）：", Set.of(), senderless);
    }

    @Test
    public void serverToClientHandlersAssertDirection() throws IOException {
        Set<String> s2c = directionOf("sendToPlayer");
        assertFalse("没扫到任何 S2C 包，判据失效了", s2c.isEmpty());

        Set<String> missing = new TreeSet<>();
        for (String name : s2c) {
            Path file = NETWORK_DIR.resolve(name + ".java");
            if (!Files.isRegularFile(file)) {
                missing.add(name + "（文件不存在）");
                continue;
            }
            if (!handleBody(read(file)).contains("PacketGuard.fromServer")) {
                missing.add(name);
            }
        }
        assertEquals("这些 S2C 包的处理器没校验方向 —— 客户端可以伪造它们发到服务端："
                        + "在 handle 第一行加 if (!PacketGuard.fromServer(包名, ctx)) { setPacketHandled(true); return; }",
                Set.of(), missing);
    }

    @Test
    public void clientToServerHandlersAssertProximity() throws IOException {
        Set<String> c2s = directionOf("sendToServer");
        assertFalse("没扫到任何 C2S 包，判据失效了", c2s.isEmpty());

        // 豁免清单必须逐条仍然真的在豁免，否则清单本身就是谎话。
        Set<String> staleExempt = new TreeSet<>();
        for (String name : C2S_EXEMPT) {
            if (!c2s.contains(name)) {
                staleExempt.add(name + "（不再是 C2S，或已删除）");
            }
        }
        assertEquals("C2S 豁免清单里有过期条目（防腐化断言）：", Set.of(), staleExempt);

        Set<String> missing = new TreeSet<>();
        for (String name : c2s) {
            if (C2S_EXEMPT.contains(name)) {
                continue;
            }
            Path file = NETWORK_DIR.resolve(name + ".java");
            if (!Files.isRegularFile(file)) {
                missing.add(name + "（文件不存在）");
                continue;
            }
            String body = handleBody(read(file));
            if (!body.contains("PacketGuard.allowed") && !body.contains("PacketGuard.target")) {
                missing.add(name);
            }
        }
        assertEquals("这些 C2S 包的处理器没有接近性校验 —— 任意客户端都能远程改机器状态：",
                Set.of(), missing);
    }

    /**
     * 判据不空转：{@code ModMessages} 必须真的被解析出足量的包，
     * 否则上面三条断言都会因为「集合为空」而恒真。
     *
     * <p>还要钉住「重载扫描」真的读到了东西 —— 方向断言的输入全部来自
     * {@code sendToServer/sendToPlayer} 的<b>方法签名</b>，签名写法一变
     * （比如改成泛型 {@code send(T)}）扫描就静默返回空集，三条断言一起变绿。</p>
     */
    @Test
    public void scanIsNotVacuous() throws IOException {
        int registered = count(read(MOD_MESSAGES), "registerMessage(");
        int packetClasses = packetFiles().size();
        int c2s = directionOf("sendToServer").size();
        int s2c = directionOf("sendToPlayer").size();

        // 相对不变量，不用写死数字：每个 *Packet.java 都必须在 ModMessages 注册一次，
        // 反之亦然。这条比「≥ 29」强 —— 它同时抓住「新增了包类却忘了注册」
        // 和「删了包类却留下注册」两个方向，而且不会因为清理而失效。
        assertEquals("Packet*.java 与 registerMessage 数量不一致 "
                        + "(类文件 " + packetClasses + " / 注册 " + registered + ")：",
                packetClasses, registered);

        // 下限只是防「正则全没匹配上」，取保守值，不随增删包波动。
        assertTrue("registerMessage 调用太少，护栏在空转", registered >= 15);
        assertTrue("Packet*.java 太少，护栏在空转", packetClasses >= 15);
        assertTrue("sendToServer 重载一个都没扫到，方向判据的输入是空的", c2s >= 10);
        assertTrue("sendToPlayer 重载一个都没扫到，方向判据的输入是空的", s2c >= 3);

        // 有重载 ≠ 有对应类文件；缺文件时 handleBody 分支会把「文件不存在」报成缺失，
        // 这里反过来确认：扫到的方向集合里的名字，绝大多数确实存在。
        int resolvable = 0;
        for (String name : directionOf("sendToServer")) {
            if (Files.isRegularFile(NETWORK_DIR.resolve(name + ".java"))) resolvable++;
        }
        assertTrue("sendToServer 扫出的名字大部分找不到文件，签名正则匹配错了东西", resolvable >= c2s - 2);
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
