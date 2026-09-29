package cn.ism.mekck.mixin;

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
 */
public interface IMekCkUnknownUpgradeHolder {

    /** 无法解析的原始条目，可能为空列表，不可为 null。 */
    List<CompoundTag> mekck$unknownRaw();

    void mekck$setUnknownRaw(List<CompoundTag> entries);
}
