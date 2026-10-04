package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 中央厨房的读档 / 订单守恒 / 自动 IO 护栏。
 *
 * <p>为什么只能钉源码：{@code CentralKitchenBlockEntity} 的构造链会触发 Forge 注册期状态，
 * 裸 JUnit 里加载必然失败（同包 {@code TestCentralKitchenThreadPersistence} 的既有做法）。
 * 本类只保证「形态正确」，端到端由实机确认。</p>
 */
public class TestCentralKitchenGuards {

    private static final String BE = "src/main/java/cn/ism/mekck/blockentity/CentralKitchenBlockEntity.java";
    private static final String AUTO_IO = "src/main/java/cn/ism/mekck/util/AutoIO.java";

    private static String body(String signature) throws IOException {
        String body = TestSourceText.methodBody(TestSourceText.read(BE), signature);
        assertFalse("找不到 " + signature + "（源码测试需在仓库根目录运行）", body.isEmpty());
        return body;
    }

    /**
     * 读档必须用循环灌能量。
     *
     * <p>单次 {@code receiveEnergy} 受 {@code maxReceive}（本类 200,000 FE）夹断，
     * 容量 5,000,000 的电池每次区块重载最多只恢复 200,000，最多丢 4,800,000 FE。</p>
     */
    @Test
    public void energyLoadMustLoopAroundMaxReceive() throws IOException {
        String load = body("public void load(CompoundTag tag)");
        assertFalse("读档不得再用单次 receiveEnergy(tag.getInt(\"Energy\"), false)："
                        + "单次调用受 maxReceive（200,000 FE）夹断，容量 5,000,000 每次重载最多丢 4,800,000",
                load.contains("receiveEnergy(tag.getInt(\"Energy\"), false)"));
        assertTrue("读档必须用 while 循环灌能量（照 MekCkLegacyMachine.load）",
                load.contains("while (remaining > 0)"));
        assertTrue("循环必须累计实际接收量（remaining -= received）",
                load.contains("remaining -= received"));
    }

    /**
     * 订单重建必须发生在 {@code onLoad}，不能在 {@code load} 里直接重建。
     *
     * <p>Forge 在 {@code setLevel} 之前调 {@code load}（{@code BlockEntity.loadStatic} 只
     * create + load，{@code LevelChunk.setBlockEntity} 之后才 setLevel），读档阶段
     * {@code getLevel()} 为 null；而重建步骤要查配方管理器。旧实现直接在 load 里取
     * {@code getLevel()}，于是每次读档都把全部订单当「重建失败」丢弃。</p>
     */
    @Test
    public void ordersAreRestoredInOnLoadNotInLoad() throws IOException {
        String load = body("public void load(CompoundTag tag)");
        assertTrue("load 必须把 Orders NBT 暂存起来（读档阶段 level 为 null，重建需要配方管理器）",
                load.contains("pendingOrdersTag"));
        assertFalse("load 不得直接重建订单（此时 getLevel() 为 null，重建必然失败 ⇒ 全部订单被丢弃）",
                load.contains("getRecipeManager"));
        String onLoad = body("public void onLoad()");
        assertTrue("onLoad 必须重建订单（此时 level 已就绪）", onLoad.contains("restoreOrders("));
    }

    /**
     * 读档丢弃订单前必须把暂存区退回存储区/掉落。
     *
     * <p>steps 重建失败（配方被删/改名）或超过 {@code MAX_ORDERS} 截断时，订单对象连同
     * 它的 buffer（已预留叶子材料 + 在制品）一起被丢弃 —— 旧实现无掉落无日志。</p>
     */
    @Test
    public void discardedOrdersRefundTheirBuffer() throws IOException {
        String restore = body("private void restoreOrders(");
        // 两条丢弃路径（steps 重建失败 / MAX_ORDERS 截断）都必须回插，缺一条就是静默销毁
        int empty = restore.indexOf("if (steps.isEmpty())");
        int firstRefund = restore.indexOf("refundDiscardedOrderBuffer", empty);
        assertTrue("steps 重建失败的订单丢弃前必须回插/掉落 buffer", empty >= 0 && firstRefund > empty);
        int secondRefund = restore.indexOf("refundDiscardedOrderBuffer", firstRefund + 1);
        assertTrue("MAX_ORDERS 截断丢弃的订单同样必须回插/掉落 buffer", secondRefund > firstRefund);
        String refund = body("private void refundDiscardedOrderBuffer(");
        assertTrue("回插存储区", refund.contains("insertIntoStorage"));
        assertTrue("回插仍放不下必须掉落 + 告警", refund.contains("dropReservedOverflow"));
    }

    /**
     * 步骤产物写入暂存区时必须消费 {@code insertIntoBuffer} 的返回值。
     *
     * <p>旧实现丢弃返回值：暂存区满时本步产物被静默销毁，而 {@code advanceStep()} 照常推进
     * ⇒ 下一步 {@code bufferHas} 永远为假，订单永久卡死。</p>
     */
    @Test
    public void tickOrdersConsumesInsertIntoBufferLeftover() throws IOException {
        String tick = body("private void tickOrders(");
        // 主产物与副产物两条写入路径都必须消费返回值：只接住一条，另一条仍会静默销毁
        int inserts = count(tick, "insertIntoBuffer(order,");
        int spills = count(tick, "spillBufferOverflow(");
        assertTrue("tickOrders 里找不到 insertIntoBuffer 调用", inserts > 0);
        assertTrue("tickOrders 必须接住每一处 insertIntoBuffer 的返回值（旧实现丢弃 ⇒ 暂存区满时产物静默销毁）："
                        + "insertIntoBuffer=" + inserts + "，spillBufferOverflow=" + spills,
                spills == inserts);
        String spill = body("private void spillBufferOverflow(");
        assertTrue("余量必须先回插存储区", spill.contains("insertIntoStorage"));
        assertTrue("回插仍放不下必须掉落 + 告警", spill.contains("dropReservedOverflow"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 步骤完成前必须预检暂存区空间，装不下就不推进。
     *
     * <p>这是「暂存区满时不推进步骤」的实现：在扣料/产出前用副本模拟「扣输入 + 插产物」，
     * 装不下就暂停本步（不扣料、不产出、不推进），避免订单走进「等一个已经不在暂存区的
     * 中间产物」的死状态。</p>
     */
    @Test
    public void stepCompletionIsPrecheckedAgainstBufferSpace() throws IOException {
        String tick = body("private void tickOrders(");
        assertTrue("完成前必须预检暂存区空间（装不下就不推进步骤）",
                tick.contains("bufferCanHoldStepOutputs"));
        String check = body("private boolean bufferCanHoldStepOutputs(");
        assertTrue("预检必须模拟扣除本步输入", check.contains("step.inputs"));
        assertTrue("预检必须模拟插入本步产物", check.contains("insertOutputs"));
    }

    /**
     * 退款 / 回滚路径必须消费 {@code insertIntoStorage} 的返回值。
     *
     * <p>存储区满时退款物品静默消失；{@code reserveLeaves} 有 {@code dropReservedOverflow}
     * 兜底，这两条没有。</p>
     */
    @Test
    public void refundPathsConsumeInsertIntoStorageLeftover() throws IOException {
        String threads = body("private void refundOrphanedThreads(");
        assertTrue("退款路径必须接住 insertIntoStorage 的返回值并走掉落兜底",
                threads.contains("dropReservedOverflow"));
        String buffer = body("private void refundBuffer(");
        assertTrue("回滚路径必须接住 insertIntoStorage 的返回值并走掉落兜底",
                buffer.contains("dropReservedOverflow"));
    }

    /**
     * 流体自动 IO 的邻居解析必须先判 {@code hasChunkAt}。
     *
     * <p>{@code Level.getBlockState} 对未加载区块会触发区块加载/生成；Bioreactor 的
     * {@code emitEnergy} 与热量组件都显式先判，这两处（中央厨房 + 共享 AutoIO）漏了。</p>
     */
    @Test
    public void fluidAutoIoChecksChunkLoaded() throws IOException {
        String fluid = body("private net.minecraftforge.fluids.capability.IFluidHandler fluidHandlerAt(");
        assertTrue("流体邻居解析必须先判 hasChunkAt（getBlockState 会触发未加载区块的加载/生成）",
                fluid.contains("hasChunkAt"));
        String resolve = TestSourceText.methodBody(TestSourceText.read(AUTO_IO), "private IItemHandler resolve(");
        assertFalse("找不到 AutoIO.resolve", resolve.isEmpty());
        assertTrue("AutoIO 的邻居解析同样必须先判 hasChunkAt", resolve.contains("hasChunkAt"));
    }

    /**
     * 流体能力不得再出现「按侧配分支但两支返回同一对象」的死分支。
     *
     * <p>旧实现的注释声称「抽取面只接受注入、弹出面只允许抽取」，但两个分支返回同一个
     * {@code fluidCapability} —— 注释与实现相反。本轮选择删死分支并订正注释（另一条路是
     * 实现真正的门控，但那会改变外部自动化行为）。</p>
     */
    @Test
    public void fluidCapabilityHasNoDeadSideBranch() throws IOException {
        String cap = body("public <T> LazyOptional<T> getCapability(");
        int fluid = cap.indexOf("ForgeCapabilities.FLUID_HANDLER");
        assertTrue("找不到 FLUID_HANDLER 分支", fluid >= 0);
        String branch = cap.substring(fluid, Math.min(cap.length(), fluid + 300));
        assertFalse("流体能力不得再出现「按 fluidSideConfig 分支但两支返回同一对象」的死分支",
                branch.contains("fluidSideConfig"));
    }
}
