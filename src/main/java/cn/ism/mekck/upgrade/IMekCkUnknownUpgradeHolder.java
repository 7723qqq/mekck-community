package cn.ism.mekck.upgrade;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 给 {@link mekanism.common.tile.component.TileComponentUpgrade} 附加的「无法解析的升级条目」
 * 存放点。
 *
 * <p>为什么需要它：{@code MekCkUpgradeCodec.decode} 遇到存档里当前不存在的升级名时
 * （比如玩家卸载了注入该升级的 mod），会把原始条目原样返回而不是丢弃。
 * 但 {@code TileComponentUpgrade} 内部只有 {@code Map<Upgrade,Integer>} 一个字段，
 * 装不下这些条目——不存下来就会在下次存档时被抹掉，装回那个 mod 也恢复不了。
 *
 * <h3>⚠️ 为什么本接口**必须**住在 {@code cn.ism.mekck.upgrade}，不能放 {@code cn.ism.mekck.mixin}</h3>
 * 它原先在 {@code cn.ism.mekck.mixin} 下，而 {@code mekck.mixins.json} 用
 * {@code "package": "cn.ism.mekck.mixin"} 把**整个包**声明成了 mixin 包。
 * Mixin 有一条硬规则：<b>被声明为 mixin 包里的类，业务代码不能直接引用</b>——
 * 一旦引用，JVM 在加载引用方的类定义时就会抛：
 * <pre>
 *   org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError:
 *   cn.ism.mekck.mixin.IMekCkUnknownUpgradeHolder is in a defined mixin package
 *   cn.ism.mekck.mixin.* owned by mekck.mixins.json and cannot be referenced directly
 * </pre>
 * 实机症状（2026-09-30 实例 crash-report）：这个接口被
 * {@code MixinTileComponentUpgradePersistence} implements，于是进了
 * {@code TileComponentUpgrade} 的类定义；而 Mek 的 {@code TileEntityMekanism}
 * 持有 {@code TileComponentUpgrade}，加载它时连锁触发 ⇒
 * <b>只要放下任意一台工厂方块就崩服</b>（栈：{@code CuttingFactoryTile.<init>}
 * → {@code MekCkMachineTile.<init>} → {@code TileEntityMekanism.<init>}）。
 *
 * <p>注意 {@code MekCkMixinConfigPlugin} 留在 mixin 包下是**合法**的：它是被
 * {@code mixins.json} 的 {@code "plugin"} 字段引用的配置插件，属于 Mixin 自身的入口，
 * 不受「不可直接引用」约束。
 *
 * <p>方法名上的 {@code mekck$} 前缀保留：它是 Mixin {@code @Unique} 命名约定，
 * 提示「这个名字只属于本模组的注入，不会与目标类撞名」。
 */
public interface IMekCkUnknownUpgradeHolder {

    /** 无法解析的原始条目，可能为空列表，不可为 null。 */
    List<CompoundTag> mekck$unknownRaw();

    void mekck$setUnknownRaw(List<CompoundTag> entries);
}
