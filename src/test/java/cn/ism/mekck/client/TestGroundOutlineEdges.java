package cn.ism.mekck.client;

import cn.ism.mekck.TestSourceText;
import net.minecraft.core.BlockPos;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 多方块「占地面积」轮廓的底边判据（M5-1）。
 *
 * <h3>为什么值得单独一个测试</h3>
 * 旧 {@code groundEdge} 判断一条底边是否被相邻块挡住时，查的是边<b>两侧</b>的格子
 * （水平边查 z0-1 与 z0+1、垂直边查 x0-1 与 x0+1），而方块自身恒在地面集合里 ⇒
 * 南/东边恒被自己挡住、北/西边被对侧邻块误挡。后果是玩家手持多方块机器时看到的
 * 占地轮廓缺边：种植切配站只画 2/4 条、生物反应堆只画 4/12 条。判据错在「查哪一格」，
 * 而正确判据（只查外侧一格）与渲染无关，所以抽成纯函数在这里钉住。
 *
 * <p>另一处缺陷同源：绑定格清单不含主方块，3×3 足迹只有外圈 8 格，
 * 不把主方块并入地面集合就会在轮廓中间多画一个 1×1 的洞（12 条边变 16 条）。</p>
 *
 * <p>本文件同时承载 M5-3 的弱护栏（{@code renderBerPreview} 不得每帧直接
 * {@code newBlockEntity}）—— 任务只允许新增本文件与 {@code TestJeiTextureSizes}
 * 两个护栏文件，而两者都是「客户端放置预览」的源码形态断言。</p>
 */
public class TestGroundOutlineEdges {

    private static final String OUTLINE_RENDERER =
            "src/main/java/cn/ism/mekck/client/MekCkOutlineRenderer.java";

    // ── 足迹 → 可见底边集合 ──────────────────────────────────────────────

    /** 1×1 足迹：4 条边。 */
    @Test
    public void oneByOneFootprintDrawsFourEdges() {
        assertEquals(Set.of("0,0|1,0", "1,0|1,1", "0,1|1,1", "0,0|0,1"),
                keys(ClientWorldEvents.visibleGroundEdges(cells(0, 0))));
    }

    /** 1×2 足迹（种植切配站/工厂的地面层）：6 条边，中间那条共享边不画。 */
    @Test
    public void oneByTwoFootprintDrawsSixEdges() {
        assertEquals(Set.of("0,0|1,0", "1,0|1,1", "1,1|1,2", "0,2|1,2", "0,1|0,2", "0,0|0,1"),
                keys(ClientWorldEvents.visibleGroundEdges(cells(0, 0, 0, 1))));
    }

    /** 3×3 足迹（生物反应堆地面层）：12 条边。 */
    @Test
    public void threeByThreeFootprintDrawsTwelveEdges() {
        Set<BlockPos> ground = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                ground.add(new BlockPos(x, 0, z));
            }
        }
        assertEquals(Set.of(
                        "-1,-1|0,-1", "0,-1|1,-1", "1,-1|2,-1",
                        "-1,2|0,2", "0,2|1,2", "1,2|2,2",
                        "-1,-1|-1,0", "-1,0|-1,1", "-1,1|-1,2",
                        "2,-1|2,0", "2,0|2,1", "2,1|2,2"),
                keys(ClientWorldEvents.visibleGroundEdges(ground)));
    }

    /**
     * 3×3 外圈 8 格（不含中心）：中间会多出 4 条洞边 —— 这正是主方块必须并入地面集合的原因。
     * 生物反应堆的绑定格清单只有外圈 8 格，主方块在底部正中。
     */
    @Test
    public void ringWithoutCenterLeavesHoleInTheMiddle() {
        Set<BlockPos> ring = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 0 || z != 0) {
                    ring.add(new BlockPos(x, 0, z));
                }
            }
        }
        Set<String> drawn = keys(ClientWorldEvents.visibleGroundEdges(ring));
        assertEquals("缺中心格时 12 条外沿 + 4 条洞边", 16, drawn.size());
        assertTrue("中心洞的 4 条边必须出现（说明缺中心格会画出一个 1×1 的洞）",
                drawn.containsAll(Set.of("0,0|1,0", "1,0|1,1", "0,1|1,1", "0,0|0,1")));
    }

    // ── 判据本身：只查外侧一格 ───────────────────────────────────────────

    /** 对侧有邻块不影响本边可见性（旧实现正是被对侧邻块误挡）。 */
    @Test
    public void oppositeNeighbourDoesNotHideTheEdge() {
        Set<BlockPos> ground = cells(0, 0, 0, 1); // 1×2，沿 z 排列
        // 北边外侧 (0,-1) 无块 → 可见；旧实现还会查南侧 (0,1)（有块）而误判 hidden
        assertTrue(ClientWorldEvents.isGroundEdgeVisible(ground, new BlockPos(0, 0, -1)));
        // 南边外侧 (0,2) 无块 → 可见；旧实现查 (0,1)（自身）而恒判 hidden
        assertTrue(ClientWorldEvents.isGroundEdgeVisible(ground, new BlockPos(0, 0, 2)));
        // 内部共享边：外侧 (0,1) 有块 → 不可见
        assertFalse(ClientWorldEvents.isGroundEdgeVisible(ground, new BlockPos(0, 0, 1)));
    }

    // ── 真实形状（含主方块并入） ─────────────────────────────────────────

    /** 种植切配站（1×2）与生物反应堆（3×3×3）的实际绑定格清单。 */
    @Test
    public void realMultiblockFootprintsDrawTheirFullPerimeter() {
        BlockPos target = new BlockPos(10, 64, 20);
        // 种植切配站：SHAPE_2_TALL 唯一绑定块在 target.above()，地面层只有主方块自己
        assertEquals(4, ClientWorldEvents.groundOutlineEdges(List.of(target.above()), target).size());
        // 生物反应堆：SHAPE_3X3X3 的 26 个绑定格（主方块在底部正中，不在清单里）
        List<BlockPos> bioreactor = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        bioreactor.add(target.offset(x, y, z));
                    }
                }
            }
        }
        assertEquals(26, bioreactor.size());
        assertEquals("主方块并入地面集合后是完整 3×3 外沿",
                12, ClientWorldEvents.groundOutlineEdges(bioreactor, target).size());
    }

    // ── M5-3 弱护栏：预览 BE 不得每帧新建 ────────────────────────────────

    /**
     * {@code renderBerPreview} 在放置预览里<b>每帧</b>被调用，不得直接
     * {@code newBlockEntity} —— 必须经 {@code previewBlockEntity} 的
     * (BlockState, BlockPos) 缓存复用（能量立方/风机的 tile 构造期持有 10+ 个
     * handler/component，每帧新建就是每秒上百个短命对象）。
     */
    @Test
    public void berPreviewReusesCachedBlockEntity() throws IOException {
        String code = TestSourceText.read(OUTLINE_RENDERER);
        String body = TestSourceText.methodBody(code, "public static boolean renderBerPreview(");
        assertFalse("没扫到 renderBerPreview 方法体，判据失效（方法被改名？）", body.isEmpty());
        assertFalse("renderBerPreview 又直接 newBlockEntity 了 —— 放置预览每帧都会走这里，"
                + "必须经 previewBlockEntity 的 (BlockState, BlockPos) 缓存复用",
                body.contains("newBlockEntity"));
        assertTrue("缓存取用入口不见了", body.contains("previewBlockEntity("));
        assertTrue("缓存字段不见了", code.contains("berPreviewBe"));
    }

    /**
     * 缓存未命中后的<b>第一帧</b> BE 也必须带 level（复审 p2-1）。
     *
     * <p>{@code BlockEntity} 构造器只写 type/worldPosition/blockState，构造链里不调
     * {@code setLevel} ⇒ {@code newBlockEntity} 出来的 BE 第一帧 {@code getLevel() == null}；
     * 而 {@code BioreactorRenderer.render} 开头就是 {@code if (level == null) return;}
     * ⇒ 玩家准星每移到一个新方块，该方块的第一帧 BER 叠加层不画（相对旧实现的行为回退）。
     * 复用分支的 setLevel 管不到这条路径，所以断言必须落在 {@code newBlockEntity} 之后。</p>
     */
    @Test
    public void berPreviewCreationPathSetsLevel() throws IOException {
        String code = TestSourceText.read(OUTLINE_RENDERER);
        String body = TestSourceText.methodBody(code,
                "private static net.minecraft.world.level.block.entity.BlockEntity previewBlockEntity(");
        assertFalse("没扫到 previewBlockEntity 方法体，判据失效（方法被改名？）", body.isEmpty());
        int create = body.indexOf("newBlockEntity");
        assertTrue("没扫到 newBlockEntity，判据失效", create >= 0);
        assertTrue("创建分支必须补 setLevel(level)：新建的 BE 没有 world 上下文，"
                        + "缓存未命中后的第一帧 BER 会因 getLevel() == null 直接 return",
                body.indexOf("setLevel(level)", create) >= 0);
    }

    /**
     * 登出时必须清掉预览 BE 缓存（复审 p2-2）。
     *
     * <p>缓存是静态字段，BE 的 level 字段强引用 ClientLevel（含已加载区块）——
     * 不清理会跨存档残留，直到玩家再次进世界并手持 BER 预览方块才换键。
     * 与 {@code BuffLinkRenderer.onLogout} 清 {@code BuffLinkIndex} 同一先例。</p>
     */
    @Test
    public void berPreviewCacheIsClearedOnLogout() throws IOException {
        String code = TestSourceText.read(OUTLINE_RENDERER);
        assertTrue("MekCkOutlineRenderer 必须订阅事件（否则 onLogout 不会被调用）",
                code.contains("@Mod.EventBusSubscriber"));
        assertTrue("必须处理 ClientPlayerNetworkEvent.LoggingOut",
                code.contains("ClientPlayerNetworkEvent.LoggingOut"));
        String body = TestSourceText.methodBody(code, "public static void onLogout(");
        assertFalse("没扫到 onLogout 方法体，判据失效（方法被改名？）", body.isEmpty());
        assertTrue("onLogout 必须清掉缓存的 BE", body.contains("berPreviewBe = null"));
        assertTrue("onLogout 必须清掉缓存键（state/pos）",
                body.contains("berPreviewState = null") && body.contains("berPreviewPos = null"));
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

    /** 地面格集合（y=0，按 x,z 对给出）。 */
    private static Set<BlockPos> cells(int... xz) {
        Set<BlockPos> out = new HashSet<>();
        for (int i = 0; i < xz.length; i += 2) {
            out.add(new BlockPos(xz[i], 0, xz[i + 1]));
        }
        return out;
    }

    /** 边的规范化键（忽略方向与 y）：minX,minZ|maxX,maxZ。 */
    private static Set<String> keys(List<ClientWorldEvents.GroundEdge> edges) {
        Set<String> out = new HashSet<>();
        for (ClientWorldEvents.GroundEdge e : edges) {
            int x0 = Math.min(e.x0(), e.x1());
            int z0 = Math.min(e.z0(), e.z1());
            int x1 = Math.max(e.x0(), e.x1());
            int z1 = Math.max(e.z0(), e.z1());
            out.add(x0 + "," + z0 + "|" + x1 + "," + z1);
        }
        return out;
    }
}
