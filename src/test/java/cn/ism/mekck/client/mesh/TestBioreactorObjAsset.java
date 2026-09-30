package cn.ism.mekck.client.mesh;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 端到端：tools/convert_bioreactor_obj.py 生成的 bioreactor.obj 必须能被
 * {@link ObjMesh#parse} 正确读回，且分组结构与几何范围符合 BioreactorRenderer 的预期。
 *
 * <p>这条测试把「JSON 资产 → 转换脚本 → OBJ 资源 → 运行时解析器」整条链路钉在一起：
 * 转换脚本改了分组数或坐标范围而渲染器没跟上时，这里会先红。</p>
 *
 * <p>期望值来自 3 个源 JSON 的实际内容（58 个 box 元素、338 条面记录）。</p>
 */
public class TestBioreactorObjAsset {

    private static final String ASSET = "/assets/mekck/models/mesh/bioreactor.obj";

    private static Map<String, float[]> parseAsset() throws IOException {
        InputStream is = TestBioreactorObjAsset.class.getResourceAsStream(ASSET);
        assertNotNull("bioreactor.obj 未打进 classpath：" + ASSET, is);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return ObjMesh.parse(reader);
        }
    }

    @Test
    public void assetIsPackagedAndParses() throws IOException {
        Map<String, float[]> groups = parseAsset();
        assertEquals(3, groups.size());
        assertTrue(groups.keySet().toString().contains("bioreactor_layer0"));
        assertTrue(groups.keySet().toString().contains("bioreactor_layer1"));
        assertTrue(groups.keySet().toString().contains("bioreactor_layer2"));
    }

    @Test
    public void eachLayerKeepsLocalHeightForRenderTimeStacking() throws IOException {
        // 每层在 OBJ 里都保持 y[0,1] 的局部坐标；层间堆叠由 BioreactorRenderer
        // 施加 translate(0, i, 0)，这样每层才能各自取 pos.above(i) 的光照。
        Map<String, float[]> groups = parseAsset();
        for (Map.Entry<String, float[]> e : groups.entrySet()) {
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (int i = 1; i < e.getValue().length; i += ObjMesh.STRIDE) {
                minY = Math.min(minY, e.getValue()[i]);
                maxY = Math.max(maxY, e.getValue()[i]);
            }
            assertEquals("层 " + e.getKey() + " 的 y 下界应为 0", 0.0f, minY, 1e-6f);
            assertEquals("层 " + e.getKey() + " 的 y 上界应为 1（16px = 1 block）", 1.0f, maxY, 1e-6f);
        }
    }

    @Test
    public void triangleCountsMatchTheSourceJsonModels() throws IOException {
        // True Mek 高精硬表面重工业模型（各层在重构优化下的三角形数）
        Map<String, float[]> groups = parseAsset();
        assertEquals(928, countTriangles(groups, "bioreactor_layer0"));
        assertEquals(1190, countTriangles(groups, "bioreactor_layer1"));
        assertEquals(1228, countTriangles(groups, "bioreactor_layer2"));
        assertEquals(3346, countTriangles(groups));
    }

    @Test
    public void footprintSpansTwoBlocks() throws IOException {
        // 3×3×3 多方块结构：机器以 (0,0) 为中心，X 和 Z 必须处于 [-1.5, 1.5] 约束内
        Map<String, float[]> groups = parseAsset();
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (float[] data : groups.values()) {
            for (int i = 0; i < data.length; i += ObjMesh.STRIDE) {
                minX = Math.min(minX, data[i]);
                maxX = Math.max(maxX, data[i]);
                minZ = Math.min(minZ, data[i + 2]);
                maxZ = Math.max(maxZ, data[i + 2]);
            }
        }
        assertTrue("X 下界应在 -1.5 范围内: " + minX, minX >= -1.5f);
        assertTrue("X 上界应在 1.5 范围内: " + maxX, maxX <= 1.5f);
        assertTrue("Z 下界应在 -1.5 范围内: " + minZ, minZ >= -1.5f);
        assertTrue("Z 上界应在 1.5 范围内: " + maxZ, maxZ <= 1.5f);
        assertTrue("机器跨度应超过 2 格 (3x3 规格)", (maxX - minX) > 2.0f);
    }

    private static int countTriangles(Map<String, float[]> groups, String group) {
        float[] data = groups.get(group);
        assertNotNull("缺少分组 " + group, data);
        return data.length / ObjMesh.STRIDE / 3;
    }

    private static int countTriangles(Map<String, float[]> groups) {
        int total = 0;
        for (float[] data : groups.values()) {
            total += data.length / ObjMesh.STRIDE / 3;
        }
        return total;
    }
}
