package cn.ism.mekck.kitchen;

import cn.ism.mekck.util.BigStackItemHandler;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 中央厨房订单系统的<b>物品安全</b>护栏。
 *
 * <p>本类集中守三条「确定性物品增删」——它们都<b>不崩服、不报错</b>，只是东西不见了，
 * 因此没有任何既有测试会红。</p>
 */
public class TestKitchenOrderItemSafety {

    private static final String ORDER = "src/main/java/cn/ism/mekck/kitchen/KitchenOrder.java";
    private static final String BE = "src/main/java/cn/ism/mekck/blockentity/CentralKitchenBlockEntity.java";

    private static String read(String path) throws IOException {
        return cn.ism.mekck.TestSourceText.read(path);
    }

    /**
     * 订单暂存区必须用 {@link BigStackItemHandler}，不能是 vanilla {@code ItemStackHandler}。
     *
     * <h3>为什么这条是 Critical</h3>
     * 原实现是 {@code new ItemStackHandler(18) { getSlotLimit → Integer.MAX_VALUE-1 }}：
     * 槽上限放宽到 21 亿，却用 vanilla 的序列化。javap 坐实
     * （{@code forge-1.20.1-47.4.16}）{@code ItemStackHandler.serializeNBT()} 只做
     * {@code stack.save} + {@code putInt("Slot")}，<b>没有</b> int 型数量键；而
     * {@code ItemStack.save} 是 {@code ldc "Count"; getfield count; putByte}。
     *
     * <p>⇒ 300 个小麦存读一轮变成 {@code (byte)300 = 44}，<b>256 个被销毁</b>；
     * 数量落在 128..255 那一档时 {@code (byte)} 为负 ⇒ {@code count <= 0}
     * ⇒ {@code isEmpty()} 为真 ⇒ <b>整堆 100% 消失，一格不剩</b>。
     * 而「下一单要 300 个原料」是完全正常的操作。</p>
     */
    @Test
    public void orderBufferMustBeARealBigStackHandler() throws IOException {
        String src = read(ORDER);
        String decl = fieldDeclaration(src, "buffer");
        assertFalse("找不到 KitchenOrder.buffer 的声明", decl.isEmpty());
        assertTrue("订单暂存区必须用 BigStackItemHandler：用 vanilla ItemStackHandler 时"
                        + "槽上限虽被放宽到 21 亿，但 serializeNBT 只写 byte 型 Count"
                        + " ⇒ 每次存档都丢物品（300 → 44；128..255 那档整堆消失）",
                decl.contains("BigStackItemHandler"));
        assertFalse("订单暂存区不得是匿名 vanilla ItemStackHandler 子类（那是本缺陷的原始写法）",
                decl.contains("new ItemStackHandler("));
    }

    /**
     * 交付不完整时<b>不得</b>把订单从 {@code orders} 里移除。
     *
     * <p>原实现：{@code deliverOrder} 是 {@code void}，它把装不下的 leftover 放回
     * {@code order.buffer} 就返回，调用方紧接着 {@code iter.remove()}。此后那个 order
     * 对象再无任何引用（{@code saveAdditional} 只遍历 {@code orders}）⇒ 成品
     * <b>连同订单预留的剩余叶子材料</b>随对象被 GC，连存档都不记录。
     * 输出区被填满时这是必现的静默物品删除。</p>
     */
    @Test
    public void incompleteDeliveryMustNotDropTheOrder() throws IOException {
        String src = read(BE);

        String deliver = methodBody(src, "private void deliverOrder(");
        if (!deliver.isEmpty()) {
            throw new AssertionError("deliverOrder 仍是 void：它无法告诉调用方「是否交付完整」，"
                    + "而调用方必须据此决定能不能 iter.remove()。请改成返回 boolean。");
        }
        String booleanDeliver = methodBody(src, "private boolean deliverOrder(");
        assertFalse("deliverOrder 必须返回 boolean（是否已全部交付）", booleanDeliver.isEmpty());
        assertTrue("deliverOrder 必须在放不下的分支把 complete 置 false",
                booleanDeliver.contains("complete = false"));

        String tick = methodBody(src, "private void tickOrders(");
        assertTrue("tickOrders 必须按 deliverOrder 的返回值决定是否 iter.remove()",
                tick.contains("if (deliverOrder(order))"));
        assertTrue("iter.remove() 必须只出现在该 if 之内（交付不完整时订单要留下来重试）",
                tick.contains("if (deliverOrder(order)) {")
                        && tick.indexOf("iter.remove()") > tick.indexOf("if (deliverOrder(order)) {"));
    }

    /**
     * 三条「把一批同物合并进容器」的路径必须<b>全都</b>逐格夹紧。
     *
     * <p>{@code ItemStack.grow(n)} 就是 {@code setCount(getCount() + n)}，而 1.20.1 的
     * {@code ItemStack.setCount} <b>不做任何夹紧</b>。槽上限是 {@code Integer.MAX_VALUE-1} 时，
     * 两个大堆叠一合并就必然溢出为负 ⇒ {@code isEmpty()} 为真 ⇒ 该格被当空格 ⇒
     * 落盘时 {@code readStack} 的 {@code if (count <= 0) return EMPTY} 再确认一次删除。</p>
     *
     * <p>本条的价值在于<b>枚举完整性</b>：这个 bug 已经出现过三次
     * （{@code KitchenRecipeMatcher.insertOutputs} → {@code insertIntoStorage}
     * → {@code insertIntoBuffer}），每次都是「修了 A 漏了 B」。所以这里不信任
     * 「我已经改过」，而是把三条路径一起列出来逐个断言。</p>
     */
    @Test
    public void everyMergeIntoAContainerClampsPerSlot() throws IOException {
        // ① insertIntoBuffer
        String buffer = methodBody(read(BE), "private int insertIntoBuffer(");
        assertFalse("找不到 insertIntoBuffer", buffer.isEmpty());
        assertTrue("insertIntoBuffer 必须逐格算剩余空间（同一 bug 的第三条路径，前两轮漏了它）",
                buffer.contains("getSlotLimit(i)"));
        assertTrue("insertIntoBuffer 必须只搬得动的量", buffer.contains("Math.min(space,"));
        assertFalse("insertIntoBuffer 不得出现未夹紧的裸 grow(remainder.getCount())",
                buffer.contains("existing.grow(remainder.getCount());"));

        // ② insertOutputs（第二轮已修，作为对照保持在列）
        String matcher = read("src/main/java/cn/ism/mekck/kitchen/KitchenRecipeMatcher.java");
        String outputs = methodBody(matcher, "public static List<ItemStack> insertOutputs(");
        assertTrue("KitchenRecipeMatcher.insertOutputs 必须逐格算剩余空间",
                outputs.contains("getSlotLimit(slot)"));
        assertFalse("insertOutputs 不得出现裸 grow(stack.getCount())",
                outputs.contains("existing.grow(stack.getCount());"));

        // ③ insertIntoStorage（第二轮已修）
        String storage = methodBody(read(BE), "private int insertIntoStorage(");
        if (!storage.isEmpty()) {
            assertTrue("insertIntoStorage 必须逐格算剩余空间", storage.contains("space"));
        }

        // 反向断言：这两个文件里不得再有「未夹紧就整份并进去」的 grow 形态。
        // （注意数组里放的是**路径**不是内容 —— 早先误放内容，报出来的
        //  InvalidPathException 里的 "package cn.ism.mekck.kitchen;" 就是源码首行。）
        for (String path : new String[]{
                BE, "src/main/java/cn/ism/mekck/kitchen/KitchenRecipeMatcher.java"}) {
            String s = read(path);
            int idx = 0;
            while ((idx = s.indexOf("existing.grow(", idx)) >= 0) {
                String window = s.substring(idx, Math.min(s.length(), idx + 80));
                boolean clamped = window.contains("Math.min(space") || window.contains("moved)");
                assertTrue(path + " 里有一处未夹紧的 grow：" + window, clamped);
                idx += "existing.grow(".length();
            }
        }
    }

    private static String fieldDeclaration(String src, String name) {
        return cn.ism.mekck.TestSourceText.fieldDeclaration(src, name);
    }

    private static String methodBody(String src, String signature) {
        return cn.ism.mekck.TestSourceText.methodBody(src, signature);
    }
}
