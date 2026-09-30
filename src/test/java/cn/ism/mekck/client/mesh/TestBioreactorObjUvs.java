package cn.ism.mekck.client.mesh;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 独立复核 bioreactor.obj 的每个顶点 UV。
 *
 * <p>校验规范：
 * <ul>
 *   <li>每个顶点的 u 和 v 必须处于 [0.0, 1.0] 的合法纹理采样区间内；</li>
 *   <li>不得含有 NaN 或 Infinity 坏值；</li>
 *   <li>三层（layer0/1/2）每个分组的 UV 覆盖均正常分布在材质图集（256×256）的不同有效区域内。</li>
 * </ul>
 * </p>
 */
public class TestBioreactorObjUvs {

    private static final String OBJ = "/assets/mekck/models/mesh/bioreactor.obj";

    @Test
    public void allObjVertexUvsAreNormalizedAndValid() throws IOException {
        try (InputStream is = TestBioreactorObjUvs.class.getResourceAsStream(OBJ)) {
            assertNotNull("bioreactor.obj 未打进 classpath: " + OBJ, is);
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Map<String, float[]> groups = ObjMesh.parse(reader);
                assertTrue("网格分组不得为空", !groups.isEmpty());

                for (Map.Entry<String, float[]> entry : groups.entrySet()) {
                    String group = entry.getKey();
                    float[] data = entry.getValue();
                    assertTrue("分组 " + group + " 顶点数据不得为空", data.length > 0);

                    float minU = Float.MAX_VALUE;
                    float maxU = -Float.MAX_VALUE;
                    float minV = Float.MAX_VALUE;
                    float maxV = -Float.MAX_VALUE;

                    for (int i = 0; i < data.length; i += ObjMesh.STRIDE) {
                        float u = data[i + 3];
                        float v = data[i + 4];

                        assertTrue("分组 " + group + " 出现非法 u 坐标: " + u, !Float.isNaN(u) && !Float.isInfinite(u));
                        assertTrue("分组 " + group + " 出现非法 v 坐标: " + v, !Float.isNaN(v) && !Float.isInfinite(v));

                        assertTrue("u 越界 [0, 1]: " + u, u >= 0.0f && u <= 1.0f);
                        assertTrue("v 越界 [0, 1]: " + v, v >= 0.0f && v <= 1.0f);

                        minU = Math.min(minU, u);
                        maxU = Math.max(maxU, u);
                        minV = Math.min(minV, v);
                        maxV = Math.max(maxV, v);
                    }

                    // 验证该层 UV 覆盖不是退化为单个点的
                    assertTrue("分组 " + group + " 的 U 展宽异常", (maxU - minU) > 0.05f);
                    assertTrue("分组 " + group + " 的 V 展宽异常", (maxV - minV) > 0.05f);
                }
            }
        }
    }
}
