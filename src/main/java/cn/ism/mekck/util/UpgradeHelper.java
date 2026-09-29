package cn.ism.mekck.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 升级模块相关工具。
 * <p>
 * 供「潜行右键机器直接安装升级模块」使用（对应放置的升级模块会按数量消耗并装入机器对应升级槽，
 * 前提是机器该槽未装满，且槽内为空或为同种升级）。
 */
public final class UpgradeHelper {

    public enum UpgradeType { SPEED, ENERGY, STACK, CREATIVE, GAS, NONE }

    // ── 升级物品 id（预先解析一次）────────────────────────────────
    // getType() 会被 isItemValid / tickUpgradeTrackers / isAnyUpgrade 逐槽位逐 tick 调用，
    // 原先每次都做 5~6 次 ResourceLocation.tryParse（每次都重新拆分并校验字符串）。
    private static final ResourceLocation ID_SPEED = ResourceLocation.tryParse("mekanism:upgrade_speed");
    private static final ResourceLocation ID_ENERGY = ResourceLocation.tryParse("mekanism:upgrade_energy");
    private static final ResourceLocation ID_STACK = ResourceLocation.tryParse("mekanism_extras:upgrade_stack");
    private static final ResourceLocation ID_CREATIVE = ResourceLocation.tryParse("mekanism_extras:upgrade_creative");
    private static final ResourceLocation ID_GAS = ResourceLocation.tryParse("mekanism:upgrade_gas");
    /** 旧版气体升级 id，保留兼容。 */
    private static final ResourceLocation ID_GAS_LEGACY = ResourceLocation.tryParse("mekanism:gasupgrade");

    // ── 升级倍率曲线 ────────────────────────────────────────────
    /**
     * Mekanism 升级曲线的分组大小：每装 {@value} 个速度升级 → 速度 ×10；
     * 每装 {@value} 个能量升级 → 能耗 ×0.1、能量容量 ×10。
     *
     * <p>这个 8 是曲线定义的一部分，<b>不是</b>可以随手改的魔数——它同时出现在全仓库
     * 十几台机器的倍率公式里。原先它以字面量 {@code 8.0} 直接写死在每处 {@code Math.pow}
     * 的除数位置，改平衡需要同步改十几处，且极易漏改。</p>
     */
    public static final int UPGRADE_CURVE_DIVISOR = 8;

    /** 速度倍率 = 10^(已装速度升级数 / 8)。 */
    public static double speedMultiplier(int speedUpgrades) {
        return Math.pow(10, speedUpgrades / (double) UPGRADE_CURVE_DIVISOR);
    }

    /** 能耗倍率 = 0.1^(已装能量升级数 / 8)。 */
    public static double energyConsumptionMultiplier(int energyUpgrades) {
        return Math.pow(0.1, energyUpgrades / (double) UPGRADE_CURVE_DIVISOR);
    }

    /** 能量容量倍率 = 10^(已装能量升级数 / 8)。 */
    public static double energyCapacityMultiplier(int energyUpgrades) {
        return Math.pow(10, energyUpgrades / (double) UPGRADE_CURVE_DIVISOR);
    }

    private UpgradeHelper() {
    }

    /** 判断手持物品是否为升级模块，并返回其类型。 */
    public static UpgradeType getType(ItemStack stack) {
        if (stack.isEmpty()) {
            return UpgradeType.NONE;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return UpgradeType.NONE;
        }
        if (id.equals(ID_SPEED)) {
            return UpgradeType.SPEED;
        }
        if (id.equals(ID_ENERGY)) {
            return UpgradeType.ENERGY;
        }
        if (id.equals(ID_STACK)) {
            return UpgradeType.STACK;
        }
        if (id.equals(ID_CREATIVE)) {
            return UpgradeType.CREATIVE;
        }
        // 气体升级（Mekanism 的 upgrade_gas；兼容旧版 id gasupgrade）
        if (id.equals(ID_GAS) || id.equals(ID_GAS_LEGACY)) {
            return UpgradeType.GAS;
        }
        return UpgradeType.NONE;
    }

    public static boolean isUpgrade(ItemStack stack) {
        return getType(stack) != UpgradeType.NONE;
    }

    public static boolean isSpeedUpgrade(ItemStack stack) {
        return getType(stack) == UpgradeType.SPEED;
    }

    public static boolean isEnergyUpgrade(ItemStack stack) {
        return getType(stack) == UpgradeType.ENERGY;
    }

    public static boolean isStackUpgrade(ItemStack stack) {
        return getType(stack) == UpgradeType.STACK;
    }

    public static boolean isCreativeUpgrade(ItemStack stack) {
        return getType(stack) == UpgradeType.CREATIVE;
    }

    public static boolean isGasUpgrade(ItemStack stack) {
        return getType(stack) == UpgradeType.GAS;
    }

    public static int install(ItemStackHandler items,
                              int speedSlot, int speedMax,
                              int energySlot, int energyMax,
                              int stackSlot, int stackMax,
                              ItemStack held) {
        return install(items, speedSlot, speedMax, energySlot, energyMax,
                stackSlot, stackMax, -1, held);
    }

    /**
     * 重载版本，额外支持创造升级槽（max=1）。
     *
     * @param creativeSlot 创造升级槽位（-1 = 不支持）
     * @param held         手持的升级模块
     * @return 实际装入/消耗的数量（0 表示未装入）
     */
    public static int install(ItemStackHandler items,
                              int speedSlot, int speedMax,
                              int energySlot, int energyMax,
                              int stackSlot, int stackMax,
                              int creativeSlot,
                              ItemStack held) {
        return install(items, speedSlot, speedMax, energySlot, energyMax,
                stackSlot, stackMax, creativeSlot, -1, held);
    }

    /**
     * 完整版本，额外支持气体升级槽（max=1）。
     *
     * @param creativeSlot 创造升级槽位（-1 = 不支持）
     * @param gasSlot      气体升级槽位（-1 = 不支持）
     * @param held         手持的升级模块
     * @return 实际装入/消耗的数量（0 表示未装入）
     */
    public static int install(ItemStackHandler items,
                              int speedSlot, int speedMax,
                              int energySlot, int energyMax,
                              int stackSlot, int stackMax,
                              int creativeSlot,
                              int gasSlot,
                              ItemStack held) {
        // 先检查创造升级（max=1，独立槽位）
        if (creativeSlot >= 0 && creativeSlot < items.getSlots()) {
            UpgradeType type = getType(held);
            if (type == UpgradeType.CREATIVE) {
                ItemStack current = items.getStackInSlot(creativeSlot);
                if (current.isEmpty()) {
                    ItemStack newStack = held.copy();
                    newStack.setCount(1);
                    items.setStackInSlot(creativeSlot, newStack);
                    return 1;
                }
                return 0;
            }
        }
        // 气体升级（max=1，独立槽位）
        if (gasSlot >= 0 && gasSlot < items.getSlots()) {
            UpgradeType type = getType(held);
            if (type == UpgradeType.GAS) {
                ItemStack current = items.getStackInSlot(gasSlot);
                if (current.isEmpty()) {
                    ItemStack newStack = held.copy();
                    newStack.setCount(1);
                    items.setStackInSlot(gasSlot, newStack);
                    return 1;
                }
                return 0;
            }
        }
        // 其他升级走原有逻辑
        return installBasic(items, speedSlot, speedMax, energySlot, energyMax,
                stackSlot, stackMax, gasSlot, held);
    }

    /**
     * 速度/能量/堆叠升级的安装逻辑。
     *
     * @param items      机器的物品处理器
     * @param speedSlot  速度升级槽位（-1 = 不支持）
     * @param speedMax   速度升级上限
     * @param energySlot 能量升级槽位（-1 = 不支持）
     * @param energyMax  能量升级上限
     * @param stackSlot  堆叠升级槽位（-1 = 不支持）
     * @param stackMax   堆叠升级上限
     * @param held       手持的升级模块
     * @return 实际装入/消耗的数量（0 表示未装入）
     */
    private static int installBasic(ItemStackHandler items,
                              int speedSlot, int speedMax,
                              int energySlot, int energyMax,
                              int stackSlot, int stackMax,
                              int gasSlot,
                              ItemStack held) {
        UpgradeType type = getType(held);
        int slot;
        int max;
        switch (type) {
            case SPEED -> {
                slot = speedSlot;
                max = speedMax;
            }
            case ENERGY -> {
                slot = energySlot;
                max = energyMax;
            }
            case STACK -> {
                slot = stackSlot;
                max = stackMax;
            }
            case GAS -> {
                slot = gasSlot;
                max = 1;
            }
            default -> {
                return 0;
            }
        }
        if (slot < 0 || slot >= items.getSlots()) {
            return 0;
        }
        ItemStack current = items.getStackInSlot(slot);
        if (!current.isEmpty()) {
            // 槽内已是其他物品，不覆盖
            if (!ItemStack.isSameItemSameTags(current, held)) {
                return 0;
            }
            if (current.getCount() >= max) {
                return 0;
            }
        }
        int space = max - current.getCount();
        if (space <= 0) {
            return 0;
        }
        int toAdd = Math.min(held.getCount(), space);
        if (toAdd <= 0) {
            return 0;
        }
        ItemStack newStack = held.copy();
        newStack.setCount(current.getCount() + toAdd);
        items.setStackInSlot(slot, newStack);
        return toAdd;
    }
}