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

    /**
     * 「无限」阈值 —— 达到此值的并行数不再显示数字，改显示「无限」。
     *
     * <h3>为什么需要它</h3>
     * 奇点创世（SINGULARITY）的基础并行是 {@code Integer.MAX_VALUE - 1}
     * （见 {@code MekckConfig.getMultithreadedDefaults}：它的设计前提是「基础并行即为极限并行」，
     * 不支持堆叠升级）。直接打出来就是 {@code 并行：2147483646} ——
     * 同一个 tooltip 里「线程数：81」和「并行：2147483646」并列，玩家会以为机器真能并行 21 亿次。
     * 实际语义是「不设上限，能放多少料就并行多少」，所以显示成「无限」才准确。
     *
     * <p>取 {@code Integer.MAX_VALUE / 2} 作为阈值：正常档位的并行上限是
     * 基础并行 × 64（最高 NEBULA 的 8 × 64 = 512），离这个阈值有 6 个数量级的余量，
     * 不会误伤任何真实档位。</p>
     */
    private static final int INFINITE_PARALLEL_THRESHOLD = Integer.MAX_VALUE / 2;

    /**
     * 并行数 → 显示组件：达到 {@link #INFINITE_PARALLEL_THRESHOLD} 时用
     * {@code infiniteKey}（「无限」），否则用 {@code numericKey} 带数字。
     *
     * @param numericKey  带 {@code %s} 的数字文案键
     * @param infiniteKey 不带占位符的「无限」文案键
     */
    private static Component parallelComponent(String numericKey, String infiniteKey, int value) {
        // 声明成 MutableComponent 而不是 Component：三元表达式的目标类型若写成基类
        // Component，{@code withStyle} 就找不到了（那是 MutableComponent 上的方法）。
        net.minecraft.network.chat.MutableComponent body = value >= INFINITE_PARALLEL_THRESHOLD
                ? Component.translatable(infiniteKey)
                : Component.translatable(numericKey, value);
        return body.withStyle(style -> style.withColor(EnumColor.GRAY.getColor()));
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
            tooltip.add(parallelComponent("tooltip.mekck.parallel", "tooltip.mekck.parallel_infinite",
                    getParallelValue(stack)));
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
                tooltip.add(parallelComponent("tooltip.mekck.max_parallel", "tooltip.mekck.max_parallel_infinite",
                        maxParallel));
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
            tooltip.add(parallelComponent("tooltip.mekck.parallel", "tooltip.mekck.parallel_infinite",
                    simpleParallel));
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
     * 联动机器支持的模组表：{机器注册名关键字, modid, ...}。
     * 只要**任意一个**模组存在机器就有配方可做；全部缺失时才给出提示。
     *
     * <p>这里只存 modid、不再存显示名：显示名改走
     * {@code tooltip.mekck.linked_mod.<modid>} 语言键（en/zh 同批），
     * 于是本表不再含任何玩家可见硬编码文本（下方 tooltip 由客户端渲染，
     * 故 {@code getString()} 在客户端解析出本地化名称）。</p>
     */
    private static final String[][] LINKED_MODS = {
            {"sushi_maker", "youkaishomecoming"},
            {"average_slicer", "kaleidoscope_cookery", "bakeries"},
            {"curd_maker", "trailandtales", "meadow"},
            {"dehydrator", "youkaishomecoming", "farm_and_charm"},
            {"fermenter", "youkaishomecoming", "bakeries", "brewery", "drinkbeer"},
            {"steamer", "youkaishomecoming"},
            {"winery", "vinery", "kaleidoscope_tavern"},
            {"juicer", "vinery", "kaleidoscope_tavern"},
            {"bakery_oven", "bakery", "bakeries"},
            {"stove", "farm_and_charm", "bakeries", "herbalbrews", "meadow"},
            {"cocktail_shaker", "kaleidoscope_tavern"},
            {"blender", "bakeries"},
            {"tea_brewer", "simplytea"},
            {"electric_grinding_machine", "kaleidoscope_cookery", "bakeries", "farm_and_charm"},
            {"smart_cooking_pot", "kaleidoscope_cookery", "youkaishomecoming"},
    };

    /** 联动机器的模组依赖提示：全部缺失时才显示。 */
    private void addLinkedModTooltip(List<Component> tooltip, String path) {
        for (String[] entry : LINKED_MODS) {
            if (!path.contains(entry[0])) continue;
            boolean anyLoaded = false;
            StringBuilder names = new StringBuilder();
            for (int i = 1; i < entry.length; i++) {
                String modId = entry[i];
                if (ModList.get().isLoaded(modId)) {
                    anyLoaded = true;
                    break;
                }
                if (names.length() > 0) {
                    names.append(Component.translatable("tooltip.mekck.linked_mod.separator").getString());
                }
                names.append('[')
                        .append(Component.translatable("tooltip.mekck.linked_mod." + modId).getString())
                        .append(']').append(modId);
            }
            if (!anyLoaded) {
                tooltip.add(Component.translatable("tooltip.mekck.linked_mod.prefix")
                        .append(Component.literal(names.toString())
                                .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())))
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
            tooltip.add(Component.translatable("tooltip.mekck.needs_mod.barbequesdelight")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 三明治组装机：需要 Some Assembly Required（联动功能，非硬依赖）
        if (path.contains("sandwich_assembler")) {
            if (!ModList.get().isLoaded("someassemblyrequired")) {
                tooltip.add(Component.translatable("tooltip.mekck.needs_mod.someassemblyrequired")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            }
            tooltip.add(Component.translatable("tooltip.mekck.sandwich_assembler.stacking_warning")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 中央厨房：说明书式提示（首行用途描述由 getMachineDescription 的 lang 键提供）
        if (path.contains("central_kitchen")) {
            tooltip.add(Component.translatable("tooltip.mekck.central_kitchen.manual_only")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.central_kitchen.auto_expand")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
            tooltip.add(Component.translatable("tooltip.mekck.central_kitchen.dual_temp")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }

        // 联动机器：支持的模组「任一存在即可工作」，全部缺失时才提示
        addLinkedModTooltip(tooltip, path);

        // Check if this is a planting-cutting machine (station or factory): requires mekmm nutrient gas
        boolean isPlantingCuttingMachine = path.contains("planting_cutting");
        if (isPlantingCuttingMachine && !ModList.get().isLoaded("mekmm")) {
            tooltip.add(Component.translatable("tooltip.mekck.needs_mod.mekmm")
                    .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
        }
        // 种植切配工厂：烈焰炽焱及以上等级内置营养液消耗减免
        // （倍率定义在 PlantingCuttingFactoryTile.getGasConsumptionMultiplier）
        if (isPlantingCuttingMachine && tier != null) {
            int reduction = switch (tier) {
                case BLAZE -> 90;
                case CRYSTAL_MATRIX -> 95;
                case NEBULA -> 99;
                case SINGULARITY -> 100;
                default -> -1;
            };
            if (reduction > 0) {
                tooltip.add(Component.translatable("tooltip.mekck.nutrient_reduction", reduction + "%")
                        .withStyle(style -> style.withColor(EnumColor.AQUA.getColor())));
            }
        }

        // Factory tier-specific checks
        if (tier != null) {
            // MekExtras 的四个档位：ABSOLUTE(4) ~ INFINITE(7)。
            // ⚠️ 下界必须用「严格大于 ULTIMATE(3)」而不是「大于等于」：
            // ULTIMATE 是 **Mekanism 原生**档（不需要 MekExtras），
            // 而文案写的是「终极**以上**等级的工厂需要安装通用机械：拓展」——
            // 原来的 {@code >=} 会让 ULTIMATE 也弹这条提示，与文案自相矛盾。
            if (tier.ordinal() > CuttingMachineFactoryTier.ULTIMATE.ordinal()
                    && tier.ordinal() <= CuttingMachineFactoryTier.INFINITE.ordinal()) {
                if (!ModList.get().isLoaded("mekanism_extras")) {
                    tooltip.add(Component.translatable("tooltip.mekck.needs_mekanism_extras")
                            .withStyle(style -> style.withColor(EnumColor.GRAY.getColor())));
                }
            }

            // 无尽贪婪 + 无尽乐事联动的档位：BLAZE(8) ~ SINGULARITY(11)。
            // ⚠️ 下界必须用「严格大于 INFINITE(7)」而不是「大于等于」：
            // 文案写的是「悖论无限**以上**等级的工厂需要安装无尽贪婪与无尽乐事」，
            // 而原来的 {@code >=} 让 INFINITE（悖论无限本身）也弹这条提示，与文案矛盾。
            // （这两个档位正是并行数 >17 的四个：25 / 36 / 49 / 81。）
            if (tier.ordinal() > CuttingMachineFactoryTier.INFINITE.ordinal()) {
                boolean hasAvaritia = ModList.get().isLoaded("avaritia");
                boolean hasAvaritiaDelight = ModList.get().isLoaded("avaritia_delight");
                if (!hasAvaritia || !hasAvaritiaDelight) {
                    tooltip.add(Component.translatable("tooltip.mekck.needs_avaritia")
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
                .append(Component.translatable(hasSustainedItems(stack) ? "gui.mekck.ui.yes" : "gui.mekck.ui.no")
                        .withStyle(style -> style.withColor(EnumColor.GRAY.getColor()))));
    }

    /**
     * 判断方块物品 NBT 中是否存有物品。
     *
     * <p>两种格式都要认：旧格式（自研 BE 时代）是
     * {@code BlockEntityTag.Items.Items}；Mek 迁移后的战利品表把 BE 存档键
     * {@code copy_nbt} 进 {@code mekData.Items}（{@code DataHandlerUtils.writeContainers}
     * 的列表）。只认旧格式的话，新掉落物的「存有物品」会显示「否」。</p>
     */
    private boolean hasSustainedItems(ItemStack stack) {
        CompoundTag beTag = stack.getTagElement("BlockEntityTag");
        if (beTag != null) {
            CompoundTag itemsCompound = beTag.getCompound("Items");
            if (itemsCompound.contains("Items", Tag.TAG_LIST)
                    && !itemsCompound.getList("Items", Tag.TAG_COMPOUND).isEmpty()) {
                return true;
            }
        }
        CompoundTag mekData = stack.getTagElement("mekData");
        return mekData != null && mekData.contains("Items", Tag.TAG_LIST)
                && !mekData.getList("Items", Tag.TAG_COMPOUND).isEmpty();
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
     *
     * <p><b>这套读法只对「旧版本存的物品」有效</b>，而且只值 0 或 1（那一格是
     * 一张卡）。阶段 3 把旧 BE 删掉之后，新放置的机器由 Mek 的
     * {@code TileComponentUpgrade} 持卡，写进 {@code BlockEntityTag} 的是 Mek 那套
     * 格式而不是本方法读的 {@code Items.Items[].Slot}——所以新物品这里恒返 0。
     * 保留读法是为了让<b>旧存档里已经存在的那种物品</b>的 tooltip 仍然正确，
     * 槽位下标因此以本文件内的常量写死（原先引的是已删 BE 的公开常量）。
     * 真正的运行时卡数由 {@code tile.getComponent().getUpgrades(...)} 给，
     * tooltip 从方块实体拿不到，这里本来也就是个近似显示。</p>
     */
    private int readStackUpgradeCount(ItemStack stack) {
        if (tier == null || tier.processes < 11) return 0;
        CompoundTag blockEntityTag = stack.getTagElement("BlockEntityTag");
        if (blockEntityTag == null) return 0;
        CompoundTag itemsCompound = blockEntityTag.getCompound("Items");
        if (!itemsCompound.contains("Items", Tag.TAG_LIST)) return 0;
        ListTag items = itemsCompound.getList("Items", Tag.TAG_COMPOUND);
        // 烹饪工厂旧版布局：6(input) + 144(storage) + 9(output) + 3(return) + 2
        int slotIndex = isCooking ? LEGACY_COOKING_STACK_UPGRADE_SLOT : 2 * tier.processes + 2;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag itemTag = items.getCompound(i);
            if (itemTag.getInt("Slot") == slotIndex) {
                return itemTag.getInt("Count");
            }
        }
        return 0;
    }

    /**
     * 旧 {@code CookingFactoryBlockEntity.STACK_UPGRADE_SLOT} 的值。
     *
     * <p>只用于读旧存档写出的物品 NBT，见 {@link #readStackUpgradeCount} 的注释。</p>
     */
    private static final int LEGACY_COOKING_STACK_UPGRADE_SLOT = 6 + 144 + 9 + 3 + 2;
}