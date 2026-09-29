package cn.ism.mekck.blockentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 钉住中央厨房「加工线程必须落盘」——线程一旦随区块卸载消失，
 * 玩家已经扣掉的材料就<b>永久损失</b>，且没有任何补偿路径。
 *
 * <p>为什么用源码断言而不是运行时往返：{@code CentralKitchenBlockEntity} 的构造链会
 * 触发 Forge 注册期状态，在裸 JUnit 里加载必然失败（同包
 * {@code TestNbtPersistenceInvariants} 的既有做法——读源码比对键）。
 * 本测试只保证「该写的键确实写了、该读的键确实读了」，端到端由实机确认。</p>
 *
 * <p>为什么需要这条测试：既有的
 * {@code saveAdditionalAndSaveToItemWriteTheSameReadbackKeys} 只能发现
 * 「save 与 load 不对称」，发现不了「两处都没有」——I7 正是后者
 * （{@code threads} 从未被序列化），所以它一路绿灯放过了这个数据丢失缺陷。</p>
 */
public class TestCentralKitchenThreadPersistence {

    private static String source() throws IOException {
        Path p = Path.of("src/main/java/cn/ism/mekck/blockentity/CentralKitchenBlockEntity.java");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 截取 {@code saveAdditional} / {@code load} 的方法体（按签名定位到下一个同缩进的方法）。 */
    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("未找到方法：" + signature, start >= 0);
        int from = src.indexOf("{", start);
        int depth = 0;
        for (int i = from; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(from, i);
        }
        return src.substring(from);
    }

    @Test
    public void threadsAreWrittenOnSaveAndReadOnLoad() throws IOException {
        String src = source();
        String save = body(src, "protected void saveAdditional(CompoundTag tag)");
        String load = body(src, "public void load(CompoundTag tag)");

        assertTrue("saveAdditional 必须写出 Threads，否则已扣料的线程随区块卸载消失",
                save.contains("put(\"Threads\""));
        assertTrue("load 必须读回 Threads",
                load.contains("contains(\"Threads\""));
    }

    /** 线程恢复所需的每一个状态都要落盘，缺一个都会导致续跑时丢料或进度归零。 */
    @Test
    public void everyResumeCriticalThreadFieldIsPersisted() throws IOException {
        String save = body(source(), "protected void saveAdditional(CompoundTag tag)");
        String load = body(source(), "public void load(CompoundTag tag)");

        List<String> missing = new ArrayList<>();
        for (String key : new String[]{"Recipe", "Consumes", "Outputs", "Progress", "Total"}) {
            if (!save.contains("\"" + key + "\"")) missing.add("save 缺 " + key);
            if (!load.contains("\"" + key + "\"")) missing.add("load 缺 " + key);
        }
        assertTrue("线程续跑所需字段未全部持久化：\n  " + String.join("\n  ", missing), missing.isEmpty());
    }

    /** 槽位索引是跨存档的硬引用，越界即说明机器被换过型，必须拒绝而不是照用。 */
    @Test
    public void staleSlotIndicesAreRejectedOnLoad() throws IOException {
        String load = body(source(), "public void load(CompoundTag tag)");
        assertTrue("读取 consumes 时必须校验槽位下标仍在 items.getSlots() 内",
                load.contains("items.getSlots()"));
    }

    /** 线程数由已安装模块决定，读档时必须先对齐长度，否则索引会串到别的系列的线程上。 */
    @Test
    public void threadListIsResizedToTheInstalledModuleCountOnLoad() throws IOException {
        String load = body(source(), "public void load(CompoundTag tag)");
        assertTrue("读档时必须按当前模块能力对齐线程数",
                load.contains("abilityOf(") && load.contains("threads()"));
    }
}
