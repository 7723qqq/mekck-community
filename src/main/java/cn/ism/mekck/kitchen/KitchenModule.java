package cn.ism.mekck.kitchen;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.upgrade.MekCkUpgradeRefs;
import cn.ism.mekck.upgrade.MekCkUpgradeTypes;
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
            return new Ability(baseFamily, 1, readParallelFromNbt(stack, null), -1,
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
            return new Ability(family, Math.max(1, tier.processes), readParallelFromNbt(stack, tier),
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
     *
     * <h3>读的是 Mek 的升级持久化，不是旧追踪器</h3>
     * 迁移后的工厂把存储卡交给 Mek 的 {@code TileComponentUpgrade} 持有，物品 NBT 里
     * 的形状是 {@code BlockEntityTag.componentUpgrade.upgrades} —— 一个
     * {@code [{type: "<rawName>", amount: n}, ...]} 列表（见
     * {@code MekCkUpgradeCodec.encode}）。所以这里按 {@code type == "storage"} 找条目、
     * 取 {@code amount}。
     *
     * <p><b>旧实现读的两个键都是死的</b>：{@code StackUpgradeTracker} 全仓只有已退役的
     * {@code IceFactoryBlockEntity} 写过，而 {@code MekCkLegacyMachineNbt} 会在迁移时把它剥掉；
     * {@code "StackUpgrade"} 更是<b>没有任何写入方</b>。两个分支因此恒不命中、恒返回 1，
     * 而 javadoc 却写着「阶段 2 先返回 1」——注释与代码互相矛盾。现已按真实键重写。</p>
     *
     * <p>上限取 {@link MekCkUpgradeTypes#capOf} 而不是写死 20：它是「本档能装几张存储卡」的
     * 唯一权威定义，同时把 {@code SINGULARITY}（无堆叠升级槽）判成 0 ⇒ 倍率 1。
     * 写死 20 会让手改存档的机器在中央厨房里虚报吞吐。</p>
     *
     * @param tier 工厂等级；{@code null}（基础机器）没有存储卡槽，直接返回 1
     */
    private static int readParallelFromNbt(ItemStack stack, CuttingMachineFactoryTier tier) {
        if (tier == null) return 1;
        int installed = readStorageUpgradeCount(stack);
        if (installed <= 0) return 1;
        int cap = MekCkUpgradeTypes.capOf(MekCkUpgradeRefs.storage(), tier);
        if (cap <= 0) return 1;
        // 移位按 mod 32 处理：1<<31 是负数、1<<32 绕回 1。cap 当前是 6，走不到这里，
        // 但这段算术不该依赖「配置值恰好很小」这个巧合。
        return 1 << Math.min(installed, Math.min(cap, 30));
    }

    /** 从 {@code BlockEntityTag.componentUpgrade.upgrades} 里取存储卡的 {@code amount}。 */
    private static int readStorageUpgradeCount(ItemStack stack) {
        net.minecraft.nbt.CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag == null) return 0;
        net.minecraft.nbt.CompoundTag component = beTag.getCompound("componentUpgrade");
        net.minecraft.nbt.ListTag list = component.getList(
                cn.ism.mekck.upgrade.MekCkUpgradeCodec.KEY, net.minecraft.nbt.Tag.TAG_COMPOUND);
        String storageName = MekCkUpgradeTypes.nameOf(MekCkUpgradeRefs.storage());
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.nbt.CompoundTag one = list.getCompound(i);
            if (storageName.equals(one.getString("type"))) {
                return one.getInt("amount");
            }
        }
        return 0;
    }
}
