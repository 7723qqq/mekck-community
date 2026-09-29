package cn.ism.mekck.client.mesh;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 独立复核 bioreactor.obj 的每个顶点 UV。
 *
 * <p><b>为什么必须有这条测试</b>：转换脚本一旦用「投影到观察者平面」之类<b>几何推导</b>去猜
 * MC 的 uv 约定，就会全盘错而其它测试全绿——曾经就是这样：338 个面里 285 个的 uv 与源 JSON
 * 不符（整块面板镜像），而当时只有分组数、三角形数、y 范围这些测试。</p>
 *
 * <p>本测试**不读转换脚本**，而是把 MC 1.20.1 的逐面顶点顺序与 uv 角分配
 * （{@code FaceInfo} / {@code BlockFaceUV}）以字面量形式写死在这里，
 * 从源 JSON 重新推导期望的 (位置, uv) 顶点集合，再与产物 OBJ 的实际顶点集合比对。
 * 换一种错误实现（例如按观察者视角推导）时这里会立刻失败。</p>
 */
public class TestBioreactorObjUvs {

    private static final String OBJ = "/assets/mekck/models/mesh/bioreactor.obj";
    private static final String LAYER_DIR = "/assets/mekck/models/block/mekck/bioreactor/";
    private static final String[] LAYERS = {
            "bioreactor_layer0", "bioreactor_layer1", "bioreactor_layer2"};
    private static final double TEXTURE_SIZE = 128.0;

    /**
     * MC 1.20.1 {@code FaceInfo} 的逐面顶点顺序：每项是 (x, y, z) 各自取 from 还是 to。
     * 这里用 0/1 表示 0=min(from)、1=max(to)。
     */
    private static final Map<String, int[][]> FACE_VERTICES = Map.of(
            "down", new int[][]{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
            "up", new int[][]{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            "north", new int[][]{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},
            "south", new int[][]{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},
            "west", new int[][]{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},
            "east", new int[][]{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}});

    /** {@code BlockFaceUV} 把 v0..v3 依次分到 uv 矩形的这四个角。 */
    private static final int[][] UV_BY_INDEX = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};

    private static JsonObject readJson(String resource) throws IOException {
        try (InputStream is = TestBioreactorObjUvs.class.getResourceAsStream(resource)) {
            assertNotNull("资源缺失: " + resource, is);
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }

    /** 从源 JSON 用 MC 的表推导期望的顶点集合（位置 block 单位，uv 为 MC 约定 0..1）。 */
    private static Set<String> expectedVertices() throws IOException {
        Set<String> out = new HashSet<>();
        for (String layer : LAYERS) {
            JsonObject model = readJson(LAYER_DIR + layer + ".json");
            for (JsonElement element : model.getAsJsonArray("elements")) {
                JsonObject el = element.getAsJsonObject();
                double x0 = el.getAsJsonArray("from").get(0).getAsDouble();
                double y0 = el.getAsJsonArray("from").get(1).getAsDouble();
                double z0 = el.getAsJsonArray("from").get(2).getAsDouble();
                double x1 = el.getAsJsonArray("to").get(0).getAsDouble();
                double y1 = el.getAsJsonArray("to").get(1).getAsDouble();
                double z1 = el.getAsJsonArray("to").get(2).getAsDouble();
                double[] lo = {x0, y0, z0};
                double[] hi = {x1, y1, z1};

                for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject("faces").entrySet()) {
                    int[][] selectors = FACE_VERTICES.get(entry.getKey());
                    assertNotNull("测试未覆盖的面朝向: " + entry.getKey(), selectors);
                    JsonArray uv = entry.getValue().getAsJsonObject().getAsJsonArray("uv");
                    for (int i = 0; i < 4; i++) {
                        double px = selectors[i][0] == 1 ? hi[0] : lo[0];
                        double py = selectors[i][1] == 1 ? hi[1] : lo[1];
                        double pz = selectors[i][2] == 1 ? hi[2] : lo[2];
                        // uv 数组是交错的 [u0, v0, u1, v1]，故 u 取偶数位、v 取奇数位
                        double u = uv.get(UV_BY_INDEX[i][0] * 2).getAsDouble();
                        double v = uv.get(UV_BY_INDEX[i][1] * 2 + 1).getAsDouble();
                        // ObjMesh.parse 会把 OBJ 的 v 翻回 MC 约定，这里直接用 MC 约定比较
                        out.add(key(px / 16.0, py / 16.0, pz / 16.0, u / TEXTURE_SIZE, v / TEXTURE_SIZE));
                    }
                }
            }
        }
        return out;
    }

    /** 产物 OBJ 经 ObjMesh.parse 后的实际顶点集合。 */
    private static Set<String> actualVertices() throws IOException {
        Set<String> out = new HashSet<>();
        try (InputStream is = TestBioreactorObjUvs.class.getResourceAsStream(OBJ)) {
            assertNotNull("bioreactor.obj 未打进 classpath", is);
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                Map<String, float[]> groups = ObjMesh.parse(reader);
                for (float[] data : groups.values()) {
                    for (int i = 0; i < data.length; i += ObjMesh.STRIDE) {
                        out.add(key(data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4]));
                    }
                }
            }
        }
        return out;
    }

    private static String key(double x, double y, double z, double u, double v) {
        return String.format("%.5f|%.5f|%.5f|%.5f|%.5f", x, y, z, u, v);
    }

    @Test
    public void everyObjVertexMatchesTheSourceJsonUnderMcsOwnTable() throws IOException {
        Set<String> expected = expectedVertices();
        Set<String> actual = actualVertices();

        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (String e : expected) {
            if (!actual.contains(e)) {
                missing.add(e);
            }
        }
        for (String a : actual) {
            if (!expected.contains(a)) {
                extra.add(a);
            }
        }
        assertTrue("产物缺少这些源顶点（位置|uv）：\n" + join(missing), missing.isEmpty());
        assertTrue("产物多出这些源中不存在的顶点：\n" + join(extra), extra.isEmpty());
        assertEquals("顶点总数应一致", expected.size(), actual.size());
    }

    private static String join(List<String> items) {
        return String.join("\n", items.subList(0, Math.min(items.size(), 12)));
    }
}
