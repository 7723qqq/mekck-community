package cn.ism.mekck.mixin;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.upgrade.IMekCkUnknownUpgradeHolder;
import cn.ism.mekck.upgrade.MekCkUpgradeCodec;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentUpgrade;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.Map;

/**
 * 把 {@link TileComponentUpgrade} 的升级持久化从 ordinal 索引换成名字键。
 *
 * <h3>为什么要换</h3>
 * Mek 的 {@code Upgrade.saveMap} 按 {@code ordinal()} 写，{@code buildMap} 按
 * {@code byIndexStatic} 读，而后者走 {@code MathUtils.getByIndexMod} <b>取模回绕</b>——
 * 越界不抛异常、不打日志。注入 {@code Upgrade} 的 mod 增删会让 ordinal 整体位移，
 * 玩家旧存档里的升级数量会静默变成另一种升级。详见
 * {@code src/test/java/cn/ism/mekck/upgrade/TestUpgradeIndexWraparoundArithmetic.java}。
 *
 * <h3>为什么用 @Redirect 而不是 @Inject</h3>
 * {@code Upgrade.saveMap} / {@code buildMap} 是 {@code public static}，
 * 在 {@code Upgrade} 上注入会波及整合包里<b>所有</b> Mekanism 机器（Mek 自己的也在内）。
 * <b>但 {@code @Redirect} 作用在<b>类</b>上，不是「作用在挂了本 Mixin 的 tile 上」</b>：
 * 它改写的是 {@code TileComponentUpgrade} 本身，因此整合包里<b>每一个</b>
 * {@code TileComponentUpgrade} 实例（含 Mek 自己机器的）都会走到本类的方法体。
 * 所以两个重定向都必须先判「这台机器是不是 MekCK 的」，不是则原样退回 Mek 的实现，
 * 判据是 {@link MekCkUpgradeTypes#isMekCkOwnedTile(Class)}（沿类链找
 * {@code cn.ism.mekck.machine.MekCkMachineTile}），<b>不能用 {@code mekck$tier() != null}</b>——
 * 它在「是 MekCK 机器但反射取档位失败」时同样返回 null，那种情况必须继续走名字键。
 * 不退回的后果见 {@code MekCkUpgradeTypes.isMekCkOwnedTile} 的注释：Mek 机器上的
 * MUFFLING / FILTER / GAS / ANCHOR / STONE_GENERATOR 会被 {@code capOf(…, null) == 0} 静默丢弃。
 *
 * <h3>只换持久化，运行时行为一概不动</h3>
 * 20 tick 安装读条、槽位合法性（{@code UpgradeInventorySlot.input(listener, supported)}）、
 * GUI 升级 tab 全部仍由 Mek 原生代码负责。
 */
@Mixin(value = TileComponentUpgrade.class, remap = false)
public abstract class MixinTileComponentUpgradePersistence implements IMekCkUnknownUpgradeHolder {

    /**
     * 目标类的私有字段，必须用 {@code @Shadow} 声明而不是直接写 {@code this.tile}。
     *
     * <p>Mixin 之所以能读到它，是因为本类的代码最终被<b>合并进</b>
     * {@code TileComponentUpgrade}；但 javac 编译本类时看不到目标类的私有成员，
     * 不声明 {@code @Shadow} 字段就直接引用 {@code this.tile} 会编译不过。
     */
    @Shadow
    @Final
    private TileEntityMekanism tile;

    @Unique
    private List<CompoundTag> mekck$unknownRaw = List.of();

    /**
     * 本组件是否属于 MekCK 自己的机器（决定升级持久化走名字键还是原样交回 Mek）。
     *
     * <p>与 {@link #mekck$tier()} 是<b>两件不同的事</b>：这里的判据是「类链上有没有
     * {@code cn.ism.mekck.machine.MekCkMachineTile}」，与能否取到档位无关。
     * 详见 {@link MekCkUpgradeTypes#isMekCkOwnedTile(Class)}。
     */
    @Unique
    private boolean mekck$isMekCkTile() {
        return MekCkUpgradeTypes.isMekCkOwnedTile(this.tile == null ? null : this.tile.getClass());
    }

    /**
     * 本组件所属的机器档位，取自 tile。
     *
     * <p>只用于 {@link MekCkUpgradeTypes#decode(CompoundTag, CuttingMachineFactoryTier)}
     * 的按档位裁剪，且<b>只在 {@link #mekck$isMekCkTile()} 为真时才会被调用</b>。
     *
     * <p><b>{@code null} 的语义是「是 MekCK 的机器，但档位取不到」</b>（{@code tile == null}，
     * 或反射调 {@code getTier()} 抛 {@code ReflectiveOperationException}），此时 decode 退化为
     * 「只按枚举自带 maxStack 裁剪」。<b>它不再表示「不是 MekCK 机器」</b>——
     * 那个判断已经上移到 {@link #mekck$isMekCkTile()}，两者不可互换，理由见该类注释与
     * {@link MekCkUpgradeTypes#isMekCkOwnedTile(Class)}。
     *
     * <p><b>⚠️ 这里是走反射而非直接类型判断的桥接，且按契约保留。</b>
     * 判据是「类链上存在 {@code cn.ism.mekck.machine.MekCkMachineTile}」+ 反射调 {@code getTier()}：
     * <ul>
     *   <li>{@code MekCkMachineTile} 的类名与 {@code getTier()} 的返回类型是
     *       {@code cn.ism.mekck.CuttingMachineFactoryTier}——这个三元契约写在该类的类注释里，
     *       <b>一个字符都不许改</b>（改了就是读档时静默丢掉存储卡）。
     *       类名字符串另由 {@code TestUpgradePersistenceOwnership} 钉住。</li>
     *   <li>MekCK 自有 tile 走到这里返回真实档位；反射失败返回 {@code null}（见上）。</li>
     *   <li>本可以改成 {@code owner instanceof MekCkMachineTile machine ? machine.getTier() : null}，
     *       但阶段 3 未做这次替换：现有反射路径已实测可用，而替换会在读档路径上引入
     *       一个新的类加载点。要改时请连带把 {@code mekck$isMekCkTile()} 一并简化。</li>
     * </ul>
     * 沿 {@code getSuperclass()} 往上找而不是只比 {@code ==}：
     * 阶段 2 的机器 tile 几乎肯定会再派生出各机器自己的子类（如
     * {@code MekCkCuttingFactoryTile}），只比直接父类会全部落空、静默退化成 {@code null}。
     */
    @Unique
    private CuttingMachineFactoryTier mekck$tier() {
        Object owner = this.tile;
        if (owner == null) {
            return null;
        }
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("cn.ism.mekck.machine.MekCkMachineTile")) {
                try {
                    return (CuttingMachineFactoryTier) c.getMethod("getTier").invoke(owner);
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public List<CompoundTag> mekck$unknownRaw() {
        return mekck$unknownRaw;
    }

    @Override
    public void mekck$setUnknownRaw(List<CompoundTag> entries) {
        this.mekck$unknownRaw = entries == null ? List.of() : entries;
    }

    /**
     * ⚠️ 目标方法是<b>合成 lambda</b>，不是 {@code read} 本身。
     *
     * <p>实测（{@code javap -p -c -s mekanism.common.tile.component.TileComponentUpgrade}，
     * jar {@code mekanism-268560-6018299_mapped_official_1.20.1.jar}）：
     * {@code read} 只用 {@code invokedynamic} 造一个 {@code Consumer} 交给
     * {@code NBTUtils.setCompoundIfPresent}，真正调用 {@code Upgrade.buildMap} 的是
     * 合成方法 {@code lambda$read$1(CompoundTag)}。把 {@code method} 写成
     * {@code "read(Lnet/minecraft/nbt/CompoundTag;)V"} 匹配不到任何目标，
     * 配合 {@code injectors.defaultRequire: 1} 会<b>启动即崩</b>。
     * <pre>
     *   public void read(net.minecraft.nbt.CompoundTag);
     *       descriptor: (Lnet/minecraft/nbt/CompoundTag;)V
     *       Code:
     *          0: aload_1
     *          1: ldc_w         #269  // String componentUpgrade
     *          5: invokedynamic #287,  0  // InvokeDynamic #0:accept:(...)Ljava/util/function/Consumer;
     *         10: invokestatic  #293  // Method mekanism/common/util/NBTUtils.setCompoundIfPresent:(...)V
     *
     *   private void lambda$read$1(net.minecraft.nbt.CompoundTag);
     *       descriptor: (Lnet/minecraft/nbt/CompoundTag;)V
     *       Code:
     *          0: aload_0
     *          1: getfield      #49   // Field upgrades:Ljava/util/Map;
     *          4: invokeinterface #394,  1  // InterfaceMethod java/util/Map.clear:()V
     *         14: invokestatic  #398  // Method mekanism/api/Upgrade.buildMap:(Lnet/minecraft/nbt/CompoundTag;)Ljava/util/Map;  ← 本处重定向
     *         17: invokeinterface #402,  2  // InterfaceMethod java/util/Map.putAll:(Ljava/util/Map;)V
     *         23: invokevirtual #404     // Method getSupportedTypes:()Ljava/util/Set;
     *         56: invokevirtual #187     // Method .../TileEntityMekanism.recalculateUpgrades:(...)V
     *         66: bipush        10
     *         74: invokestatic  #428  // Method .../NBTUtils.setListIfPresent:(...)V
     * </pre>
     * <b>只有 buildMap 一处被换掉</b>：{@code upgrades.clear()}、{@code putAll}、
     * 遍历 {@code getSupportedTypes()} 调 {@code recalculateUpgrades}、读 {@code "Items"} 槽位
     * 全部仍走 Mek 原生。
     */
    // remap = false 是**必需**的：目标 lambda$read$1 是 lambda 编译产物（合成方法），
    // 混淆映射表里不会有它。`Redirect.remap` 同样默认 true、不继承类级的
    // `@Mixin(remap = false)`，缺这一行时处理器报
    // `Cannot find target method "lambda$read$1(...)"` 直接编译失败。
    // 该 lambda 在生产环境的 Mek jar 里确实存在（名字由 Mek 自己编译时定，
    // 合成方法不被 SRG 重命名），所以运行期语义不变。
    @Redirect(
            remap = false,
            method = "lambda$read$1(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/Upgrade;buildMap(Lnet/minecraft/nbt/CompoundTag;)"
                            + "Ljava/util/Map;"))
    private Map<Upgrade, Integer> mekck$decode(CompoundTag tag) {
        // 非 MekCK 机器：原样交回 Mek 的 ordinal 编解码。
        // 少了这一行，Mek 自己机器上的 MUFFLING/FILTER/GAS/ANCHOR/STONE_GENERATOR
        // 会被 capOf(…, null) == 0 判成「裁到 0」并由 decode 丢弃，回写后存档永久丢失。
        if (!mekck$isMekCkTile()) {
            return Upgrade.buildMap(tag);
        }
        MekCkUpgradeCodec.Decoded<Upgrade> decoded = MekCkUpgradeTypes.decode(tag, mekck$tier());
        this.mekck$unknownRaw = decoded.unknownRaw();
        return decoded.known();
    }

    /**
     * 与 {@link #mekck$decode} 对称：把 Mek 的 {@code Upgrade.saveMap} 换成名字键编码。
     *
     * <p>目标方法确实是 {@code write} 本身（不是合成 lambda），{@code saveMap} 直接躺在它体内：
     * <pre>
     *   public void write(net.minecraft.nbt.CompoundTag);
     *       descriptor: (Lnet/minecraft/nbt/CompoundTag;)V
     *       Code:
     *          0: new           #297  // class net/minecraft/nbt/CompoundTag
     *          9: getfield      #49   // Field upgrades:Ljava/util/Map;
     *         13: invokestatic  #302  // Method mekanism/api/Upgrade.saveMap:(Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V  ← 本处重定向
     *         27: invokevirtual #315  // Method net/minecraft/nbt/CompoundTag.put:(...)Lnet/minecraft/nbt/Tag;
     * </pre>
     *
     * <p>本方法返回 {@code void} 而非写进传入的 {@code tag}：Mek 原实现返回 void、
     * 自己往 {@code tag} 里塞 {@code "upgrades"} 子标签，而
     * {@code MekCkUpgradeTypes.encode} 返回的是一个<b>全新</b>的 {@code CompoundTag}，
     * 所以要把它合并进传入的那个（它是 {@code write} 偏移 0 处 {@code new CompoundTag}
     * 出来、偏移 7 {@code astore_2} 存下的那个空标签）。
     *
     * <p><b>方向不能反：{@code a.merge(b)} 是把 b 的键拷进 a。</b>
     * 实测 1.20.1 的 {@code net.minecraft.nbt.CompoundTag}：
     * <pre>
     *   public CompoundTag merge(CompoundTag p_128392_) {
     *       for (String s : p_128392_.tags.keySet()) { ... this.put(s, tag.copy()); ... }
     * </pre>
     * 所以必须写 {@code tag.merge(encoded)} 而不是 {@code encoded.merge(tag)}——
     * 后者只会把 Mek 那个空标签合进我们的新标签，传进来的 {@code tag} 仍旧是空的，
     * 于是 {@code write} 后续的 {@code put("Items", ...)} 落到无人引用的标签上，存档丢字段。
     *
     * <p>另注：1.20.1 的 {@code CompoundTag} <b>没有</b> {@code copyTo}（该方法是 1.20.2+ 才有的），
     * 用它会直接编译不过。
     */
    @Redirect(
            method = "write(Lnet/minecraft/nbt/CompoundTag;)V",
            at = @At(value = "INVOKE",
                    target = "Lmekanism/api/Upgrade;saveMap(Ljava/util/Map;Lnet/minecraft/nbt/CompoundTag;)V"))
    private void mekck$encode(Map<Upgrade, Integer> map, CompoundTag tag) {
        // 与 mekck$decode 对称：非 MekCK 机器交回 Mek 自己的 ordinal 编解码。
        // 少了这一行，Mek 机器会被改写成名字键格式（Mek 自己读得回来，但那是「本模组
        // 悄悄改写了别人的存档格式」，一旦玩家卸掉 mekck 就整批不可读）。
        if (!mekck$isMekCkTile()) {
            Upgrade.saveMap(map, tag);
            return;
        }
        tag.merge(MekCkUpgradeTypes.encode(map, this.mekck$unknownRaw));
    }
}
