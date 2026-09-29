package cn.ism.mekck.item;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.config.MekckConfig;
import mekanism.api.text.EnumColor;
import mekanism.api.text.TextComponentUtil;
import mekanism.client.key.MekKeyHandler;
import mekanism.client.key.MekanismKeyHandler;
import mekanism.common.MekanismLang;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MekCkBlockItem extends BlockItem {

    @Nullable
    private final Component description;
    @Nullable
    private final CuttingMachineFactoryTier tier;
    private final boolean isCooking; // true for cooking machines (no thread count in tooltip)
    // Simple machine stats (used when tier is null, e.g. Universal Cutting Machine)
    private final int simpleParallel;
    private final int simpleEnergyPerTick;
    private final int simpleEnergyCapacity;

    // Full constructor for factories
    public MekCkBlockItem(Block block, Properties properties, @Nullable Component description, @Nullable CuttingMachineFactoryTier tier, boolean isCooking) {
        super(block, properties);
        this.description = description;
        this.tier = tier;
        this.isCooking = isCooking;
        this.simpleParallel = -1;
        this.simpleEnergyPerTick = -1;
        this.simpleEnergyCapacity = -1;
    }

    // Constructor for simple machines (no tier, e.g. Universal Cutting Machine)
    public MekCkBlockItem(Block block, Properties properties, int threads, int energyPerTick, int energyCapacity) {
        super(block, properties);
        this.description = null;
        this.tier = null;
        this.isCooking = false;
        this.simpleParallel = threads;
        this.simpleEnergyPerTick = energyPerTick;
        this.simpleEnergyCapacity = energyCapacity;
    }

    // Constructor for simple cooking machines (no tier, no thread count, e.g. Smart Cooking Pot)
    public MekCkBlockItem(Block block, Properties properties, int parallel, int energyPerTick, int energyCapacity, boolean isCooking) {
        super(block, properties);
        this.description = null;
        this.tier = null;
        this.isCooking = isCooking;
        this.simpleParallel = parallel;
        this.simpleEnergyPerTick = energyPerTick;
        this.simpleEnergyCapacity = energyCapacity;
    }

    // Default constructor (no stats)
    public MekCkBlockItem(Block block, Properties properties) {
        this(block, properties, null, null, false);
    }

    /**
     * 若未显式传入描述，则使用基于方块 id 的默认描述语言键。
     * 这样所有机器都能在按住描述键时显示用途说明。
     * <p>
     * §F21：标准 9 档工厂（注册名形如 {@code <tier>_<family>_factory}）的描述高度重复，
     * 此处把标准档前缀剥掉、归一到家族键 {@code tooltip.mekck.<family>_factory}，使同族各档共用一条文案。
     * blaze/nebula/singularity 三个特殊档**不在** {@link #STANDARD_FACTORY_TIERS} 内，保留各自专属 flavor。
     * </p>
     */
    private static final java.util.Set<String> STANDARD_FACTORY_TIERS = java.util.Set.of(
            "basic", "advanced", "elite", "ultimate", "absolute", "supreme", "cosmic", "infinite", "crystal_matrix");

    private static Component descriptionOrDefault(Block block, @Nullable Component description) {
        if (description != null) {
            return description;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        String path = (id == null ? "unknown" : id.getPath());
        if (path.endsWith("_factory")) {
            for (String tier : STANDARD_FACTORY_TIERS) {
                String prefix = tier + "_";
                if (path.startsWith(prefix)) {
                    path = path.substring(prefix.length());
                    break;
                }
            }
        }
        return Component.translatable("tooltip.mekck." + path);
    }

    /**
     * 当前展示的描述内容（使用用户显式传入的描述或由方块 id 派生的默认描述）。
     */
    private Component getMachineDescription() {
        return description != null ? description : descriptionOrDefault(getBlock(), null);
    }

    @NotNull
    @Override
    public Component getName(@NotNull ItemStack stack) {
        if (tier != null) {
            return TextComponentUtil.build(tier.getColor(), super.getName(stack));
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level world, @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        if (MekKeyHandler.isKeyPressed(MekanismKeyHandler.descriptionKey)) {
            tooltip.add(getMachineDescription().copy().withStyle(style -> style.withColor(EnumColor.AQUA.getColor())));
        } else if (MekKeyHandler.isKeyPressed(MekanismKeyHandler.detailsKey)) {
            addDetails(tooltip, stack);
        } else {
            addStats(tooltip, stack);
            tooltip.add(MekanismLang.HOLD_FOR_DETAILS.translateColored(EnumColor.GRAY, EnumColor.INDIGO, MekanismKeyHandler.detailsKey.getTranslatedKeyMessage()));
            tooltip.add(MekanismLang.HOLD_FOR_DESCRIPTION.translateColored(EnumColor.GRAY, EnumColor.AQUA, MekanismKeyHandler.descriptionKey.getTranslatedKeyMessage()));
        }
    }

    private boolean hasStats() {
        return tier != null || simpleParallel > 0;
    }

    private void addStats(List<Component> tooltip, ItemStack stack) {
        if (tier != null) {
            // 显示工厂等级：等级名颜色与对应物品名一致（tier.getColor()），"工厂等级："标签保持灰色
            tooltip.add(Component.translatable("tooltip.mekck.tier",
                    Component.translatable("tier.mekck." + tier.name)
                            .withStyle(style -> style.withColor(tier.getColor())))
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            // Show thread count only for cutting machines
            if (!isCooking) {
                tooltip.add(Component.translatable("tooltip.mekck.threads", tier.processes).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            tooltip.add(Component.translatable("tooltip.mekck.parallel", getParallelValue(stack)).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            // 显示MAX并行值（对烹饪/串串工厂显示，或有堆叠升级槽的工厂）；奇点创世等级只显示并行。
            // 「是否支持堆叠升级」由枚举的 supportsStackUpgrade() 统一定义，此处不再复述
            // processes >= 11 这条规则（外层已排除 SINGULARITY，故与 supportsStackUpgrade() 严格等价）。
            boolean showMaxParallel = tier != CuttingMachineFactoryTier.SINGULARITY
                    && (tier.supportsStackUpgrade() || isCooking);
            if (showMaxParallel) {
                int maxParallel;
                if (isCooking) {
                    // 非多线程工厂：使用配置文件中的 non_multithreaded maxParallel
                    maxParallel = MekckConfig.getNonMultithreadedMax(tier);
                } else {
                    // 多线程工厂：使用配置文件中的 multithreaded maxParallel
                    maxParallel = MekckConfig.getMultithreadedMax(tier);
                }
                tooltip.add(Component.translatable("tooltip.mekck.max_parallel", maxParallel).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            tooltip.add(Component.translatable("tooltip.mekck.energy_per_tick", tier.energyPerTick).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            
            // Mod detection tooltips
            addModDependencyTooltips(tooltip);
        } else if (simpleParallel > 0) {
            // 显示基础机器等级
            tooltip.add(Component.translatable("tooltip.mekck.tier",
                    Component.translatable("tier.mekck.basic_machine")).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            // Show thread count only for non-cooking simple machines
            if (!isCooking) {
                tooltip.add(Component.translatable("tooltip.mekck.threads", simpleParallel).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            tooltip.add(Component.translatable("tooltip.mekck.parallel", simpleParallel).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.energy_per_tick", simpleEnergyPerTick).withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            
            // Mod detection tooltips for basic machines
            addModDependencyTooltips(tooltip);
        }
    }

    /**
     * Add mod dependency tooltips based on the block type and tier.
     * Checks for barbequesdelight, mekanism_extras, avaritia, and avaritia_delight.
     */
    /**
     * 联动机器支持的模组表：{机器注册名关键字, "modid|中文名", ...}。
     * 只要**任意一个**模组存在机器就有配方可做；全部缺失时才给出提示。
     */
    private static final String[][] LINKED_MODS = {
            {"sushi_maker", "youkaishomecoming|妖怪们的归家"},
            {"average_slicer", "kaleidoscope_cookery|森罗物语：厨房", "bakeries|烘焙坊"},
            {"curd_maker", "trailandtales|樱途旅事", "meadow|青青草甸"},
            {"dehydrator", "youkaishomecoming|妖怪们的归家", "farm_and_charm|沉浸农艺"},
            {"fermenter", "youkaishomecoming|妖怪们的归家", "bakeries|烘焙坊",
                    "brewery|盛节精酿", "drinkbeer|喝啤酒啦"},
            {"steamer", "youkaishomecoming|妖怪们的归家"},
            {"winery", "vinery|葡园酒香", "kaleidoscope_tavern|森罗物语：酒馆"},
            {"juicer", "vinery|葡园酒香", "kaleidoscope_tavern|森罗物语：酒馆"},
            {"bakery_oven", "bakery|馥郁烘焙", "bakeries|烘焙坊"},
            {"stove", "farm_and_charm|沉浸农艺", "bakeries|烘焙坊",
                    "herbalbrews|煨茶酝露", "meadow|青青草甸"},
            {"cocktail_shaker", "kaleidoscope_tavern|森罗物语：酒馆"},
            {"blender", "bakeries|烘焙坊"},
            {"tea_brewer", "simplytea|简单的茶"},
            {"electric_grinding_machine", "kaleidoscope_cookery|森罗物语：厨房",
                    "bakeries|烘焙坊", "farm_and_charm|沉浸农艺"},
            {"smart_cooking_pot", "kaleidoscope_cookery|森罗物语：厨房",
                    "youkaishomecoming|妖怪们的归家"},
    };

    /** 联动机器的模组依赖提示：全部缺失时才显示。 */
    private void addLinkedModTooltip(List<Component> tooltip, String path) {
        for (String[] entry : LINKED_MODS) {
            if (!path.contains(entry[0])) continue;
            boolean anyLoaded = false;
            StringBuilder names = new StringBuilder();
            for (int i = 1; i < entry.length; i++) {
                String[] parts = entry[i].split("\\|");
                if (parts.length < 2) continue;
                if (ModList.get().isLoaded(parts[0])) {
                    anyLoaded = true;
                    break;
                }
                if (names.length() > 0) names.append("、");
                names.append('[').append(parts[1]).append(']').append(parts[0]);
            }
            if (!anyLoaded) {
                tooltip.add(Component.literal("该机器需要安装以下模组之一才有作用：" + names)
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            return;
        }
    }

    private void addModDependencyTooltips(List<Component> tooltip) {
        Block block = getBlock();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
        if (blockId == null) return;
        String path = blockId.getPath();

        // Check if this is a barbequesdelight-related machine (grill or skewer)
        boolean isBarbequesMachine = path.contains("grill") || path.contains("skewer");
        if (isBarbequesMachine && !ModList.get().isLoaded("barbequesdelight")) {
            tooltip.add(Component.literal("该机器需要安装[烧烤乐事]barbequesdelight才有作用")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 三明治组装机：需要 Some Assembly Required（联动功能，非硬依赖）
        if (path.contains("sandwich_assembler")) {
            if (!ModList.get().isLoaded("someassemblyrequired")) {
                tooltip.add(Component.literal("该机器需要安装[三明治]someassemblyrequired才有作用")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            tooltip.add(Component.literal("层数过多会导致三明治的 NBT 过大，可能影响性能甚至超出网络同步限制，请谨慎堆叠")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 中央厨房：说明书式提示（首行用途描述由 getMachineDescription 的 lang 键提供）
        if (path.contains("central_kitchen")) {
            tooltip.add(Component.literal("默认只在下单时加工（不会自动消耗存储区材料）；每个模块可单独开启自动加工")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.literal("缺少中间产物时会自动展开合成链（例如先切牛肉馅再烹饪），最多 3 层")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.literal("双独立温度：发热侧供烘焙/烧烤等系列，制冷侧供制冰系列；正面为冷端、背面为热端")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 联动机器：支持的模组「任一存在即可工作」，全部缺失时才提示
        addLinkedModTooltip(tooltip, path);

        // Check if this is a planting-cutting machine (station or factory): requires mekmm nutrient gas
        boolean isPlantingCuttingMachine = path.contains("planting_cutting");
        if (isPlantingCuttingMachine && !ModList.get().isLoaded("mekmm")) {
            tooltip.add(Component.literal("该机器需要安装[mekmm]通用机械：更多机器才能运行")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }
        // 种植切配工厂：烈焰炽焱及以上等级内置营养液消耗减免（与 PlantingCuttingFactoryBlockEntity 的倍率一致）
        if (isPlantingCuttingMachine && tier != null) {
            int reduction = switch (tier) {
                case BLAZE -> 90;
                case CRYSTAL_MATRIX -> 95;
                case NEBULA -> 99;
                case SINGULARITY -> 100;
                default -> -1;
            };
            if (reduction > 0) {
                tooltip.add(Component.literal("营养液消耗减免：" + reduction + "%")
                        .withStyle(style -> style.withColor(EnumColor.AQUA.getColor())));
            }
        }

        // Factory tier-specific checks
        if (tier != null) {
            // Check mekanism_extras: ULTIMATE (ordinal 3) through INFINITE (ordinal 10)
            if (tier.ordinal() >= CuttingMachineFactoryTier.ULTIMATE.ordinal() && tier.ordinal() <= CuttingMachineFactoryTier.INFINITE.ordinal()) {
                if (!ModList.get().isLoaded("mekanism_extras")) {
                    tooltip.add(Component.literal("终极以上等级的工厂需要安装通用机械：拓展以添加合成表")
                            .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
                }
            }

            // Check avaritia and avaritia_delight: INFINITE (ordinal 10) through SINGULARITY (ordinal 13)
            if (tier.ordinal() >= CuttingMachineFactoryTier.INFINITE.ordinal()) {
                boolean hasAvaritia = ModList.get().isLoaded("avaritia");
                boolean hasAvaritiaDelight = ModList.get().isLoaded("avaritia_delight");
                if (!hasAvaritia || !hasAvaritiaDelight) {
                    tooltip.add(Component.literal("悖论无限以上等级的工厂需要安装无尽贪婪与无尽乐事以添加合成表")
                            .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
                }
            }
        }
    }

    private void addDetails(List<Component> tooltip, ItemStack stack) {
        addStats(tooltip, stack);
        long capacity;
        if (tier != null) {
            capacity = tier.energyCapacity;
        } else {
            capacity = simpleEnergyCapacity;
        }
        long stored = 0;
        CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag != null && beTag.contains("Energy", net.minecraft.nbt.Tag.TAG_INT)) {
            stored = beTag.getInt("Energy");
        }
        // 已储能：X FE / Y kFE（与 Mekanism StorageUtils.addStoredEnergy 一致：标签 BRIGHT_GREEN，值灰色）
        tooltip.add(Component.translatable("tooltip.mekck.detail_energy")
                .withStyle(style -> style.withColor(EnumColor.BRIGHT_GREEN.getColor()))
                .append(Component.literal(stored + " FE / " + (capacity / 1000) + " kFE")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor()))));
        // 存有物品：是/否（标签淡蓝色，值灰色）
        tooltip.add(Component.translatable("tooltip.mekck.detail_has_items")
                .withStyle(style -> style.withColor(EnumColor.AQUA.getColor()))
                .append(Component.literal(hasSustainedItems(stack) ? "是" : "否")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor()))));
    }

    /**
     * 判断方块物品 NBT（BlockEntityTag -> Items -> Items）中是否存有物品。
     */
    private boolean hasSustainedItems(ItemStack stack) {
        CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag == null) {
            return false;
        }
        CompoundTag itemsCompound = beTag.getCompound("Items");
        if (!itemsCompound.contains("Items", Tag.TAG_LIST)) {
            return false;
        }
        ListTag items = itemsCompound.getList("Items", Tag.TAG_COMPOUND);
        return !items.isEmpty();
    }

    /**
     * 从物品NBT读取实际安装的堆叠升级数量，计算并行值。
     * 使用配置文件中的真实 baseParallel 和 maxParallel 值。
     * 基础～终极（processes<11）：无堆叠升级槽，固定为配置的 baseParallel
     * 绝对级及以上（processes>=11）：baseParallel << min(升级数, 6)，但不超过配置的 maxParallel
     */
    private int getParallelValue(ItemStack stack) {
        if (tier == null) return 1;

        if (isCooking) {
            // 非多线程工厂（烹饪/穿串）：使用配置文件中的 non_multithreaded 值
            int baseParallel = MekckConfig.getNonMultithreadedBase(tier);
            if (tier.processes < 11 || tier == CuttingMachineFactoryTier.SINGULARITY) return baseParallel; // 无堆叠升级槽
            int stackCount = readStackUpgradeCount(stack);
            if (stackCount == 0) return baseParallel;
            int maxParallel = MekckConfig.getNonMultithreadedMax(tier);
            // 应用升级倍增，但不超过 maxParallel
            long multiplied = (long) baseParallel << Math.min(stackCount, 6);
            return (int) Math.min(multiplied, maxParallel);
        }

        // 多线程工厂（切割/烧烤/种植切配）：使用配置文件中的 multithreaded 值
        int baseParallel = MekckConfig.getMultithreadedBase(tier);
        if (tier.processes < 11 || tier == CuttingMachineFactoryTier.SINGULARITY) return baseParallel; // 无堆叠升级槽
        int stackCount = readStackUpgradeCount(stack);
        if (stackCount == 0) return baseParallel;
        int maxParallel = MekckConfig.getMultithreadedMax(tier);
        // 应用升级倍增，但不超过 maxParallel
        long multiplied = (long) baseParallel << Math.min(stackCount, 6);
        return (int) Math.min(multiplied, maxParallel);
    }

    /**
     * 从物品NBT的BlockEntityTag中读取堆叠升级安装数量
     * NBT结构: BlockEntityTag -> Items(Compound) -> Items(List) -> [{Slot, Count}]
     * 切菜工厂槽位: 2 * processes + 2
     * 烹饪工厂槽位: 6 + 81 + 4 = 91 (固定，storageSlots始终为81)
     */
    private int readStackUpgradeCount(ItemStack stack) {
        if (tier == null || tier.processes < 11) return 0;
        CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
        if (blockEntityTag == null) return 0;
        CompoundTag itemsCompound = blockEntityTag.getCompound("Items");
        if (!itemsCompound.contains("Items", Tag.TAG_LIST)) return 0;
        ListTag items = itemsCompound.getList("Items", Tag.TAG_COMPOUND);
        // 烹饪工厂堆叠升级槽位 = 6(input) + 144(storage) + 9(output) + 3(return) + 2
        int slotIndex = isCooking ? cn.ism.mekck.blockentity.CookingFactoryBlockEntity.STACK_UPGRADE_SLOT
                : 2 * tier.processes + 2;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            if (itemTag.getInt("Slot") == slotIndex) {
                return itemTag.getInt("Count");
            }
        }
        return 0;
    }
}