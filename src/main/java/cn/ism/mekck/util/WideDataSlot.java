package cn.ism.mekck.util;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.ContainerData;

/**
 * 把一个 32 位 {@code int} 拆成<b>两个</b> {@link ContainerData} 槽（低 16 位 / 高 16 位）传输。
 *
 * <h3>为什么必须拆两个槽</h3>
 * 原版 {@code ContainerData} 的每一个值都经
 * {@code net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket} 下发，
 * 而那个包对 value 只调 {@link FriendlyByteBuf#writeShort(int)} —— 实测 1.20.1 Forge
 * 反编译源码（{@code forge-1.20.1-47.2.0_mapped_official_1.20.1-sources.jar}）为：
 *
 * <pre>
 *    public ClientboundContainerSetDataPacket(FriendlyByteBuf p_178825_) {
 *       this.containerId = p_178825_.readUnsignedByte();
 *       this.id = p_178825_.readShort();
 *       this.value = p_178825_.readShort();   // ← 有符号短整型
 *    }
 *    public void write(FriendlyByteBuf p_131974_) {
 *       p_131974_.writeByte(this.containerId);
 *       p_131974_.writeShort(this.id);
 *       p_131974_.writeShort(this.value);     // ← 只写低 16 位
 *    }
 * </pre>
 *
 * <p><b>后果</b>：任何 {@code |value| > 32767} 的槽到客户端都会被截断并<b>符号扩展</b>。
 * 本模组 13 台遗留机器把 {@code energy.getEnergyStored()}（容量 10 万 ~ 4 亿）裸放进
 * {@code ContainerData}，于是 100_000 到客户端变成 {@code (short)0x86A0 = -31072} ——
 * 能源条的 {@code getLevel()} 算出负数、条纹显示为空，tooltip 也显示负的 FE。
 * 这是三轮代码审查都挂账的 I1 / I-N5。</p>
 *
 * <h3>为什么不用「缩放」代替</h3>
 * 审查曾判断「缩放修不了」（全局除数要装下 4 亿就得 ≥12211，那样 4000 FE 的小机器显示 0），
 * 后又判断「框定过宽，同步缩放值即可」。两者都<b>留了尾巴</b>：缩放是有损的 ——
 * 容量 4 亿的陈酿机按比例同步，往返误差可达 ±6104 FE，tooltip 会显示
 * 「399,993,896 / 400,000,000 FE」这种凭空冒出来的数。<b>拆两个槽是无损的</b>，
 * 16 位 × 2 = 32 位，恰好装下一个 int，且这本就是本仓既有的写法 ——
 * {@code SandwichAssemblerMenu} 的「目标数量低 16 位 / 高 16 位」两槽就是同一个思路。
 *
 * <h3>索引为什么放在末尾</h3>
 * 高位槽一律取「旧 {@code DATA_SIZE}」这个值，即<b>追加</b>到槽表末尾，
 * 现有所有 {@code DATA_*} 常量与 {@code data.get(1)} 这类字面量下标一律不动 ——
 * 追加不会移位，移位则会静默读到别的量（比现在更糟）。
 * 客户端那侧的 {@code new SimpleContainerData(XxxBlockEntity.DATA_SIZE)} 是符号引用，
 * {@code DATA_SIZE} 自增会同步放大数组，不会越界。
 *
 * <h3>约束</h3>
 * 低位槽与高位槽<b>不必相邻</b>，因此调用方要显式传两个下标 —— 这是刻意的：
 * 追加式布局下二者必然不相邻，强行要求相邻会逼人去移动下标。
 */
public final class WideDataSlot {

    private WideDataSlot() {
    }

    /** 服务端写入低位槽的值：取低 16 位并<b>转成无符号</b>（0~65535）。 */
    public static int low(int value) {
        return value & 0xFFFF;
    }

    /** 服务端写入高位槽的值：取高 16 位并转成无符号（0~65535）。 */
    public static int high(int value) {
        return (value >>> 16) & 0xFFFF;
    }

    /**
     * 客户端把两个槽合并回 32 位。
     *
     * <p>入参可能是<b>被符号扩展过的</b>（{@code readShort()} 返回 {@code short}，
     * 34464 会变成 -31072），所以两半都要先 {@code & 0xFFFF} 去符号位，
     * 再用 {@code << 16} 拼回 —— 高位左移 16 位会自然恢复正确的符号。</p>
     */
    public static int combine(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }

    /**
     * 从 {@link ContainerData} 一次读出完整值。
     *
     * @param data     客户端是 {@code SimpleContainerData}，服务端是 BE 自己的实现
     * @param lowIndex 低位槽下标（{@code DATA_ENERGY}）
     * @param highIndex 高位槽下标（{@code DATA_ENERGY_HI}）
     */
    public static int read(ContainerData data, int lowIndex, int highIndex) {
        return combine(data.get(lowIndex), data.get(highIndex));
    }

    /**
     * 服务端写槽的两个值 —— 返回给 {@code ContainerData.get} 的 {@code switch} 用。
     *
     * @return {@code {low(v), high(v)}}
     */
    public static int[] split(int value) {
        return new int[]{low(value), high(value)};
    }
}
