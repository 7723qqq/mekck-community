package cn.ism.mekck.network;

import cn.ism.mekck.TestSourceText;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 中央厨房存储快照的<b>大堆叠计数</b>护栏 + 两个独立菜单的<b>空 BE 判空</b>护栏。
 *
 * <h3>被钉住的缺陷一：计数被截成有符号 byte（M7-M1）</h3>
 * {@code KitchenStorageSyncPacket} 原先用 {@code writeItem/readItem} 传每个 stack，
 * 而原版 {@code FriendlyByteBuf.writeItem} 内部是 {@code writeByte(getCount())}：
 * 存储槽上限 {@code BIG_STACK = Integer.MAX_VALUE - 1}，于是 300 → 44、
 * 200 → -56（{@code isEmpty()} 为真 ⇒ 整格画成空）、1000 → -24。
 * 客户端 {@code StorageSlot.getItem()} 只读这个镜像，所以显示值就是被截断的值。
 *
 * <p>修法是每个 stack 先写 {@code writeVarInt(count)}、再写计数为 1 的 stack，
 * 解码时用读到的计数 {@code setCount}（与 {@code BigStackItemHandler} 的
 * {@code McCount} 同思路）。本测试用<b>真实</b> {@code FriendlyByteBuf} 做
 * encode→decode 往返，断言 300/200/1000 与全部边界值原样还原 ——
 * 这是源码形态断言抓不到的那一类回归。</p>
 *
 * <h3>被钉住的缺陷二：客户端构造器未判空 BE（M7-m6）</h3>
 * 方块在 OpenScreen 到达前被破坏/替换、或区块被卸载时，客户端
 * {@code getBlockEntity} 返回 null：{@code CentralKitchenMenu} 经
 * {@code refreshDisplay()} 读 {@code machine.items}、{@code SandwichAssemblerMenu}
 * 直接读 {@code machine.items} ⇒ NPE 崩客户端。真菜单在裸 JVM 里造不出来
 * （构造链要 MenuType / 注册表），所以这一段按本仓惯例用<b>源码形态</b>断言钉住
 * （同 {@code TestKitchenStorageBrowserSync} / {@code TestMenuQuickMoveSlotRanges}）。</p>
 *
 * <h3>判据不许空转</h3>
 * 源码断言都要求真的匹配到方法体；{@link #criteriaStillMatchSomething()} 兜底，
 * 防止改名后断言静默恒真。
 */
public class TestKitchenStorageSyncCounts {

    private static final Path PACKET =
            Path.of("src/main/java/cn/ism/mekck/network/KitchenStorageSyncPacket.java");
    private static final Path CENTRAL_MENU =
            Path.of("src/main/java/cn/ism/mekck/menu/CentralKitchenMenu.java");
    private static final Path SANDWICH_MENU =
            Path.of("src/main/java/cn/ism/mekck/menu/SandwichAssemblerMenu.java");

    /**
     * {@code Items.*} 与 {@code ItemStack} 需要注册表就绪；Forge 网络钩子在未变换的
     * classpath 上必然失败，但注册表此时已建好（同 {@code TestMenuQuickMoveSlotRanges}）。
     */
    @BeforeClass
    public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        try {
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // 见方法注释：注册表已就绪，Forge 钩子的失败与本测试无关。
        }
    }

    /** 真实 {@code FriendlyByteBuf} 往返：encode 后按 decode 的读法读回来。 */
    private static KitchenStorageSyncPacket roundTrip(KitchenStorageSyncPacket packet) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.encode(buf);
            return KitchenStorageSyncPacket.decode(buf);
        } finally {
            buf.release();
        }
    }

    // ================== 1. 大堆叠计数往返 ==================

    /**
     * 任务书点名的三个值：300 / 200 / 1000。
     *
     * <p>200 是「整格画成空」的形态（{@code (byte)200 = -56} ⇒ {@code isEmpty()}），
     * 300 / 1000 是「显示成另一个数」的形态（44 / -24）。三者都必须原样还原。</p>
     */
    @Test
    public void largeCountsSurviveTheRoundTrip() {
        List<ItemStack> page = List.of(
                new ItemStack(Items.CARROT, 300),
                new ItemStack(Items.POTATO, 200),
                new ItemStack(Items.WHEAT, 1000));
        KitchenStorageSyncPacket decoded = roundTrip(new KitchenStorageSyncPacket(
                new BlockPos(3, 4, 5), 1, 2, 7, page));

        List<ItemStack> got = decoded.visible();
        assertEquals("可见页格数必须原样", 3, got.size());
        assertEquals("300 不能被截成 (byte)300 = 44", 300, got.get(0).getCount());
        assertEquals("200 不能被截成 (byte)200 = -56（isEmpty ⇒ 整格画成空）",
                200, got.get(1).getCount());
        assertEquals("1000 不能被截成 (byte)1000 = -24", 1000, got.get(2).getCount());
        assertEquals("物品身份必须原样", Items.CARROT, got.get(0).getItem());
        assertEquals("物品身份必须原样", Items.POTATO, got.get(1).getItem());
        assertEquals("物品身份必须原样", Items.WHEAT, got.get(2).getItem());
    }

    /**
     * 边界与全量程：0 / 1 / 127 / 128 / 255 / 256 / 32767 / 32768 / 100 万 / BIG_STACK。
     *
     * <p>127/128 是 byte 的符号分界，255/256 是「整格为空」与「显示为 0」的分界，
     * {@code Integer.MAX_VALUE - 1} 是存储槽上限（{@code CentralKitchenBlockEntity.BIG_STACK}）。</p>
     */
    @Test
    public void boundaryCountsSurviveTheRoundTrip() {
        int[] counts = {0, 1, 63, 64, 127, 128, 255, 256, 32767, 32768, 1_000_000,
                Integer.MAX_VALUE - 1};
        for (int count : counts) {
            List<ItemStack> page = count == 0
                    ? List.of(ItemStack.EMPTY)
                    : List.of(new ItemStack(Items.CARROT, count));
            KitchenStorageSyncPacket decoded = roundTrip(new KitchenStorageSyncPacket(
                    BlockPos.ZERO, 0, 0, 1, page));
            List<ItemStack> got = decoded.visible();
            assertEquals("计数 " + count + " 的页必须只有一格", 1, got.size());
            if (count == 0) {
                assertTrue("空槽必须原样为空", got.get(0).isEmpty());
            } else {
                assertEquals("计数 " + count + " 必须原样往返", count, got.get(0).getCount());
            }
        }
    }

    /** 空槽与有货槽混在一页里：空槽不能被计数编码写成「1 个」。 */
    @Test
    public void emptySlotsStayEmptyInAMixedPage() {
        List<ItemStack> page = List.of(
                ItemStack.EMPTY,
                new ItemStack(Items.CARROT, 300),
                ItemStack.EMPTY,
                new ItemStack(Items.POTATO, 128));
        KitchenStorageSyncPacket decoded = roundTrip(new KitchenStorageSyncPacket(
                BlockPos.ZERO, 0, 0, 2, page));

        List<ItemStack> got = decoded.visible();
        assertEquals(4, got.size());
        assertTrue("第 0 格必须为空", got.get(0).isEmpty());
        assertEquals(300, got.get(1).getCount());
        assertTrue("第 2 格必须为空", got.get(2).isEmpty());
        assertEquals(128, got.get(3).getCount());
    }

    /**
     * 计数编码不能顺手丢掉物品 NBT。
     *
     * <p>encode 写的是 {@code stack.copyWithCount(1)}，若哪天改成「新建一个同物品的
     * 空 stack」就会静默丢 NBT —— 耐久、附魔、自定义名全没。用可损坏物品
     * （{@code writeItem} 对它才会写 tag）钉住。</p>
     */
    @Test
    public void itemTagSurvivesTheCountEncoding() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD, 300);
        sword.setDamageValue(17);
        KitchenStorageSyncPacket decoded = roundTrip(new KitchenStorageSyncPacket(
                BlockPos.ZERO, 0, 0, 1, List.of(sword)));

        ItemStack got = decoded.visible().get(0);
        assertEquals(300, got.getCount());
        assertEquals(Items.DIAMOND_SWORD, got.getItem());
        assertEquals("NBT（耐久）必须随物品一起往返", 17, got.getDamageValue());
    }

    /**
     * 反向锚定：原版 {@code writeItem} 仍然是 byte 计数。
     *
     * <p>这条<b>故意</b>断言有损，用来解释「为什么必须自己写 VarInt 计数」。
     * 哪天 Forge/原版把 {@code writeItem} 改成全宽传输，这里会失败 ——
     * 那正是「自定义计数编码已无必要、可以简化回去」的信号，比默默留着一段
     * 用不上的旁路更诚实（同 {@code TestWideDataSlot#channelIsSixteenBitSigned}）。</p>
     */
    @Test
    public void vanillaWriteItemStillTruncatesToSignedByte() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeItem(new ItemStack(Items.CARROT, 300));
            assertEquals("原版 writeItem 把 300 截成 (byte)300 = 44", 44, buf.readItem().getCount());
        } finally {
            buf.release();
        }

        FriendlyByteBuf buf2 = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf2.writeItem(new ItemStack(Items.CARROT, 200));
            ItemStack read = buf2.readItem();
            // 注意：ItemStack#getCount() 对 isEmpty() 的 stack 恒返回 0
            // （原版实现是 `isEmpty() ? 0 : count`），所以负数计数只能从 isEmpty() 观察。
            assertTrue("200 → (byte)200 = -56 ⇒ isEmpty() 为真（整格画成空）", read.isEmpty());
            assertEquals("isEmpty 的 stack，getCount() 读作 0", 0, read.getCount());
        } finally {
            buf2.release();
        }
    }

    // ================== 2. 菜单判空（源码形态） ==================

    /**
     * {@code CentralKitchenMenu} 客户端构造器必须对 {@code getBlockEntity} 的结果判空。
     *
     * <p>旧写法 {@code (CentralKitchenBlockEntity) ...getBlockEntity(...)} 在 BE 为 null 时
     * 把 null 交给主构造器，{@code refreshDisplay()} 随即读 {@code machine.items} ⇒ NPE。
     * 用 {@code instanceof} 同时挡掉「该坐标上是别的 BE」的类型不符。</p>
     */
    @Test
    public void centralKitchenClientConstructorNullChecksTheBlockEntity() throws IOException {
        String src = TestSourceText.read(CENTRAL_MENU.toString());
        String ctor = TestSourceText.methodBody(src,
                "public CentralKitchenMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf)");
        assertTrue("客户端构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue("客户端构造器必须对 getBlockEntity 的结果判空（instanceof 同时挡掉类型不符）："
                        + "旧写法直接强转，BE 为 null 时 refreshDisplay() 读 machine.items 崩客户端",
                ctor.contains("instanceof CentralKitchenBlockEntity"));
    }

    /** {@code CentralKitchenMenu} 的读取路径必须全部走 {@code machine == null} 兜底。 */
    @Test
    public void centralKitchenReadPathsAreNullSafe() throws IOException {
        String src = TestSourceText.read(CENTRAL_MENU.toString());

        String refresh = TestSourceText.methodBody(src, "public void refreshDisplay()");
        assertTrue("refreshDisplay() 没找到，判据可能失配", !refresh.isEmpty());
        assertTrue("refreshDisplay() 必须在 machine == null 时提前返回："
                        + "空菜单的 isClientSide() 为假，不返回就会在 machine.items 上 NPE",
                refresh.contains("machine == null"));

        String dataGet = TestSourceText.methodBody(src, "public int get(int index)");
        assertTrue("ContainerData#get 没找到，判据可能失配", !dataGet.isEmpty());
        assertTrue("ContainerData#get 必须在 machine == null 时返回 0（屏幕会读这些槽）",
                dataGet.contains("machine == null"));

        String stillValid = TestSourceText.methodBody(src, "public boolean stillValid(Player player)");
        assertTrue("stillValid() 没找到，判据可能失配", !stillValid.isEmpty());
        assertTrue("stillValid() 必须在 machine == null 时返回 false",
                stillValid.contains("machine != null"));

        String quickMove = TestSourceText.methodBody(src,
                "public ItemStack quickMoveStack(Player player, int index)");
        assertTrue("quickMoveStack() 没找到，判据可能失配", !quickMove.isEmpty());
        assertTrue("quickMoveStack() 必须在 machine == null 时直接返回 EMPTY",
                quickMove.contains("machine == null"));

        String blockPos = TestSourceText.methodBody(src, "public net.minecraft.core.BlockPos getBlockPos()");
        assertTrue("getBlockPos() 没找到，判据可能失配", !blockPos.isEmpty());
        assertTrue("getBlockPos() 必须在 machine == null 时返回兜底坐标而不是 NPE",
                blockPos.contains("machine == null"));
    }

    /**
     * {@code SandwichAssemblerMenu} 客户端构造器必须对 {@code getBlockEntity} 的结果判空。
     *
     * <p>旧写法直接强转，主构造器第一行 {@code machine.items} 就 NPE。</p>
     */
    @Test
    public void sandwichAssemblerClientConstructorNullChecksTheBlockEntity() throws IOException {
        String src = TestSourceText.read(SANDWICH_MENU.toString());
        String ctor = TestSourceText.methodBody(src,
                "public SandwichAssemblerMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf)");
        assertTrue("客户端构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue("客户端构造器必须对 getBlockEntity 的结果判空（instanceof 同时挡掉类型不符）："
                        + "旧写法直接强转，主构造器第一行 machine.items 就 NPE",
                ctor.contains("instanceof SandwichAssemblerBlockEntity"));
    }

    /** {@code SandwichAssemblerMenu} 的读取路径必须全部走 {@code machine == null} 兜底。 */
    @Test
    public void sandwichAssemblerReadPathsAreNullSafe() throws IOException {
        String src = TestSourceText.read(SANDWICH_MENU.toString());

        String ctor = TestSourceText.methodBody(src,
                "public SandwichAssemblerMenu(int containerId, Inventory playerInventory,"
                        + " SandwichAssemblerBlockEntity machine)");
        assertTrue("主构造器没找到，判据可能失配", !ctor.isEmpty());
        assertTrue("主构造器必须给空菜单一个等长的兜底 handler（槽位数量契约不能变）",
                ctor.contains("machine == null"));

        String dataGet = TestSourceText.methodBody(src, "public int get(int index)");
        assertTrue("ContainerData#get 没找到，判据可能失配", !dataGet.isEmpty());
        assertTrue("ContainerData#get 必须在 machine == null 时返回 0",
                dataGet.contains("machine == null"));

        String stillValid = TestSourceText.methodBody(src, "public boolean stillValid(Player player)");
        assertTrue("stillValid() 没找到，判据可能失配", !stillValid.isEmpty());
        assertTrue("stillValid() 必须在 machine == null 时返回 false",
                stillValid.contains("machine != null"));

        String blockPos = TestSourceText.methodBody(src, "public net.minecraft.core.BlockPos getBlockPos()");
        assertTrue("getBlockPos() 没找到，判据可能失配", !blockPos.isEmpty());
        assertTrue("getBlockPos() 必须在 machine == null 时返回兜底坐标而不是 NPE",
                blockPos.contains("machine == null"));
    }

    // ================== 判据不许空转 ==================

    /** 确认上面的判据在本仓确实还能匹配到东西（防改名后静默恒真）。 */
    @Test
    public void criteriaStillMatchSomething() throws IOException {
        String packet = TestSourceText.read(PACKET.toString());
        assertTrue("判据失效：包类里已找不到 VarInt 计数编码",
                packet.contains("writeVarInt(stack.getCount())"));
        assertTrue("判据失效：包类里已找不到解码侧的 setCount", packet.contains("setCount("));

        String central = TestSourceText.read(CENTRAL_MENU.toString());
        assertTrue("判据失效：CentralKitchenMenu 里已找不到 machine == null",
                central.contains("machine == null"));
        assertTrue("判据失效：CentralKitchenMenu 里已找不到 instanceof 判空",
                central.contains("instanceof CentralKitchenBlockEntity"));

        String sandwich = TestSourceText.read(SANDWICH_MENU.toString());
        assertTrue("判据失效：SandwichAssemblerMenu 里已找不到 machine == null",
                sandwich.contains("machine == null"));
        assertTrue("判据失效：SandwichAssemblerMenu 里已找不到 instanceof 判空",
                sandwich.contains("instanceof SandwichAssemblerBlockEntity"));
    }
}
