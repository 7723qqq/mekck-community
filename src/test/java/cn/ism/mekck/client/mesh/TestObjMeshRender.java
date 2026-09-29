package cn.ism.mekck.client.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ObjMeshRenderer 的顶点写出契约。
 *
 * <p>用记录型 {@link VertexConsumer} 桩件覆盖顶点数据流，无需真实 GL 上下文。
 * 覆盖的是「数据是否正确传到 GPU 队列」，不覆盖「渲染出来是否正确」——后者需实机截图对比。</p>
 */
public class TestObjMeshRender {

    /** 记录每个顶点写出的全部属性。 */
    private static final class RecordingConsumer implements VertexConsumer {
        final List<float[]> positions = new ArrayList<>();
        final List<float[]> uvs = new ArrayList<>();
        final List<float[]> normals = new ArrayList<>();
        final List<int[]> colors = new ArrayList<>();
        final List<Integer> overlays = new ArrayList<>();
        final List<Integer> lights = new ArrayList<>();
        int endVertexCount;

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            positions.add(new float[]{(float) x, (float) y, (float) z});
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            colors.add(new int[]{r, g, b, a});
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            uvs.add(new float[]{u, v});
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            overlays.add(u);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            lights.add(v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            normals.add(new float[]{x, y, z});
            return this;
        }

        @Override
        public void endVertex() {
            endVertexCount++;
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }

    /** 一个三角形的 mesh，法线 +Z。 */
    private static ObjMesh triangle() {
        Map<String, float[]> groups = new LinkedHashMap<>();
        groups.put("body", new float[]{
                0.0F, 0.0F, 0.0F, 0.1F, 0.2F, 0.0F, 0.0F, 1.0F,
                1.0F, 0.0F, 0.0F, 0.3F, 0.4F, 0.0F, 0.0F, 1.0F,
                0.0F, 1.0F, 0.0F, 0.5F, 0.6F, 0.0F, 0.0F, 1.0F,
        });
        return ObjMesh.of(groups);
    }

    private static RecordingConsumer draw(ObjMesh mesh, String group) {
        RecordingConsumer rec = new RecordingConsumer();
        MultiBufferSource buffers = new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType renderType) {
                return rec;
            }
        };
        ObjMeshRenderer.renderGroup(mesh, group, new PoseStack(), buffers,
                0x00F000F0, 0x00112233, null);
        return rec;
    }

    @Test
    public void writesExactlyThreeVerticesAndEndsEach() {
        RecordingConsumer rec = draw(triangle(), "body");
        assertEquals(3, rec.positions.size());
        assertEquals(3, rec.endVertexCount);
    }

    @Test
    public void passesPositionsUvsAndNormalsThroughUnchanged() {
        RecordingConsumer rec = draw(triangle(), "body");
        assertArrayEquals(new float[]{0, 0, 0}, rec.positions.get(0), 1e-6f);
        assertArrayEquals(new float[]{1, 0, 0}, rec.positions.get(1), 1e-6f);
        assertArrayEquals(new float[]{0, 1, 0}, rec.positions.get(2), 1e-6f);
        assertArrayEquals(new float[]{0.1F, 0.2F}, rec.uvs.get(0), 1e-6f);
        assertArrayEquals(new float[]{0.3F, 0.4F}, rec.uvs.get(1), 1e-6f);
        assertArrayEquals(new float[]{0.5F, 0.6F}, rec.uvs.get(2), 1e-6f);
        for (float[] n : rec.normals) {
            assertArrayEquals(new float[]{0, 0, 1}, n, 1e-6f);
        }
    }

    @Test
    public void vertexColorCarriesVanillaPerFaceShade() {
        // 测试三角形的法线是 +Z（南北向），对应 vanilla 的 0.8 明暗。
        // 配套的 objSolid 用 rendertype_solid（只看 lightmap、不做漫反射），
        // 所以逐面明暗必须由顶点色承担，否则画面对比原 baked 模型会明显偏亮。
        RecordingConsumer rec = draw(triangle(), "body");
        for (int[] c : rec.colors) {
            assertArrayEquals(new int[]{0xCC, 0xCC, 0xCC, 0xFF}, c);
        }
    }
    @Test
    public void shadeFollowsFaceNormalDirection() {
        // vanilla 对 baked 方块模型按模型空间面朝向烘焙固定明暗：上 1.0 / 南北 0.8 /
        // 东西 0.6 / 下 0.5。四种朝向必须给出四种不同明暗，否则说明法线没被读对。
        assertEquals(0xFF, shadeOfNormal(0.0f, 1.0f, 0.0f));    // up    1.0 -> 255
        assertEquals(0xCC, shadeOfNormal(0.0f, 0.0f, 1.0f));    // 南北  0.8 -> 204
        assertEquals(0x99, shadeOfNormal(1.0f, 0.0f, 0.0f));    // 东西  0.6 -> 153
        assertEquals(0x80, shadeOfNormal(0.0f, -1.0f, 0.0f));   // down  0.5 -> 128
    }

    /**
     * 造一个法线为给定方向的单面三角形并绘制，返回写出的亮度分量。
     * 明暗是灰度的（r=g=b），alpha 应恒为不透明——两者都在此处断言。
     */
    private static int shadeOfNormal(float nx, float ny, float nz) {
        Map<String, float[]> groups = new LinkedHashMap<>();
        groups.put("body", new float[]{
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, nx, ny, nz,
                1.0F, 0.0F, 0.0F, 0.0F, 0.0F, nx, ny, nz,
                0.0F, 1.0F, 0.0F, 0.0F, 0.0F, nx, ny, nz,
        });
        RecordingConsumer rec = draw(ObjMesh.of(groups), "body");
        int[] c = rec.colors.get(0);
        assertEquals("明暗应为灰度", c[0], c[1]);
        assertEquals("明暗应为灰度", c[0], c[2]);
        assertEquals("alpha 应恒为不透明", 0xFF, c[3]);
        return c[0];
    }

    @Test
    public void forwardsOverlayAndLight() {
        RecordingConsumer rec = draw(triangle(), "body");
        // overlayCoords(int) / uv2(int) 是 default 方法（javap -c 核实），
        // 会把打包值拆成两个 16 位分量：u = packed & 0xFFFF，v = (packed >> 16) & 0xFFFF。
        for (int o : rec.overlays) {
            assertEquals(0x00112233 & 0xFFFF, o);              // 0x2233
        }
        for (int l : rec.lights) {
            assertEquals((0x00F000F0 >> 16) & 0xFFFF, l);     // 0x00F0
        }
    }

    @Test
    public void missingGroupWritesNothingAndDoesNotThrow() {
        RecordingConsumer rec = draw(triangle(), "absent");
        assertEquals(0, rec.positions.size());
        assertEquals(0, rec.endVertexCount);
    }

    @Test
    public void unavailableMeshIsSafe() {
        RecordingConsumer rec = draw(ObjMesh.empty(), "body");
        assertEquals(0, rec.positions.size());
    }

    @Test
    public void renderAllCoversEveryGroup() {
        Map<String, float[]> groups = new LinkedHashMap<>();
        float[] tri = {
                0, 0, 0, 0, 0, 0, 0, 1,
                1, 0, 0, 0, 0, 0, 0, 1,
                0, 1, 0, 0, 0, 0, 0, 1,
        };
        groups.put("layer0", tri);
        groups.put("layer1", tri);
        ObjMesh mesh = ObjMesh.of(groups);

        RecordingConsumer rec = new RecordingConsumer();
        MultiBufferSource buffers = new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType renderType) {
                return rec;
            }
        };
        ObjMeshRenderer.renderAll(mesh, new PoseStack(), buffers, 0, 0, null);
        assertEquals(6, rec.positions.size());
        assertTrue(rec.endVertexCount == 6);
    }
}
