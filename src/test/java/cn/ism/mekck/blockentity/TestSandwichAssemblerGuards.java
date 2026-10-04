package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 三明治组装机的读档 / 加工守恒护栏。
 *
 * <p>为什么只能钉源码：{@code SandwichAssemblerBlockEntity} 的构造链会触发 Forge 注册期状态，
 * 裸 JUnit 里加载必然失败（同包 {@code TestCentralKitchenThreadPersistence} 的既有做法）。
 * 本类只保证「形态正确」，端到端由实机确认。</p>
 */
public class TestSandwichAssemblerGuards {

    private static final String BE = "src/main/java/cn/ism/mekck/blockentity/SandwichAssemblerBlockEntity.java";

    private static String body(String signature) throws IOException {
        String body = TestSourceText.methodBody(TestSourceText.read(BE), signature);
        assertFalse("找不到 " + signature + "（源码测试需在仓库根目录运行）", body.isEmpty());
        return body;
    }

    /**
     * 读档必须用循环灌能量。
     *
     * <p>单次 {@code receiveEnergy} 受 {@code maxReceive}（本类 5,000 FE）夹断，
     * 容量 100,000 的电池每次区块重载最多只恢复 5,000，其余 95,000 静默丢失。</p>
     */
    @Test
    public void energyLoadMustLoopAroundMaxReceive() throws IOException {
        String load = body("public void load(CompoundTag tag)");
        assertFalse("读档不得再用单次 receiveEnergy(tag.getInt(\"Energy\"), false)："
                        + "单次调用受 maxReceive（5,000 FE）夹断，容量 100,000 每次重载最多只恢复 5,000",
                load.contains("receiveEnergy(tag.getInt(\"Energy\"), false)"));
        assertTrue("读档必须用 while 循环灌能量（照 MekCkLegacyMachine.load）",
                load.contains("while (remaining > 0)"));
        assertTrue("循环必须累计实际接收量（remaining -= received）",
                load.contains("remaining -= received"));
    }

    /**
     * 读档必须夹紧 Mode 到 [MODE_COPY, MODE_SEQUENCED]。
     *
     * <p>越界值会让 {@code tick} 走 {@code orderedLayers()} 分支而 {@code batchSize}
     * 按非自定义处理，行为漂移；{@code setMode} 早就夹紧，读档是漏网的一侧。</p>
     */
    @Test
    public void loadedModeMustBeClamped() throws IOException {
        String load = body("public void load(CompoundTag tag)");
        assertFalse("读档不得原样接受越界 Mode（mode = tag.getInt(\"Mode\")）",
                load.contains("mode = tag.getInt(\"Mode\")"));
        assertTrue("读档必须与 setMode 同口径夹紧 Mode 到 [MODE_COPY, MODE_SEQUENCED]",
                load.contains("MODE_COPY") && load.contains("MODE_SEQUENCED"));
    }

    /**
     * 完成时必须重新校验材料与输出空间。
     *
     * <p>旧实现只在 {@code progress == 0}（开工时）校验一次；加工期间玩家取走材料后，
     * {@code consumeMaterials} 对每层只取「槽里还剩多少」、不校验是否取满，随后无条件
     * {@code insertOutput} ⇒ 只扣到 4 个却产出 64 个（物品复制）。</p>
     */
    @Test
    public void produceMustRevalidateBeforeConsuming() throws IOException {
        String produce = body("private void produce(");
        int check = produce.indexOf("canProduce(layers)");
        int consume = produce.indexOf("consumeMaterials(");
        assertTrue("produce 必须在扣料/产出前重新校验 canProduce(layers)："
                        + "加工期间取走材料时旧实现只扣到多少就产多少（复制）",
                check >= 0);
        assertTrue("重新校验必须在 consumeMaterials 之前", consume > check);
    }

    /**
     * {@code canProduce} 必须按层出现次数汇总需求。
     *
     * <p>同物品占多层（如两层面包）时每层各要 {@code batches} 份；逐层单独比较只查
     * 1 份 ⇒ 扣不满也照产，是同一复制缺陷的另一条路径。</p>
     */
    @Test
    public void canProduceAggregatesDuplicateLayers() throws IOException {
        String canProduce = body("private boolean canProduce(");
        assertTrue("canProduce 必须按层出现次数汇总需求（同物品占多层时每层各要 batches 份）",
                canProduce.contains("occurrences"));
    }

    /**
     * 输出槽放不下的产物余量必须走掉落兜底。
     *
     * <p>旧 {@code insertOutput} 在输出槽被换成异类物品、或同类但余量不足时直接丢弃
     * 局部 stack，无日志无掉落。</p>
     */
    @Test
    public void outputOverflowFallsBackToDrops() throws IOException {
        String insert = body("private void insertOutput(");
        assertTrue("insertOutput 的余量必须走掉落兜底，不得静默丢弃",
                insert.contains("dropOutputOverflow"));
        String drop = body("private void dropOutputOverflow(");
        assertTrue("掉落兜底必须用 BigStackDrops（大堆叠感知）", drop.contains("BigStackDrops"));
    }
}
