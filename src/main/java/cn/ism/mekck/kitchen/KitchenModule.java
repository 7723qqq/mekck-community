package cn.ism.mekck.kitchen;

import cn.ism.mekck.CuttingMachineFactoryTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 中央厨房的「机器模块」识别与能力换算。
 *
 * <p>放入模块槽的机器方块物品会被识别为某个 {@link KitchenFamily}，并换算为：
 * 线程数 x（工厂 = 该等级进程数，基础机器 = 1）与每线程并行 y（堆叠倍率）。
 * 线程**专用**于该系列的配方类型，不跨类型借用。</p>
 */
public final class KitchenModule {

    private KitchenModule() {
    }

    /**
     * 模块能力。
     *
     * @param family      所属系列
     * @param threads     线程数 x
     * @param parallel    每线程并行 y
     * @param tierOrdinal 工厂等级序号（基础机器为 -1）
     * @param displayName 模块显示名（用于界面）
     */
    public record Ability(KitchenFamily family, int threads, int parallel, int tierOrdinal, String displayName) {
        /** 总吞吐量 = x × y。 */
        public int totalThroughput() {
            return Math.max(1, threads) * Math.max(1, parallel);
        }

        /** 是否来自工厂（多线程）。 */
        public boolean fromFactory() {
            return tierOrdinal >= 0;
        }
    }

    /**
     * 识别一个模块物品的能力；不是可安装的机器模块时返回 null。
     */
    public static Ability identify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null || !"mekck".equals(id.getNamespace())) return null;
        String path = id.getPath();

        // ① 基础机器：与系列图标一一对应
        KitchenFamily baseFamily = KitchenFamily.byIconItem(id.toString());
        if (baseFamily != null) {
            return new Ability(baseFamily, 1, readParallelFromNbt(stack), -1,
                    net.minecraft.network.chat.Component.translatable(
                            stack.getDescriptionId()).getString());
        }

        // ② 工厂：`{等级}_{系列后缀}_factory`
        for (KitchenFamily family : KitchenFamily.values()) {
            if (family.factorySuffix == null) continue;
            String suffix = "_" + family.factorySuffix + "_factory";
            if (!path.endsWith(suffix)) continue;
            String tierName = path.substring(0, path.length() - suffix.length());
            CuttingMachineFactoryTier tier = tierByName(tierName);
            if (tier == null) continue;
            return new Ability(family, Math.max(1, tier.processes), readParallelFromNbt(stack),
                    tier.ordinal(), net.minecraft.network.chat.Component.translatable(
                            stack.getDescriptionId()).getString());
        }
        return null;
    }

    /** 按枚举名（小写）匹配工厂等级。 */
    private static CuttingMachineFactoryTier tierByName(String name) {
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            if (tier.name().equalsIgnoreCase(name)) return tier;
        }
        return null;
    }

    /**
     * 从模块物品 NBT 读取每线程并行（堆叠升级倍率 ×2^n）。
     * 阶段 2 先返回 1；后续阶段接入工厂物品的升级 NBT（StackUpgradeTracker）。
     */
    private static int readParallelFromNbt(ItemStack stack) {
        net.minecraft.nbt.CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag == null) return 1;
        // 工厂物品的堆叠升级读条数据（MekCkUpgradeTracker 序列化格式）
        if (beTag.contains("StackUpgradeTracker", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            int installed = beTag.getCompound("StackUpgradeTracker").getInt("Installed");
            if (installed > 0) {
                return (int) Math.pow(2, Math.min(installed, 20)); // 每级 ×2
            }
        }
        if (beTag.contains("StackUpgrade")) {
            int installed = beTag.getCompound("StackUpgrade").getInt("Installed");
            if (installed > 0) {
                return (int) Math.pow(2, Math.min(installed, 20));
            }
        }
        return 1;
    }
}
