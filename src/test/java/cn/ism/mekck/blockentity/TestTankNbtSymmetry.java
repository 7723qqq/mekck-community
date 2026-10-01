package cn.ism.mekck.blockentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 流体罐 NBT 的<b>写读对称性</b>护栏。
 *
 * <h3>它挡的是哪一类事故</h3>
 * 流体子系统从 {@code SimpleMachineBlockEntity} 抽出来时，罐的存档读写被搬进
 * {@link SimpleMachineFluids} 的 {@code writeTanks} / {@code readTanks}。
 * 实际发生过一次事故：**{@code readTanks} 被搬成了空方法**，而 {@code load()} 照常调用它 ——
 * 编译通过、457 条测试全绿、启动不报错，**而所有旧存档里罐里的流体静默变空**。
 *
 * <p>为什么护栏没能抓住：现有断言关心的是「配方匹配 / NBT 字段 / 侧配」这些**内容**，
 * 没有任何一条关心「写出去的键有没有被读回来」。这类缺陷只在玩家打开旧存档时显形。</p>
 *
 * <p>所以这里钉的是<b>对称性</b>：写出去的键集合必须与读回来的键集合完全相等。
 * 它不需要知道键名是什么（键名可以自由演进），也不需要对比 git 历史
 * （测试运行时未必有仓库），因此能在任何一次重构之后继续生效。</p>
 */
public class TestTankNbtSymmetry {

    private static final Path FLUIDS = Path.of("src", "main", "java", "cn", "ism", "mekck",
            "blockentity", "SimpleMachineFluids.java");

    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        assertTrue("找不到方法：" + signature, i >= 0);
        int open = src.indexOf('{', i);
        int depth = 0;
        for (int j = open; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, j);
                }
            }
        }
        return src.substring(open + 1);
    }

    private static TreeSet<String> keys(String body) {
        TreeSet<String> out = new TreeSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"([A-Za-z][A-Za-z0-9_]*)\"").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    public void readTanksMustReadBackEverythingWriteTanksWrote() throws IOException {
        String src = Files.readString(FLUIDS, StandardCharsets.UTF_8);
        String write = methodBody(src, "public void writeTanks(CompoundTag tag)");
        String read = methodBody(src, "public void readTanks(CompoundTag tag)");

        assertTrue("writeTanks 是空方法：存档根本不会写入罐内容", !write.trim().isEmpty());
        assertTrue("readTanks 是空方法：旧存档里的罐内容会**静默丢失**"
                + "（编译与测试都不会发现，只有玩家打开旧存档时显形）", !read.trim().isEmpty());

        assertEquals("罐的 NBT 键写读不对称：写出去但没读回来（或反过来）= 存档丢数据",
                keys(write), keys(read));
    }
}