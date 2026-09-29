package cn.ism.mekck.machine;

import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 工厂「一批产物能不能装下 / 怎么落槽」的两段算术 —— 家族无关，6 个家族共用。
 *
 * <h3>为什么从切菜执行器里搬出来</h3>
 * 阶段 3 Task 1（研磨工厂）是第二个需要它们的家族。两段逻辑在切菜与研磨之间
 * <b>逐字相同</b>：都只按「槽内物品引用 + 判定中的累计数量」推进，不拷任何
 * {@code ItemStack}；上限都问槽自己的 {@code getLimit(stack)}。留在切菜执行器里的话，
 * 研磨只能整个抄一遍，而两份一旦漂移，表现是
 * 「预演说装得下、落槽时只塞进去一部分，剩下凭空消失」——
 * <b>不报错、不留日志</b>，是本项目最难查的那一类故障。
 *
 * <h3>为什么是 static 且只依赖 {@code List<IInventorySlot>}</h3>
 * 与 {@code MekCkMachineTile#gatedEnergyCost} 同一个理由：真 tile 在裸 JVM 里造不出来
 * （要 {@code BlockEntityType} 与 Mek 的 {@code Attribute} 注册表），
 * 但这两段算术只用到 {@code ItemStack} 与 {@code IInventorySlot}，
 * 跑起 {@code Bootstrap.bootStrap()} 就能测。见
 * {@code TestCuttingBatchPacking}（切菜侧）与 {@code TestGrindingBatchPacking}（研磨侧）。
 */
public final class MekCkBatchPacking {

    private MekCkBatchPacking() {
    }

    /**
     * 单个槽能装多少 —— <b>问槽自己</b>，不由调用方写死（阶段 2 Task 4.9）。
     *
     * <p>判定用的上限与玩家真能存进去的上限来自<b>同一个</b> {@code getLimit}，
     * 这道统一是 {@link #canFitAll} 与 {@link #insertOutput} 能配对使用的前提。</p>
     */
    public static int slotCapacity(IInventorySlot slot, ItemStack stack) {
        return slot.getLimit(stack);
    }

    /**
     * 产出容量判定：<b>只跟踪每个产出槽的占用数量</b>，不做任何 {@code ItemStack} 拷贝。
     *
     * <p>本方法按槽调用，81 并行工厂原先每次都要把全部产出槽各 {@code copy()} 一份；
     * 现在只记录「槽内物品引用 + 判定过程中的累计数量」，语义与逐份拷贝完全等价。
     * 判定过程<b>不改动产出槽</b>——真正落槽在 {@link #insertOutput}。</p>
     *
     * @param outputs    本机的产出槽（{@code tile.getOutputSlots()}）
     * @param results    这一批要产出的物品（空/null 会被跳过）
     * @param multiplier 批大小
     * @return 全部装得下才为 {@code true}；任何一项 {@code result.getCount() × multiplier}
     *         超出 {@code int} 上界一律判 {@code false}——不在这里拦，
     *         后面 {@code setCount((int) 溢出值)} 会造出数量为负的 {@code ItemStack}，
     *         那是「凭空造物品」级别的故障
     */
    public static boolean canFitAll(List<IInventorySlot> outputs, List<ItemStack> results, int multiplier) {
        int outputSlots = outputs.size();
        ItemStack[] slotItem = new ItemStack[outputSlots];
        int[] slotCount = new int[outputSlots];
        for (int slot = 0; slot < outputSlots; slot++) {
            ItemStack existing = outputs.get(slot).getStack();
            slotItem[slot] = existing.isEmpty() ? null : existing;
            slotCount[slot] = existing.isEmpty() ? 0 : existing.getCount();
        }

        for (ItemStack result : results) {
            if (result == null || result.isEmpty()) {
                continue;
            }
            long totalCountLong = (long) result.getCount() * multiplier;
            if (totalCountLong > Integer.MAX_VALUE) {
                return false;
            }
            int remaining = (int) totalCountLong;
            for (int slot = 0; slot < outputSlots && remaining > 0; slot++) {
                ItemStack current = slotItem[slot];
                // 上限按需问，不提前算：被跳过的槽（装着别的物品）用不到它，
                // 而 81 并行时这一层循环每 tick 要跑上万次。
                if (current == null) {
                    int moved = Math.min(remaining, slotCapacity(outputs.get(slot), result));
                    slotItem[slot] = result; // 只记引用，不拷贝
                    slotCount[slot] = moved;
                    remaining -= moved;
                } else if (ItemStack.isSameItemSameTags(current, result)) {
                    int space = slotCapacity(outputs.get(slot), result) - slotCount[slot];
                    if (space > 0) {
                        int moved = Math.min(remaining, space);
                        slotCount[slot] += moved;
                        remaining -= moved;
                    }
                }
            }
            if (remaining > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把一批产物按「先并入已有的同类槽、再往后找空槽」的顺序塞进产出区。
     *
     * <p>上限同样取自槽自己的 {@code getLimit}，与 {@link #canFitAll} 同一口径——
     * 两处一旦漂移，表现就是「预演说装得下、落槽时却只塞进去一部分，剩下凭空消失」，
     * 而且不报错、不留日志。</p>
     *
     * <p>入参 {@code stack} <b>会被消耗</b>（{@code shrink}），剩余量留在参数里。
     * 装不下时它原样剩下——由调用方决定是丢弃还是记日志，不在本方法里吞。</p>
     */
    public static void insertOutput(List<IInventorySlot> outputs, ItemStack stack) {
        for (int slot = 0; slot < outputs.size() && !stack.isEmpty(); slot++) {
            IInventorySlot outputSlot = outputs.get(slot);
            ItemStack existing = outputSlot.getStack();
            int capacity = slotCapacity(outputSlot, stack);
            if (existing.isEmpty()) {
                int moved = Math.min(stack.getCount(), capacity);
                ItemStack inserted = stack.copy();
                inserted.setCount(moved);
                outputSlot.setStack(inserted);
                stack.shrink(moved);
            } else if (ItemStack.isSameItemSameTags(existing, stack)) {
                int space = capacity - existing.getCount();
                if (space > 0) {
                    int moved = Math.min(stack.getCount(), space);
                    existing.grow(moved);
                    outputSlot.setStack(existing);
                    stack.shrink(moved);
                }
            }
        }
    }
}
