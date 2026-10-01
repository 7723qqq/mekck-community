package cn.ism.mekck.blockentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 存档键的<b>写读对称性</b>护栏（跨文件版）。
 *
 * <h3>它挡的是哪一类事故</h3>
 * 「写得出、读不回」= 存档静默丢数据。这类缺陷的特征是**编译通过、测试全绿、启动无异常**，
 * 只有玩家打开旧存档时才发现。
 * 本轮真的发生过一次：流体子系统从 BE 抽出时，
 * {@code SimpleMachineFluids#readTanks} 被搬成了**空方法**，
 * 而 {@code SimpleMachineBlockEntity#load} 照常调用它 ——
 * 结果是所有旧存档里罐中的流体变空。发现它靠的是一次性的人工比对；
 * 本测试把那次比对**固化下来**。
 *
 * <h3>为什么必须跨文件看</h3>
 * {@link TestTankNbtSymmetry} 只看 {@code SimpleMachineFluids} 一个类内部的
 * write/read 对称 —— 那次事故它**抓不到**，因为读回的调用在 BE 里、键的写出在新类里。
 * 所以这里按「这一族方块实体的存档面」整体统计：
 * {@code SimpleMachineBlockEntity}（写主体键 + 委托流体写入）+ {@code SimpleMachineFluids}。
 *
 * <h3>口径说明</h3>
 * * 只统计 {@code tag.putXxx("Key", …)} 形式的键；经由 {@code CompoundTag} 子对象
 *   序列化的（{@code MekckTavernBatch} 等）只统计外层键 —— 子对象的键由它自己的类负责。
 * * 断言的是<b>集合相等</b>，不关心键的具体拼写：键名可以自由演进，
 *   但「写出去的每一个都必须被读回来」这条永远成立。
 */
public class TestNbtWriteReadSymmetry {

    private static final Path BE = Path.of("src", "main", "java", "cn", "ism", "mekck",
            "blockentity", "SimpleMachineBlockEntity.java");

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

    private static final Pattern PUT = Pattern.compile("tag\\.put\\w*\\s*\\(\\s*\"([A-Za-z][A-Za-z0-9_]*)\"");

    private static final Pattern GET = Pattern.compile(
            "tag\\.(?:get\\w*|contains)\\s*\\(\\s*\"([A-Za-z][A-Za-z0-9_]*)\"");

    private static TreeSet<String> collect(Pattern p, String... sources) {
        TreeSet<String> out = new TreeSet<>();
        for (String s : sources) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    @Test
    public void everyWrittenKeyIsReadBack() throws IOException {
        String be = Files.readString(BE, StandardCharsets.UTF_8);
        String fl = Files.readString(FLUIDS, StandardCharsets.UTF_8);

        // 写出面：BE 的两个保存方法 + 流体子系统的罐写出（BE 通过委托调用它）
        String writeSide = methodBody(be, "public void saveAdditional(CompoundTag tag)")
                + methodBody(be, "public void saveToItem(ItemStack stack)")
                + methodBody(fl, "public void writeTanks(CompoundTag tag)");
        // 读回面：BE 的载入方法 + 流体子系统的罐读回
        String readSide = methodBody(be, "public void load(CompoundTag tag)")
                + methodBody(fl, "public void readTanks(CompoundTag tag)");

        TreeSet<String> writes = collect(PUT, writeSide);
        TreeSet<String> reads = collect(GET, readSide);

        assertTrue("没扫到任何写入键，判据空转了（是存档面改名/搬家了吗？）", writes.size() >= 20);
        assertEquals("这些键写进了存档却没被读回来（旧存档加载后它们会静默变默认值）：\n  "
                + diff(writes, reads), writes, reads);
    }

    @Test
    public void everyReadKeyIsAlsoWritten() throws IOException {
        String be = Files.readString(BE, StandardCharsets.UTF_8);
        String fl = Files.readString(FLUIDS, StandardCharsets.UTF_8);
        String writeSide = methodBody(be, "public void saveAdditional(CompoundTag tag)")
                + methodBody(be, "public void saveToItem(ItemStack stack)")
                + methodBody(fl, "public void writeTanks(CompoundTag tag)");
        String readSide = methodBody(be, "public void load(CompoundTag tag)")
                + methodBody(fl, "public void readTanks(CompoundTag tag)");
        assertEquals("这些键被读回来了，但没有任何地方写入（新存档里它们永远是默认值）：\n  "
                + diff(collect(GET, readSide), collect(PUT, writeSide)),
                collect(PUT, writeSide), collect(GET, readSide));
    }

    private static String diff(TreeSet<String> a, TreeSet<String> b) {
        TreeSet<String> onlyA = new TreeSet<>(a);
        onlyA.removeAll(b);
        return onlyA.toString();
    }
}