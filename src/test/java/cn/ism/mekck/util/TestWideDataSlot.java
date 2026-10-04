package cn.ism.mekck.util;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link WideDataSlot} —— 「32 位值如何穿过 16 位 {@code ContainerData} 通道」的护栏。
 *
 * <h3>这条测试要钉死三件事</h3>
 * <ol>
 *   <li><b>通道真的是 16 位有符号的</b>：不靠读文档，直接拿原版
 *       {@link ClientboundContainerSetDataPacket} 做字节往返，实测客户端收到什么。
 *       若哪天 Forge 把它改成 {@code writeVarInt}，第 1 条会失败并提示
 *       「拆槽已无必要，可以简化回去」。</li>
 *   <li><b>拆槽是无损的</b>：低/高两半各自穿过同一个<b>有损</b>通道、被符号扩展之后，
 *       {@link WideDataSlot#combine} 必须还原出原值。覆盖 0 / 32767 边界 /
 *       10 万 / 4 亿 / 负数 / {@link Integer#MAX_VALUE}。</li>
 *   <li><b>覆盖面</b>：不许有人只拆一半 —— 那种半吊子状态的表现是
 *       「能量条看着有反应、数字却是负的」，比完全没改还难在 review 里发现。</li>
 * </ol>
 */
public class TestWideDataSlot {

    private static final Path BLOCKENTITY_DIR = Path.of("src/main/java/cn/ism/mekck/blockentity");
    private static final Path MENU_DIR = Path.of("src/main/java/cn/ism/mekck/menu");

    /** 代表值：边界、超界、负数，以及几台真实机器的 {@code ENERGY_CAPACITY}。 */
    private static final int[] SAMPLES = {
            0, 1, 32767, 32768, 65535, 65536,
            100_000,        // SimpleMachine / UniversalCuttingMachine … 的 ENERGY_CAPACITY
            300_000,        // IceMaker
            5_000_000,      // CentralKitchen
            400_000_000,    // WineCellar
            Integer.MAX_VALUE, -1, Integer.MIN_VALUE
    };

    /** 用真实包做字节往返：写出 value，再按客户端的读法读回来。 */
    private static int throughRealPacket(int value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        // (containerId, id, value)；containerId 客户端用 readUnsignedByte 读，必须在 0~255
        new ClientboundContainerSetDataPacket(0, 0, value).write(buf);
        int got = new ClientboundContainerSetDataPacket(buf).getValue();
        buf.release();
        return got;
    }

    // ================== 1. 通道约束（反向锚定） ==================

    /**
     * 原版通道对 value 只 {@code writeShort} ⇒ 客户端拿到的是<b>符号扩展过的低 16 位</b>。
     *
     * <p>这条断言<b>故意</b>断言有损，用来反向锚定「为什么需要拆槽」。
     * 哪天 Forge 改成全宽传输，这里会失败 —— 那正是「拆槽已经没必要、可以简化回去」的信号，
     * 比默默留着一个用不上的高位槽更诚实。</p>
     */
    @Test
    public void channelIsSixteenBitSigned() {
        assertEquals("32767 恰好不溢出", 32767, throughRealPacket(32767));
        assertEquals("32768 立刻溢出成 -32768", -32768, throughRealPacket(32768));
        assertEquals("100_000 = 0x186A0 → 低 16 位 0x86A0 → 符号扩展 -31072（不是 +34464）",
                -31072, throughRealPacket(100_000));
        assertEquals("400_000_000 = 0x17D78400 → 0x8400 → -31744",
                -31744, throughRealPacket(400_000_000));
    }

    // ================== 2. 拆槽无损 ==================

    /**
     * 低位、高位各自独立地穿过上面那条<b>有损</b>通道，合并后必须无损。
     *
     * <p>关键在于 {@link WideDataSlot#low} 把服务端值转成<b>无符号</b> 0~65535：
     * {@code writeShort} 对无符号与有符号值写出去的字节相同，而客户端
     * {@code & 0xFFFF} 把符号扩展抹掉 —— 一来一回正好抵消。</p>
     */
    @Test
    public void splitSurvivesTheRealLossyChannel() {
        for (int value : SAMPLES) {
            int low = WideDataSlot.low(value);
            int high = WideDataSlot.high(value);

            // 槽值**必然**被符号扩展（32768 会变成 -32768）—— 这不是 bug，是通道的既定行为。
            // 要钉的是：低位槽到达后等于「把原无符号值强转 short 再扩展回来」。
            assertEquals("低位槽 " + low + " 经真实包后应是符号扩展形态",
                    (short) low, throughRealPacket(low));
            assertEquals("高位槽 " + high + " 经真实包后应是符号扩展形态",
                    (short) high, throughRealPacket(high));

            // 关键：combine 对两半都 & 0xFFFF 抹掉符号扩展，因此仍能无损还原。
            assertEquals("拆两槽必须无损还原（原值 " + value + "）",
                    value, WideDataSlot.combine(throughRealPacket(low), throughRealPacket(high)));
        }
    }

    /** 纯逻辑往返（不依赖网络类），锁死 {@code combine} 自身的位运算。 */
    @Test
    public void combineIsInverseOfSplit() {
        for (int value : SAMPLES) {
            assertEquals(value, WideDataSlot.combine(WideDataSlot.low(value), WideDataSlot.high(value)));
        }
        // 客户端实际拿到的是符号扩展过的形态，两半都必须能正确去符号。
        // 100_000 的低位是 0x86A0、高位是 1 ⇒ combine = 34464 | 65536 = 100_000。
        assertEquals(100_000, WideDataSlot.combine((short) 0x86A0, (short) 0x0001));
        // 只给低位时，combine 得到的就是那个 16 位无符号值本身（高位为 0 不贡献任何位）。
        assertEquals(34464, WideDataSlot.combine((short) 0x86A0, (short) 0x0000));
        assertEquals(-1, WideDataSlot.combine((short) 0xFFFF, (short) 0xFFFF));
    }

    /** {@link WideDataSlot#split} 必须与逐个取值一致（供 {@code ContainerData#get} 的 switch 用）。 */
    @Test
    public void splitReturnsBothHalves() {
        for (int value : SAMPLES) {
            int[] halves = WideDataSlot.split(value);
            assertEquals(WideDataSlot.low(value), halves[0]);
            assertEquals(WideDataSlot.high(value), halves[1]);
        }
    }

    // ================== 2b. 侧配编码 / 大流体量的往返 ==================

    /**
     * 24-bit 侧配编码与真实大罐流体量必须无损往返。
     *
     * <p>这是本轮新增两类「同型残留」的锚点：侧配是 6 面 × 4 bit = 24 bit，裸读低槽只拿得到前 4 面
     * ⇒ WEST/EAST 恒 NONE；流体量 480000 / {@code Integer.MAX_VALUE} 裸读低槽会被读成负数 ⇒
     * 菜单侧 {@code amount <= 0 → EMPTY}，满罐显示空。</p>
     */
    @Test
    public void sideConfigAndFluidAmountsRoundTrip() {
        int[] values = {
                0,                       // 6 面全 NONE
                0x3F3F3F,                // 6 面全 PUSH_OUTPUT（ordinal=3）
                0x00F000,                // 仅 1 面非零
                480_000,                 // Bioreactor FLUID_CAPACITY
                256_000,                 // IceMaker WATER_CAPACITY
                32767, 32768
        };
        for (int value : values) {
            assertEquals("拆两槽必须无损还原（值 " + value + "）",
                    value, WideDataSlot.combine(
                            throughRealPacket(WideDataSlot.low(value)),
                            throughRealPacket(WideDataSlot.high(value))));
        }
        // 回归锚点：WEST(ordinal 4)/EAST(ordinal 5) 位于 bit16..23，只读低槽会得到 0 —— 旧实现
        // 的「WEST/EAST 恒 NONE」正是这么来的。
        int westEastPush = (3 << 16) | (3 << 20);
        assertEquals("WEST/EAST 全在高 16 位：低槽为 0", 0, WideDataSlot.low(westEastPush));
        assertEquals("WEST/EAST 必须靠高位槽才能读回", westEastPush, WideDataSlot.combine(
                throughRealPacket(WideDataSlot.low(westEastPush)),
                throughRealPacket(WideDataSlot.high(westEastPush))));
    }

    // ================== 3. 覆盖面：不许只拆一半 ==================

    private static String read(Path path) throws IOException {
        assertTrue("找不到源文件：" + path + "（源码测试需要在仓库根目录跑）", Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /**
     * 每个把能量放进 {@code ContainerData} 的 BE，都必须同时提供高 16 位槽。
     *
     * <p><b>判定为什么写成这样</b>：有些 BE 的 switch 用命名常量
     * （{@code case DATA_ENERGY ->}），另一些（{@code SmartCookingPot} / {@code SkeweringMachine}）
     * 用<b>字面量</b>（{@code case 2 -> energy.getEnergyStored()}）—— 只认命名常量会漏掉两台，
     * 而漏掉的正是最难在 review 里发现的那类（同一个槽位在两处各写各的数字）。
     * 所以这里直接认「能量被塞进 ContainerData」这个<b>行为</b>，两种写法都收。</p>
     *
     * <p>除了槽位声明，还要求真的实现了高 16 位的<b>产出</b>（{@code >>> 16}）——
     * 光声明常量不产出等于没拆。</p>
     */
    @Test
    public void everyEnergySlotHasItsHighHalf() throws IOException {
        Set<String> missing = new TreeSet<>();
        int seen = 0;
        Pattern syncsEnergy = Pattern.compile(
                "case\\s+(?:DATA_ENERGY|\\d+)\\s*->\\s*energy\\.getEnergyStored\\(\\)");
        for (Path file : javaFiles(BLOCKENTITY_DIR)) {
            String src = read(file);
            if (!syncsEnergy.matcher(src).find()) {
                continue;
            }
            seen++;
            boolean declared = src.contains("DATA_ENERGY_HI");
            boolean produced = src.contains(">>> 16") || src.contains("WideDataSlot.high(");
            if (!declared || !produced) {
                missing.add(file.getFileName().toString()
                        + (declared ? "" : "（缺 DATA_ENERGY_HI 槽）")
                        + (produced ? "" : "（高位没产出）"));
            }
        }
        // 阈值跟着「仍在旧 BE 形态上的机器数」走：每台迁到 Mek 原生体系就少一台
        // （能量改走 Mek 的能量容器通道，不再经 ContainerData 拆位）。
        // 电力研磨机于阶段 3 样板迁移中离开本扫描：10 → 9。
        // **这不是放宽判据**：下面那条 missing 断言对扫到的每一台逐条生效，
        // 「塞进 16 位通道却不拆高位」这条规则一个字没改。
        assertTrue("一个同步能量的 BE 都没扫到，判据失效了", seen >= 9);
        assertEquals("这些 BE 把能量塞进了 16 位通道却没拆高位 —— 客户端拿到的仍是被截断的负数：\n",
                Set.of(), missing);
    }

    /**
     * 每个菜单的 {@code getEnergy()} 都必须走 {@link WideDataSlot#read}。
     *
     * <p><b>只看方法体</b>：整文件匹配会误伤 —— 菜单里别处出现 {@code data.get(2)}
     * 完全正常（进度、侧面配置等），只有 {@code getEnergy()} 自己在读哪个槽才作数。
     * 方法体按花括号配对截取，多行实现也能覆盖
     * （{@code UniversalCuttingMachineMenu} 那批菜单的实现体就是一行
     * {@code return data.get(2);}）。</p>
     */
    @Test
    public void noMenuReadsEnergyFromASingleSlot() throws IOException {
        Set<String> offenders = new TreeSet<>();
        int seen = 0;
        for (Path file : javaFiles(MENU_DIR)) {
            String src = read(file);
            String body = methodBody(src, "public int getEnergy()");
            if (body == null) {
                continue; // 没有 int 版 getEnergy 的不参与（GrillMenu 读 Mek 能量容器，走的是另一套通道）
            }
            seen++;
            if (body.contains("data.get(") && !body.contains("WideDataSlot.read")) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue("一个 getEnergy() 都没扫到，判据失效了", seen >= 10);
        assertEquals("这些菜单的 getEnergy() 仍在单槽裸读（只有低 16 位，会变成负数）：\n",
                Set.of(), offenders);
    }

    // ================== 4. 侧配 / 流体量 / 容量的覆盖面 ==================

    /**
     * 4-bit 侧配家族的菜单 {@code getEncodedSideConfig()} 必须走 {@link WideDataSlot#read}。
     *
     * <p>判据沿用 {@link #noMenuReadsEnergyFromASingleSlot()}：只看方法体，避免误伤别处的
     * {@code data.get}。2-bit 家族（12 bit，装得下 16 位）可单槽，列入白名单。</p>
     *
     * <p>{@code IceFactoryMenu} 原先以「方块未注册、不可达」豁免 —— 该理由不成立：
     * {@code ice_factory.enable_ice_factory} 是普通配置项，翻开即注册（见
     * {@code TestIceFactoryToggle}），因此它已移出白名单并随 BE 一起拆槽。</p>
     */
    @Test
    public void noFourBitMenuReadsSideConfigFromASingleSlot() throws IOException {
        // ElectricGrindingMachineMenu 已于阶段 3 样板迁移中移出：
        // 它随整机迁到 Mek 的 MekanismTileContainer，不再有 getEncodedSideConfig()（侧配由
        // Mek 的 configComponent 承担），因此它不再进本扫描 —— 白名单留着会变成「陈旧豁免」。
        Set<String> allowedSingleRead = Set.of(
                "SmartCookingPotMenu.java",         // 2-bit 家族
                "SkeweringMachineMenu.java",        // 2-bit 家族
                "PlantingCuttingStationMenu.java"); // 2-bit 家族
        Set<String> offenders = new TreeSet<>();
        int seen = 0;
        for (Path file : javaFiles(MENU_DIR)) {
            String src = read(file);
            String name = file.getFileName().toString();
            String body = methodBody(src, "public int getEncodedSideConfig()");
            if (body == null) {
                continue;
            }
            seen++;
            if (body.contains("data.get(") && !body.contains("WideDataSlot.read")
                    && !allowedSingleRead.contains(name)) {
                offenders.add(name);
            }
        }
        assertTrue("一个 getEncodedSideConfig() 都没扫到，判据失效了", seen >= 8);
        assertEquals("这些菜单的 getEncodedSideConfig() 仍在单槽裸读（24-bit 编码会丢 WEST/EAST 两面）：\n",
                Set.of(), offenders);
    }

    /**
     * 大罐（容量 &gt; 32767）的菜单流体 getter 必须走 {@link WideDataSlot#read}。
     *
     * <p>只列容量确定超界的菜单；如实测的 8000 罐（ChocolateCannon）等仍可单槽。</p>
     */
    @Test
    public void noWideTankMenuReadsFluidAmountFromASingleSlot() throws IOException {
        String[][] wideTanks = {
                {"BioreactorMenu.java", "public FluidStack getFluidStack()"},          // 480,000
                {"SmartCookingPotMenu.java", "public FluidStack getFluidStack(int tankIndex)"}, // MAX_VALUE
                {"IceMakerMenu.java", "public FluidStack getWaterStack()"},            // 256,000
                {"IceFactoryMenu.java", "public FluidStack getWaterStack()"},          // 256,000
        };
        for (String[] t : wideTanks) {
            String body = methodBody(read(MENU_DIR.resolve(t[0])), t[1]);
            assertNotNull(t[0] + " 里找不到 " + t[1] + "（改名了就同步更新本测试）", body);
            assertTrue(t[0] + " 的流体量仍在单槽裸读（>32767 会变负数 → 满罐显示空）",
                    body.contains("WideDataSlot.read"));
        }
    }

    /** 陈酿窖容量必须取客户端已知常量，而非 16 位通道里的 4 亿（会读成负数）。 */
    @Test
    public void wineCellarCapacityComesFromConstantNotADataSlot() throws IOException {
        String body = methodBody(read(MENU_DIR.resolve("WineCellarMenu.java")),
                "public int getEnergyCapacity()");
        assertNotNull("WineCellarMenu 里找不到 getEnergyCapacity()", body);
        assertTrue("陈酿窖容量必须返回 WineCellarBlockEntity.ENERGY_CAPACITY 常量",
                body.contains("WineCellarBlockEntity.ENERGY_CAPACITY"));
        assertTrue("getEnergyCapacity() 不得再读 DATA_CAPACITY 槽（4 亿经 16 位通道为负数）",
                !body.contains("data.get("));
    }

    /** 按花括号配对截取 {@code signature} 之后的方法体；找不到返回 null。 */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) {
            return null;
        }
        int brace = src.indexOf('{', start);
        if (brace < 0) {
            return null;
        }
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) {
                    return src.substring(brace, i + 1);
                }
            }
        }
        return null;
    }
}
