package cn.ism.mekck.mixin;

import cn.ism.mekck.util.BigStackItemHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 {@link BigStackItemHandler#BIG_COUNT_KEY} 的数量旁路在**所有** NBT 往返中生效，而不只是
 * 该 handler 自己序列化的那一份。
 *
 * <p><b>为什么需要</b>：原版 1.20.1 的 {@code ItemStack.save} 把 Count 写成 <b>byte</b>
 * （{@code putByte}），读取也是 {@code getByte}——而 {@code getByte} 返回的是
 * <b>有符号</b> byte。本模组机器槽位上限是 {@code Integer.MAX_VALUE-1}，
 * 因此任何超过 127 的堆叠经 NBT 往返后必然改变：
 * <ul>
 *   <li>5000 → 低 8 位 {@code 0x88} → 有符号 <b>-120</b>（负数量被当作无效）</li>
 *   <li>256 / 4096 → 低 8 位为 0 → <b>0</b>（整堆消失）</li>
 *   <li>其余 &gt; 127 的值一律被改写成更小的数（128–255 变负，257 起变 1、2…）</li>
 * </ul>
 * {@code BigStackItemHandler} 早已用 int 型 {@code McCount} 旁路解决自身序列化，
 * 但 {@code BigStackDrops} 掉落的是普通 {@code ItemEntity}——它走 vanilla 的
 * {@code ItemEntity.save} → {@code ItemStack.save}，旁路完全不生效，
 * 区块卸载后大堆叠掉落物即损坏。这是本 mod 存在意义（大堆叠）的直接反噬。
 *
 * <p><b>为什么用 Mixin 而不是改掉落逻辑</b>：把大堆叠拆成 ≤127 的实体需要上亿个实体
 * （正是 {@code BigStackDrops} 类注释里要避免的 3355 万），不可行。
 * 而把旁路下沉到 {@code ItemStack} 本身，顺带修好所有其它 NBT 路径
 * （漏斗、管道、其它模组读写该堆叠），与该类的既有文档一致。
 *
 * <p><b>兼容性</b>：写出时仍保留原版 byte 字段（外部读取者仍看到一个被截断的值，
 * 与改动前一致），仅在 count &gt; 127 时<b>额外</b>写 {@code McCount}；
 * 读入时只有存在 {@code McCount} 才覆盖，没有该键的旧存档与原版物品行为完全不变。
 *
 * <p>目标类属 vanilla，故用默认 {@code remap = true}（走 refmap 映射到 SRG 名）；
 * 本包内针对第三方模组的 mixin 才用 {@code remap = false}。
 */
@Mixin(ItemStack.class)
public abstract class MixinItemStack {

    /**
     * 写出：count 超过 byte 上限时补写权威 int 数量。
     *
     * <p>用 RETURN 而非 HEAD：原版已把 byte 字段写好并处理了 count==0 的省略，
     * 在其后追加不会破坏既有字段。</p>
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void mekck$writeBigCount(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        int count = ((ItemStack) (Object) this).getCount();
        if (count > 127) {
            tag.putInt(BigStackItemHandler.BIG_COUNT_KEY, count);
        }
    }

    /**
     * 读入：存在 {@code McCount} 时用它覆盖原版 byte 解析出的（可能被截断或为负的）数量。
     *
     * <p>不动原版逻辑，仅在返回前按需覆盖；{@code EMPTY} 与非正数量一律不碰，
     * 避免把空物品或无效数量写进物品堆。</p>
     */
    @Inject(method = "of", at = @At("RETURN"))
    private static void mekck$readBigCount(CompoundTag tag, CallbackInfoReturnable<ItemStack> cir) {
        if (!tag.contains(BigStackItemHandler.BIG_COUNT_KEY, Tag.TAG_INT)) {
            return;
        }
        ItemStack stack = cir.getReturnValue();
        int count = tag.getInt(BigStackItemHandler.BIG_COUNT_KEY);
        if (!stack.isEmpty() && count > 0) {
            stack.setCount(count);
        }
    }
}
