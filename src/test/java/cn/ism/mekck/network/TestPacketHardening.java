package cn.ism.mekck.network;

import cn.ism.mekck.TestSourceText;
import cn.ism.mekck.kitchen.KitchenFilter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 网络包加固护栏（第七轮 M7-M2 / M7-M3 / M7-M4 / M7-m1 / M7-m5）。
 *
 * <h3>为什么需要它</h3>
 * 这一轮修的五条缺陷有一个共同点：<b>修好之后没有任何东西会拦住回归</b>。
 * <ul>
 *   <li><b>M7-M2</b>：{@code expensiveRequest} 的「同指纹放行」不省计算 ——
 *       调用方拿到 true 后照常全量 solve，重复同一请求可无限触发计算。
 *       修法是三态（ALLOW_COMPUTE / ALLOW_CACHED / DENY）+ 调用方在 cached 分支回上次结果。
 *       判定表是纯逻辑，这里直接对 {@link PacketGuard#classifyExpensiveRequest} 断言；
 *       「调用方真的处理了 cached 分支」只能靠源码形态断言。</li>
 *   <li><b>M7-M4</b>：{@code NetworkOrderPacket} / {@code NetworkPullPacket(ACTION_PULL)}
 *       每包一次全网扫描，此前完全没有节流。源码形态断言「必须走三态闸门」。</li>
 *   <li><b>M7-M3</b>：{@code NetworkPullPacket} 的 itemId 来自 {@code readUtf} 无校验，
 *       恶意客户端可把 AE2 侧勾选清单撑到任意大。断言「必须校验合法注册名」。</li>
 *   <li><b>M7-m1</b>：两个请求包在客户端被处理时 {@code getSender()} 恒为 null ⇒ NPE。
 *       断言「player == null 直接 return」。</li>
 *   <li><b>M7-m5</b>：{@code KitchenFilterPacket} 接受任意 NBT 的 ItemStack 并落盘
 *       （单厨房最多 ~18MB）。行为断言「add 存进去的是剥掉 NBT 的精简版」。</li>
 * </ul>
 *
 * <p>源码形态断言一律走 {@link TestSourceText#read}（剥注释）——
 * 否则本文件自己的 javadoc 里引用的方法名就能把断言喂饱。</p>
 */
public class TestPacketHardening {

    private static final String NETWORK = "src/main/java/cn/ism/mekck/network/";
    private static final String KITCHEN = "src/main/java/cn/ism/mekck/kitchen/";

    /**
     * 裸 JVM 里启用 {@link ItemStack} / {@code Items} / 注册表。
     *
     * <p>必须逐字照抄 {@code kitchen/TestKitchenOutputMergeOverflow#boot} 的配方，
     * 且必须在 {@code @BeforeClass} 里：漏掉 {@code SharedConstants.tryDetectVersion()}
     * 会让 {@code ItemStack.<clinit>} 抛 {@code ExceptionInInitializerError}，
     * 而 JVM 会缓存这个失败、毒化整个测试会话。</p>
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

    // ================== 一、三态判定表（纯函数） ==================

    @Test
    public void sameFingerprintWithinCooldownIsCachedNotRecomputed() {
        // M7-M2 的核心：修复前「同指纹放行」后调用方照常全量 solve，
        // 重复同一请求可无限触发计算。现在必须回 ALLOW_CACHED（调用方回上次结果）。
        assertEquals(PacketGuard.ExpensiveRequest.ALLOW_CACHED,
                PacketGuard.classifyExpensiveRequest(true, true));
    }

    @Test
    public void sameFingerprintAfterCooldownRecomputes() {
        // 冷却期外同指纹必须重算：结果可能已随机器状态变化；
        // 若在这里回缓存，同一订单（指纹不变）将永远无法再下一次。
        assertEquals(PacketGuard.ExpensiveRequest.ALLOW_COMPUTE,
                PacketGuard.classifyExpensiveRequest(true, false));
    }

    @Test
    public void differentFingerprintWithinCooldownIsDenied() {
        assertEquals(PacketGuard.ExpensiveRequest.DENY,
                PacketGuard.classifyExpensiveRequest(false, true));
    }

    @Test
    public void differentFingerprintAfterCooldownComputes() {
        assertEquals(PacketGuard.ExpensiveRequest.ALLOW_COMPUTE,
                PacketGuard.classifyExpensiveRequest(false, false));
    }

    @Test
    public void cachedIsTheOnlyStateThatSkipsComputation() {
        // 三态里只有 ALLOW_CACHED 允许调用方跳过计算；DENY 是丢弃、ALLOW_COMPUTE 是重算。
        // 这条钉住「判定表不得退化成两态」。
        int cached = 0;
        for (boolean same : new boolean[]{true, false}) {
            for (boolean within : new boolean[]{true, false}) {
                PacketGuard.ExpensiveRequest state = PacketGuard.classifyExpensiveRequest(same, within);
                assertNotNull(state);
                if (state == PacketGuard.ExpensiveRequest.ALLOW_CACHED) cached++;
            }
        }
        assertEquals("ALLOW_CACHED 只应出现在「同指纹 + 冷却期内」这一格", 1, cached);
    }

    @Test
    public void decideExpensiveRequestCoversEverySlotState() {
        // 这是 expensiveRequestState 的判定核心（纯函数，槽位快照 → 三态）。
        // 直接测它才能钉住「冷却期内的同指纹重复必须回 ALLOW_CACHED」——
        // 只测 classifyExpensiveRequest 挡不住「把 cached 算成 false」这类胶水回归。
        assertEquals("无槽位（首次请求）必须重算",
                PacketGuard.ExpensiveRequest.ALLOW_COMPUTE,
                PacketGuard.decideExpensiveRequest(null, 100L, 7L));
        assertEquals("同指纹 + 冷却期内必须回缓存",
                PacketGuard.ExpensiveRequest.ALLOW_CACHED,
                PacketGuard.decideExpensiveRequest(new long[]{100L, 7L}, 100L, 7L));
        assertEquals("同指纹 + 冷却期内（边界内 4 tick）必须回缓存",
                PacketGuard.ExpensiveRequest.ALLOW_CACHED,
                PacketGuard.decideExpensiveRequest(new long[]{100L, 7L}, 104L, 7L));
        assertEquals("同指纹 + 冷却期外（边界 5 tick）必须重算",
                PacketGuard.ExpensiveRequest.ALLOW_COMPUTE,
                PacketGuard.decideExpensiveRequest(new long[]{100L, 7L}, 105L, 7L));
        assertEquals("不同指纹 + 冷却期内必须拒绝",
                PacketGuard.ExpensiveRequest.DENY,
                PacketGuard.decideExpensiveRequest(new long[]{100L, 7L}, 100L, 8L));
        assertEquals("不同指纹 + 冷却期外必须重算",
                PacketGuard.ExpensiveRequest.ALLOW_COMPUTE,
                PacketGuard.decideExpensiveRequest(new long[]{100L, 7L}, 105L, 8L));
    }

    @Test
    public void fingerprintIsOrderSensitiveAndStable() {
        assertEquals("同一组分量必须稳定", PacketGuard.fingerprint(7L, 8L, 9L),
                PacketGuard.fingerprint(7L, 8L, 9L));
        assertNotEquals("分量顺序必须影响结果（否则 (a,b) 与 (b,a) 会被误判为同一请求）",
                PacketGuard.fingerprint(1L, 2L), PacketGuard.fingerprint(2L, 1L));
        assertNotEquals(PacketGuard.fingerprint(1L, 2L), PacketGuard.fingerprint(1L, 3L));
    }

    @Test
    public void fingerprintDistinguishesMachinePositions() {
        // 节流槽按玩家存：两台机器的同参数请求必须得到不同指纹，
        // 否则 ALLOW_CACHED 会回错结果 / 吞掉真实操作。
        long machineA = PacketGuard.fingerprint(1000L, 0L, 12345L, 5L);
        long machineB = PacketGuard.fingerprint(2000L, 0L, 12345L, 5L);
        assertNotEquals(machineA, machineB);
    }

    // ================== 二、源码形态：闸门被用上 ==================

    /**
     * 三态入口本身必须被钉住：四个调用方都走 {@code expensiveRequestState}，
     * 但它的方法体此前没有任何断言 —— 把判定胶水改成 {@code cached = false}
     * 会让每个放行都变成 ALLOW_COMPUTE（重复请求照常重算，M7-M2 静默回归），
     * 而纯函数测试与调用方源码形态测试都不会变红。
     */
    @Test
    public void threeStateEntryPointMapsDecisionFaithfully() throws IOException {
        String guard = TestSourceText.read(NETWORK + "PacketGuard.java");
        String state = TestSourceText.methodBody(guard,
                "public static ExpensiveRequest expensiveRequestState(");
        assertFalse("找不到 expensiveRequestState，判据失效", state.isEmpty());
        assertTrue("三态入口必须用纯函数 decideExpensiveRequest 判定（判定逻辑必须可单测）",
                state.contains("decideExpensiveRequest("));
        assertTrue("三态入口必须原样返回判定结果（不得再映射成别的状态）",
                state.contains("return decision;"));
        assertTrue("三态入口必须真的调用布尔闸门落账（否则槽位永不更新，节流失效）",
                state.contains("if (!expensiveRequest(player, fingerprint))"));
        assertTrue("三态入口必须挡住 null 玩家（客户端侧 getSender() 恒为 null）",
                state.contains("player == null"));
    }

    @Test
    public void kitchenOrderPacketHandlesCachedBranch() throws IOException {
        String src = TestSourceText.read(NETWORK + "KitchenOrderPacket.java");
        assertTrue("KitchenOrderPacket 必须走三态闸门（布尔闸门分不出 cached）",
                src.contains("PacketGuard.expensiveRequestState"));
        assertTrue("KitchenOrderPacket 必须显式处理 ALLOW_CACHED 分支（写成 if (false) 之类的空转不算）",
                src.contains("if (gate == PacketGuard.ExpensiveRequest.ALLOW_CACHED)"));
        assertTrue("KitchenOrderPacket 的 cached 分支必须取回上次结果（不得重算）",
                src.contains("PacketGuard.cachedResult"));
        assertTrue("KitchenOrderPacket 必须在真实计算后记录结果（否则 cached 分支永远取不到）",
                src.contains("PacketGuard.rememberResult"));
        assertTrue("KitchenOrderPacket 的指纹必须含机器坐标（否则两台机器的同参数请求会被误判为重复）",
                src.contains("pos.asLong()"));
        assertGatePrecedes(src, "kitchen.previewOrder", "KitchenOrderPacket");
        assertGatePrecedes(src, "kitchen.placeOrder", "KitchenOrderPacket");
    }

    @Test
    public void orderRecipePacketHandlesCachedBranch() throws IOException {
        String src = TestSourceText.read(NETWORK + "OrderRecipePacket.java");
        assertTrue("OrderRecipePacket 必须走三态闸门",
                src.contains("PacketGuard.expensiveRequestState"));
        assertTrue("OrderRecipePacket 只允许在 ALLOW_COMPUTE 时重算（本包无回包，cached 直接跳过）",
                src.contains("!= PacketGuard.ExpensiveRequest.ALLOW_COMPUTE"));
        assertTrue("OrderRecipePacket 的指纹必须含机器坐标",
                src.contains("packet.pos.asLong()"));
        assertGatePrecedes(src, "kitchen.placeOrder", "OrderRecipePacket");
    }

    @Test
    public void networkOrderPacketIsThrottled() throws IOException {
        String src = TestSourceText.read(NETWORK + "NetworkOrderPacket.java");
        assertTrue("NetworkOrderPacket 的 ME 下单必须过节流闸（每包一次全网扫描）",
                src.contains("PacketGuard.expensiveRequestState"));
        assertTrue("NetworkOrderPacket 只允许在 ALLOW_COMPUTE 时执行抽料",
                src.contains("!= PacketGuard.ExpensiveRequest.ALLOW_COMPUTE"));
        assertTrue("NetworkOrderPacket 的指纹必须含机器坐标",
                src.contains("packet.pos.asLong()"));
        assertGatePrecedes(src, "AE2Compat.pullNetworkIngredients", "NetworkOrderPacket");
    }

    @Test
    public void networkPullPacketIsThrottledAndValidatesItemId() throws IOException {
        String src = TestSourceText.read(NETWORK + "NetworkPullPacket.java");
        assertTrue("ACTION_PULL 必须过节流闸（每包一次全网扫描）",
                src.contains("PacketGuard.expensiveRequestState"));
        assertTrue("ACTION_PULL 只允许在 ALLOW_COMPUTE 时执行拉料",
                src.contains("!= PacketGuard.ExpensiveRequest.ALLOW_COMPUTE"));
        assertTrue("itemId 必须校验为合法注册名（客户端可控字符串不得直接进 AE2 勾选清单）",
                src.contains("ResourceLocation.tryParse")
                        && src.contains("ForgeRegistries.ITEMS.containsKey"));
        assertTrue("校验必须真的用在 toggle 分支上",
                src.contains("isRegisteredItem(packet.itemId)"));
        assertGatePrecedes(src, "AE2Compat.pullNetworkInputs", "NetworkPullPacket");
    }

    @Test
    public void requestPacketsRejectNullPlayer() throws IOException {
        for (String name : new String[]{"NetworkRecipeRequestPacket", "NetworkMissingRequestPacket"}) {
            String src = TestSourceText.read(NETWORK + name + ".java");
            String handle = TestSourceText.methodBody(src, "public static void handle(");
            assertFalse(name + "：找不到 handle 方法，判据失效", handle.isEmpty());
            assertTrue(name + " 必须在 player == null 时直接 return"
                            + "（恶意服务端把 C2S 包发回客户端时 getSender() 恒为 null，"
                            + "继续走到 sendToPlayer 会 NPE 崩客户端）",
                    handle.contains("player == null"));
        }
    }

    @Test
    public void kitchenFilterStripsNbtOnAdd() throws IOException {
        String src = TestSourceText.read(KITCHEN + "KitchenFilter.java");
        String add = TestSourceText.methodBody(src, "public boolean add(ItemStack stack)");
        assertFalse("找不到 KitchenFilter.add，判据失效", add.isEmpty());
        assertTrue("add 必须剥掉 NBT（readItem 允许 2MB NBT，原样落盘会把单厨房存档放大到 ~18MB）",
                add.contains("setTag(null)"));
    }

    /**
     * 闸门必须排在昂贵调用<b>之前</b>：只断言「闸门存在」挡不住「把闸门挪到昂贵调用之后」——
     * 那样昂贵扫描先执行，节流形同虚设，而所有 presence 断言照样全绿。
     */
    private static void assertGatePrecedes(String src, String expensiveCall, String where) {
        int gate = src.indexOf("PacketGuard.expensiveRequestState");
        int call = src.indexOf(expensiveCall);
        assertTrue(where + "：找不到闸门调用", gate >= 0);
        assertTrue(where + "：找不到昂贵调用 " + expensiveCall, call >= 0);
        assertTrue(where + "：闸门必须排在 " + expensiveCall + " 之前"
                + "（否则昂贵扫描先执行，节流形同虚设）", gate < call);
    }

    // ================== 三、KitchenFilter：NBT 剥离（行为） ==================

    @Test
    public void filterAddStoresStrippedCopy() {
        KitchenFilter filter = new KitchenFilter();
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag big = new CompoundTag();
        big.putByteArray("payload", new byte[200_000]);
        stack.setTag(big);

        assertTrue(filter.add(stack));
        ItemStack stored = filter.get(0);
        assertFalse("过滤材料必须被存下", stored.isEmpty());
        assertEquals("存进去的必须是剥掉 NBT 的精简版", null, stored.getTag());
        assertEquals("过滤材料只记 1 个", 1, stored.getCount());
    }

    @Test
    public void filterAddDedupsByItemIgnoringNbt() {
        KitchenFilter filter = new KitchenFilter();
        ItemStack plain = new ItemStack(Items.DIAMOND);
        ItemStack tagged = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("k", "v");
        tagged.setTag(tag);

        assertTrue(filter.add(plain));
        assertFalse("同一物品的不同 NBT 变体不得重复占位（匹配本身只看物品）", filter.add(tagged));
        assertEquals(1, filter.size());
    }

    @Test
    public void filterSaveStaysBoundedForBigNbtInput() {
        KitchenFilter filter = new KitchenFilter();
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag big = new CompoundTag();
        big.putByteArray("payload", new byte[2_000_000]);   // readItem 允许的上限量级
        stack.setTag(big);

        assertTrue(filter.add(stack));
        CompoundTag saved = filter.save();
        assertTrue("单条过滤材料的存档体积必须被压到 KB 级（修复前是 MB 级）："
                + saved.sizeInBytes(), saved.sizeInBytes() < 4096);
    }
}
