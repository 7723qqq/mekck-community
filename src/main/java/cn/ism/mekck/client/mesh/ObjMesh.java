package cn.ism.mekck.client.mesh;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用 OBJ 网格：任意分组名 → 每组一份展平的三角形顶点数据。
 *
 * <h2>分组即骨骼</h2>
 * 解析器按 {@code g} 切分分组，调用方对每组施加各自的 {@code PoseStack} 变换 ——
 * 这就是骨架。分组名沿用 geo 的骨骼名，动画逻辑可以平移。
 *
 * <h2>为什么本类不依赖 Minecraft</h2>
 * {@link #parse} 与所有辅助方法都是纯函数，不碰 Minecraft / GL，因此可以在普通 JVM
 * 单元测试中直接运行。资源读取在 {@link ObjMeshLoader}，绘制在 {@link ObjMeshRenderer}。
 *
 * <p>移植自 mek_adapter（MC 1.12.2）的同名类，仅保留解析部分；
 * 原版的 display list 编译与 GL 立即模式绘制在 1.20.1 已不可用，未随之移植。</p>
 */
public final class ObjMesh {

    /** 无 {@code g} 分组时，所有面归入此组。 */
    public static final String DEFAULT_GROUP = "default";

    /** 每顶点 float 数：x, y, z, u, v, nx, ny, nz */
    static final int STRIDE = 8;

    private final Map<String, float[]> groups;

    private ObjMesh(Map<String, float[]> groups) {
        this.groups = groups;
    }

    /** 空网格：加载或解析失败时返回，{@link #isAvailable()} 为 false。 */
    public static ObjMesh empty() {
        return new ObjMesh(Collections.emptyMap());
    }

    /**
     * 由已解析的分组数据构造。
     *
     * <p>空分组会被丢弃；<b>长度不是 {@code STRIDE * 3} 整数倍（即含半个三角形）的分组
     * 同样会被丢弃</b>——留着它会让 {@link #triangleCount} 与 {@link #group} 的语义对不上
     * （绘制时末尾残缺的顶点会按越界处理掉，三角形数还会被静默截断）。</p>
     */
    public static ObjMesh of(Map<String, float[]> parsed) {
        if (parsed.isEmpty()) {
            return empty();
        }
        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, float[]> e : parsed.entrySet()) {
            float[] data = e.getValue();
            if (data != null && data.length >= STRIDE * 3 && data.length % (STRIDE * 3) == 0) {
                out.put(e.getKey(), data);
            }
        }
        return out.isEmpty() ? empty() : new ObjMesh(out);
    }

    // ==================== 纯解析（可单测） ====================

    /**
     * 解析 OBJ 文本，返回 分组名 → 展平的三角形顶点数组（每顶点 {@value #STRIDE} 个 float）。
     *
     * <p>支持：{@code v} / {@code vt} / {@code vn} / {@code g} / {@code f}；
     * 多边形扇形三角化；负数相对索引；{@code #} 注释。空分组不进入结果。</p>
     *
     * <p><b>容错</b>：坏数据一律只丢最小的那一块，且规则一致——
     * 非有限浮点（NaN / Infinity）会让整行被跳过，越界的面索引只丢那一个面，
     * 数量不足的数值行也只丢自己。绝不因为一个坏 token 抛异常毁掉整个文件。</p>
     */
    public static Map<String, float[]> parse(BufferedReader reader) throws IOException {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        Map<String, List<Float>> acc = new LinkedHashMap<>();
        List<Float> current = null;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (line.startsWith("v ")) {
                float[] v = floats(line, 3);
                if (v != null) {
                    positions.add(v);
                }
            } else if (line.startsWith("vt ")) {
                float[] vt = floats(line, 2);
                if (vt != null) {
                    // OBJ 的 v 轴向上，MC 纹理 v 轴向下
                    texCoords.add(new float[]{vt[0], 1.0f - vt[1]});
                }
            } else if (line.startsWith("vn ")) {
                float[] vn = floats(line, 3);
                if (vn != null) {
                    normals.add(vn);
                }
            } else if (line.startsWith("g ")) {
                String name = line.substring(2).trim();
                if (name.isEmpty()) {
                    name = DEFAULT_GROUP;
                }
                current = acc.computeIfAbsent(name, k -> new ArrayList<>());
            } else if (line.startsWith("f ")) {
                if (current == null) {
                    current = acc.computeIfAbsent(DEFAULT_GROUP, k -> new ArrayList<>());
                }
                appendFace(line, positions, texCoords, normals, current);
            }
        }

        Map<String, float[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Float>> e : acc.entrySet()) {
            List<Float> v = e.getValue();
            if (v.isEmpty()) {
                continue;
            }
            float[] arr = new float[v.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = v.get(i);
            }
            out.put(e.getKey(), arr);
        }
        return out;
    }

    private static void appendFace(String line, List<float[]> positions, List<float[]> texCoords,
                                   List<float[]> normals, List<Float> out) {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) {
            return;
        }
        int n = parts.length - 1;
        float[][] poly = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] v = parseVertex(parts[i + 1], positions, texCoords, normals);
            if (v == null) {
                return; // 索引坏了，整面丢弃而不是写半个面
            }
            poly[i] = v;
        }
        for (int i = 1; i + 1 < n; i++) {
            emit(out, poly[0]);
            emit(out, poly[i]);
            emit(out, poly[i + 1]);
        }
    }

    private static void emit(List<Float> out, float[] v) {
        for (float f : v) {
            out.add(f);
        }
    }

    private static float[] parseVertex(String token, List<float[]> positions,
                                       List<float[]> texCoords, List<float[]> normals) {
        String[] idx = token.split("/");
        try {
            float[] pos = positions.get(resolve(idx[0], positions.size()));
            float u = 0.0f;
            float v = 0.0f;
            if (idx.length > 1 && !idx[1].isEmpty()) {
                float[] vt = texCoords.get(resolve(idx[1], texCoords.size()));
                u = vt[0];
                v = vt[1];
            }
            float nx = 0.0f;
            float ny = 0.0f;
            float nz = 0.0f;
            if (idx.length > 2 && !idx[2].isEmpty()) {
                float[] vn = normals.get(resolve(idx[2], normals.size()));
                nx = vn[0];
                ny = vn[1];
                nz = vn[2];
            }
            return new float[]{pos[0], pos[1], pos[2], u, v, nx, ny, nz};
        } catch (IndexOutOfBoundsException | NumberFormatException e) {
            return null;
        }
    }

    /** OBJ 索引：正数 1-based，负数相对末尾。 */
    private static int resolve(String token, int size) {
        int i = Integer.parseInt(token);
        return i > 0 ? i - 1 : size + i;
    }

    /**
     * 读一行 {@code v} / {@code vt} / {@code vn} 的数值分量。
     *
     * <p>数量不足、非数字、或出现 NaN / Infinity 时返回 {@code null}，由调用方跳过该行。
     * {@link Float#parseFloat} 会静默接受 {@code "nan"} 与 {@code "1e999"}，若放它们进顶点
     * 数据，整块网格会在 GPU 端退化成不可见的 NaN 三角形——本仓库的
     * {@code MekCkOutlineRenderer} 早已因同类问题加了 {@code Float.isFinite} 防线。</p>
     */
    private static float[] floats(String line, int count) {
        String[] parts = line.split("\\s+");
        if (parts.length < count + 1) {
            return null;
        }
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            float v;
            try {
                v = Float.parseFloat(parts[i + 1]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (!Float.isFinite(v)) {
                return null;
            }
            out[i] = v;
        }
        return out;
    }

    // ==================== 查询 ====================

    public boolean isAvailable() {
        return !groups.isEmpty();
    }

    public Set<String> groupNames() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    public boolean hasGroup(String name) {
        return groups.containsKey(name);
    }

    /** 该分组的三角形数；分组不存在时为 0。{@link #of} 已保证每组都是完整三角形。 */
    public int triangleCount(String name) {
        float[] data = groups.get(name);
        return data == null ? 0 : data.length / STRIDE / 3;
    }

    /**
     * 该分组的展平顶点数据；分组不存在时为 null。每 {@value #STRIDE} 个 float 为一个顶点。
     *
     * <p><b>返回的是内部数组，请按只读对待。</b>这里不克隆是为了避免每帧为 1352 个顶点
     * 复制 43KB；{@link ObjMesh} 之所以自称不可变，指的是它从不写入这批数组。</p>
     */
    public float[] group(String name) {
        return groups.get(name);
    }
}
