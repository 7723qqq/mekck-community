package cn.ism.mekck.client.mesh;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ObjMesh.parse 的解析契约。
 *
 * <p>parse 是纯函数、不碰 Minecraft / GL，因此可在普通 JVM 中直接运行——
 * 这正是把它与资源读取、绘制分开的原因。</p>
 *
 * <p>注意读期望值时：{@code vt} 的 v 会被 parse 翻转成 MC 约定（v 向下），
 * 所以 {@code vt 0 0} 解析出来是 v=1 而不是 v=0。</p>
 */
public class TestObjMeshParse {

    private static Map<String, float[]> parse(String obj) throws IOException {
        return ObjMesh.parse(new BufferedReader(new StringReader(obj)));
    }

    private static float[] firstGroup(String obj) throws IOException {
        Map<String, float[]> groups = parse(obj);
        assertEquals(1, groups.size());
        return groups.values().iterator().next();
    }

    /** 取第 index 个顶点的 8 个分量（x, y, z, u, v, nx, ny, nz）。 */
    private static float[] vertex(float[] data, int index) {
        return Arrays.copyOfRange(data, index * ObjMesh.STRIDE, (index + 1) * ObjMesh.STRIDE);
    }

    /** 只取位置分量，便于比较几何。 */
    private static float[] position(float[] data, int index) {
        return Arrays.copyOfRange(data, index * ObjMesh.STRIDE, index * ObjMesh.STRIDE + 3);
    }

    @Test
    public void parsesVertexFaceWithUvAndNormal() throws IOException {
        String obj = String.join("\n",
                "v 0 0 0",
                "v 1 0 0",
                "v 0 1 0",
                "vt 0 0",
                "vt 1 0",
                "vt 0 1",
                "vn 0 0 1",
                "f 1/1/1 2/2/1 3/3/1");
        float[] data = firstGroup(obj);
        assertEquals(3 * ObjMesh.STRIDE, data.length);
        // vt 的 v 经 1-v 翻转：vt 0 0 -> v=1，vt 1 0 -> v=1，vt 0 1 -> v=0
        assertArrayEquals(new float[]{0, 0, 0, 0, 1, 0, 0, 1}, vertex(data, 0), 1e-6f);
        assertArrayEquals(new float[]{1, 0, 0, 1, 1, 0, 0, 1}, vertex(data, 1), 1e-6f);
        assertArrayEquals(new float[]{0, 1, 0, 0, 0, 0, 0, 1}, vertex(data, 2), 1e-6f);
    }

    @Test
    public void flipsTextureVBecauseMcUsesTopLeftOrigin() throws IOException {
        // OBJ 的 v 轴向上，MC 纹理 v 轴向下，parse 须做 1-v 翻转
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "vt 0 0.25",
                "f 1/1 2/1 3/1");
        float[] data = firstGroup(obj);
        assertEquals(0.75f, data[4], 1e-6f);
        assertEquals(0.75f, data[12], 1e-6f);
        assertEquals(0.75f, data[20], 1e-6f);
    }

    @Test
    public void triangulatesPolygonAsFan() throws IOException {
        // 一个四边形应产生两个三角形 = 6 个顶点
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 1 1 0", "v 0 1 0",
                "f 1 2 3 4");
        assertEquals(6 * ObjMesh.STRIDE, firstGroup(obj).length);
    }

    @Test
    public void supportsNegativeRelativeIndices() throws IOException {
        // -1 指最后一个顶点，-3 指倒数第三个
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "f -3 -2 -1");
        float[] data = firstGroup(obj);
        assertEquals(3 * ObjMesh.STRIDE, data.length);
        assertArrayEquals(new float[]{0, 0, 0}, position(data, 0), 1e-6f);
        assertArrayEquals(new float[]{1, 0, 0}, position(data, 1), 1e-6f);
        assertArrayEquals(new float[]{0, 1, 0}, position(data, 2), 1e-6f);
    }

    @Test
    public void skipsCommentsAndBlankLines() throws IOException {
        String obj = String.join("\n",
                "# leading comment",
                "",
                "v 0 0 0",
                "   ",
                "# mid comment",
                "v 1 0 0", "v 0 1 0",
                "f 1 2 3");
        assertEquals(3 * ObjMesh.STRIDE, firstGroup(obj).length);
    }

    @Test
    public void dropsEmptyGroups() throws IOException {
        // 声明了 g empty 但没有面，不应出现在结果里
        String obj = String.join("\n",
                "g empty",
                "g real",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "f 1 2 3");
        Map<String, float[]> groups = parse(obj);
        assertEquals(1, groups.size());
        assertTrue(groups.containsKey("real"));
        assertFalse(groups.containsKey("empty"));
    }

    @Test
    public void dropsWholeFaceOnBadIndexInsteadOfWritingHalf() throws IOException {
        // 4 边形中第 3 个索引越界：整面丢弃，不写出半个面。
        // 该面被丢尽后分组为空，因此结果里不应有任何分组。
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "f 1 2 99 3");
        assertTrue(parse(obj).isEmpty());
    }

    @Test
    public void usesDefaultGroupWhenNoGroupStatement() throws IOException {
        String obj = String.join("\n",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "f 1 2 3");
        assertTrue(parse(obj).containsKey(ObjMesh.DEFAULT_GROUP));
    }

    @Test
    public void rejectsNonFiniteFloatsInsteadOfPoisoningTheMesh() throws IOException {
        // Float.parseFloat 静默接受 "nan" / "1e999"(=Infinity)。若放行，整块网格会在
        // GPU 端退化成不可见的 NaN 三角形，所以这类行必须被跳过。
        String obj = String.join("\n",
                "v 0 0 0", "v nan 1 2", "v 1 0 0", "v 1e999 0 1", "v 0 1 0",
                "f 1 2 3");
        float[] data = firstGroup(obj);
        for (float component : data) {
            assertTrue("不应出现 NaN/Infinity: " + component, Float.isFinite(component));
        }
        // 两条坏 v 被丢弃后，f 1 2 3 引用的是剩下的第 1/3/4 个顶点
        assertEquals(3 * ObjMesh.STRIDE, data.length);
    }

    @Test
    public void aTruncatedNumericLineDropsOnlyItself() throws IOException {
        // 「v 0 0」数量不足，历史上会抛 ArrayIndexOutOfBoundsException 毁掉整个文件；
        // 正确的容错是只丢这一行，后续顶点与面照常解析。
        String obj = String.join("\n",
                "v 0 0",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "f 1 2 3");
        assertEquals(3 * ObjMesh.STRIDE, firstGroup(obj).length);
    }

    @Test
    public void aNonNumericTokenDropsOnlyItsLine() throws IOException {
        // v/vt/vn 三种行各塞一个坏 token：非数字、含 NaN、缺分量。
        // 每一行都只丢自己，后面的合法数据仍要正常解析出来。
        String obj = String.join("\n",
                "v 0 0 abc",
                "vt 0 nan 0",
                "vn 0 0 1.5.5",
                "v 0 0 0", "v 1 0 0", "v 0 1 0",
                "vt 0 0",
                "vn 0 0 1",
                "f 1/1/1 2/1/1 3/1/1");
        float[] data = firstGroup(obj);
        assertEquals(3 * ObjMesh.STRIDE, data.length);
        assertArrayEquals(new float[]{0, 0, 1}, new float[]{data[5], data[6], data[7]}, 1e-6f);
    }

    @Test
    public void rejectsGroupsWithAPartialTrailingTriangle() throws IOException {
        // 长度不是 STRIDE*3 整数倍意味着末尾有半个三角形；留着会让 triangleCount
        // 被静默截断、绘制时越界，所以 of() 应当丢弃这种分组。
        Map<String, float[]> groups = new LinkedHashMap<>();
        groups.put("whole", new float[ObjMesh.STRIDE * 3]);
        groups.put("partial", new float[ObjMesh.STRIDE * 3 + ObjMesh.STRIDE]);
        ObjMesh mesh = ObjMesh.of(groups);
        assertTrue(mesh.hasGroup("whole"));
        assertFalse(mesh.hasGroup("partial"));
        assertEquals(1, mesh.triangleCount("whole"));
    }

    @Test
    public void preservesGroupOrder() throws IOException {
        String obj = String.join("\n",
                "g zeta", "v 0 0 0", "v 1 0 0", "v 0 1 0", "f 1 2 3",
                "g alpha", "v 0 0 0", "v 1 0 0", "v 0 1 0", "f 1 2 3",
                "g mid", "v 0 0 0", "v 1 0 0", "v 0 1 0", "f 1 2 3");
        assertEquals("[zeta, alpha, mid]", parse(obj).keySet().toString());
    }

    @Test
    public void reportsTriangleCountAndAvailability() throws IOException {
        ObjMesh mesh = ObjMesh.of(parse(String.join("\n",
                "g body",
                "v 0 0 0", "v 1 0 0", "v 0 1 0", "v 1 1 0",
                "f 1 2 3",
                "f 2 4 3")));
        assertTrue(mesh.isAvailable());
        assertTrue(mesh.hasGroup("body"));
        assertFalse(mesh.hasGroup("nope"));
        assertEquals(2, mesh.triangleCount("body"));
        assertEquals(0, mesh.triangleCount("nope"));
        assertEquals(6 * ObjMesh.STRIDE, mesh.group("body").length);
    }

    @Test
    public void emptyMeshIsUnavailable() {
        ObjMesh empty = ObjMesh.empty();
        assertFalse(empty.isAvailable());
        assertTrue(empty.groupNames().isEmpty());
    }
}
