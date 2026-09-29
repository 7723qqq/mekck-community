package cn.ism.mekck.config;

import cn.ism.mekck.CuttingMachineFactoryTier;
import mekanism.api.Upgrade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for factory parallel processing.
 * <p>
 * Two categories:
 * - {@code multithreaded} 多线程工厂（切菜、烧烤、种植切配、研磨）
 * - {@code non_multithreaded} 非多线程工厂（烹饪、穿串）
 * <p>
 * Default values:
 * <table>
 *   <tr><th>Tier</th><th>Multithreaded base</th><th>Multithreaded max</th><th>Non-multithreaded base</th><th>Non-multithreaded max</th></tr>
 *   <tr><td>Basic~Ultimate</td><td>1</td><td>1</td><td>1 + tier*2</td><td>1 + tier*2</td></tr>
 *   <tr><td>Absolute~Infinite</td><td>1</td><td>64</td><td>1 + tier*2</td><td>(1 + tier*2) × 64</td></tr>
 *   <tr><td>Crystal Matrix</td><td>1</td><td>128</td><td>1 + tier*2</td><td>(1 + tier*2) × 128</td></tr>
 *   <tr><td>Nebula</td><td>1</td><td>256</td><td>1 + tier*2</td><td>(1 + tier*2) × 256</td></tr>
 *   <tr><td>Singularity</td><td>357913941</td><td>2147483646</td><td>357913941</td><td>2147483646</td></tr>
 * </table>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MekckConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK");

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // ──────────────────────────────────────────────
    // Multithreaded factories (cutting, grill, planting_cutting)
    // ──────────────────────────────────────────────
    private static final Map<CuttingMachineFactoryTier, ForgeConfigSpec.IntValue> MT_BASE = new EnumMap<>(CuttingMachineFactoryTier.class);

    // ──────────────────────────────────────────────
    // Non-multithreaded factories (cooking, skewering)
    // ──────────────────────────────────────────────
    private static final Map<CuttingMachineFactoryTier, ForgeConfigSpec.IntValue> NMT_BASE = new EnumMap<>(CuttingMachineFactoryTier.class);

    // ──────────────────────────────────────────────
    // Bioreactor custom fuels
    // ──────────────────────────────────────────────
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BIOREACTOR_FUELS;
    /** 生物反应堆「可接受不消耗」的食物（永久燃料：转化时产流体但不消耗物品）。 */
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> BIOREACTOR_ETERNAL_FOODS;
    /** 「可接受不消耗」食物缓存，配置重载时置空重建。key=物品注册名，value=自定义每单位 mb（null=用默认 10000）。 */
    private static volatile Map<ResourceLocation, Integer> bioreactorEternalFoodsCache;

    // ──────────────────────────────────────────────
    // Upgrade slot limits
    // ──────────────────────────────────────────────

    /** 见 auto_pull 段：每种物品的 ME 自动补料上限。 */
    private static final ForgeConfigSpec.IntValue AUTO_PULL_STACK_LIMIT;

    // ──────────────────────────────────────────────
    // Ice Maker (急冻制冰机 / 制冰工厂) settings
    // ──────────────────────────────────────────────
    private static final ForgeConfigSpec.DoubleValue ICE_CUBE_DAMAGE_MULT;
    // ──────────────────────────────────────────────
    // Chocolate Cannon (巧克力大炮) settings
    // ──────────────────────────────────────────────
    private static final ForgeConfigSpec.DoubleValue FERRERO_DAMAGE_MULT;
    private static final ForgeConfigSpec.IntValue ICE_ATTACK_RADIUS;
    private static final ForgeConfigSpec.IntValue ICE_ATTACK_INTERVAL;
    /** 冷萃攻击敌对生物列表（实体注册名，如 minecraft:zombie / dummmmmmy:target_dummy）。 */
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ICE_ATTACK_HOSTILE_ENTITIES;
    /** 敌对列表缓存，配置重载时置空重建。 */
    private static volatile Set<ResourceLocation> iceAttackHostileCache;
    /** 是否自动扫描全部注册的敌对生物（继承 Monster / 实现 Enemy）并入名单。 */
    private static final ForgeConfigSpec.BooleanValue ICE_ATTACK_AUTO_SCAN;
    /** F10 攻击增益彩虹连线渲染开关（默认：开）。 */
    private static final ForgeConfigSpec.BooleanValue BUFF_CONNECTION_RENDER;
    private static final ForgeConfigSpec.BooleanValue CRYSTAL_MATRIX_GRILL_FURNACE;
    private static final ForgeConfigSpec.BooleanValue SINGULARITY_GRILL_FURNACE;
    private static final ForgeConfigSpec.BooleanValue GRILL_FURNACE_JEI_CATALYST;
    private static final ForgeConfigSpec.BooleanValue MULTIBLOCK_PREVIEW;
    private static final ForgeConfigSpec.BooleanValue SINGLE_BLOCK_PREVIEW;
    private static final ForgeConfigSpec.BooleanValue OTHER_MODS_MACHINE_PREVIEW;
    private static final ForgeConfigSpec.BooleanValue PREVIEW_TRANSLUCENT;
    private static final ForgeConfigSpec.BooleanValue PREVIEW_WIREFRAME;
    private static final ForgeConfigSpec.BooleanValue PREVIEW_BOX;
    private static final ForgeConfigSpec.BooleanValue AUTO_GENERATE_PLANTING;
    private static final ForgeConfigSpec.BooleanValue PLANTING_DEBUG_TO_CHAT;
    /** 种植配方黑名单（原先在独立的 planting_blacklist.json，2026-09-16 合并进本文件）。 */
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PLANTING_BLACKLIST;
    /** 49 种随机食物的重新随机时机：{@code per_world}（默认）/ {@code per_restart}。 */
    private static final ForgeConfigSpec.ConfigValue<String> CREATIVE_UPGRADE_ROTATION_MODE;
    private static final ForgeConfigSpec.BooleanValue CREATIVE_UPGRADE_FOOD_HINT;
    private static final ForgeConfigSpec.BooleanValue AUTO_GENERATE_PLATING;
    private static final ForgeConfigSpec.DoubleValue BOX_LINE_WIDTH;
    private static final ForgeConfigSpec.DoubleValue PREVIEW_TRANSLUCENT_ALPHA;


    // Factory per-tier values
    private static final Map<CuttingMachineFactoryTier, ForgeConfigSpec.IntValue> FACTORY_STACK_MAX  = new EnumMap<>(CuttingMachineFactoryTier.class);

    /**
     * 堆叠升级级数的硬上限：每级使并行倍率翻倍，故 6 级 = ×64。
     *
     * <p><b>这是"堆叠升级能叠多高"的唯一权威值。</b>它同时被用作：</p>
     * <ol>
     *   <li>{@code *_stack_max} 配置项的 {@code defineInRange} 上界——收窄后，配置文件
     *       <b>无法表达</b>代码不支持的数值，从而消除"配置声明一个范围、代码实现另一个
     *       更小范围"这类静默失效；</li>
     *   <li>各工厂 {@code getStackMultiplier()} 中倍率的夹紧上界；</li>
     *   <li>{@link #getStackUpgradeDefault} 中支持等级的默认值。</li>
     * </ol>
     * 修改本值时请三处一起生效，不要只改一处。</p>
     */
    public static final int STACK_UPGRADE_MAX = 6;

    static {
        // ─── Multithreaded ────────────────────────
        BUILDER.comment("多线程工厂（切菜工厂、烧烤工厂、种植切配工厂、研磨工厂）的设置。",
                "baseParallel：每个槽位每次操作处理的物品数量（未安装堆叠升级时）。",
                "注意：**最大并行数不是可配置项**，它由「基础并行数 × 堆叠升级倍率」自动算出——",
                "堆叠升级每级使倍率翻倍（上限 6 级 = ×64），因此 最大并行 = 基础并行 × 64。",
                "想要提高某等级的上限，请调该等级的 baseParallel。")
                .push("multithreaded");

        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String name = tier.name;
            String cnName = tierCnName(tier);
            int[] defaults = getMultithreadedDefaults(tier);
            MT_BASE.put(tier, BUILDER
                    .comment(cnName + " 基础并行数（最大并行 = 本值 × 64，随堆叠升级提升）")
                    .defineInRange(name + "_base_parallel", defaults[0], 1, Integer.MAX_VALUE));
        }

        BUILDER.pop();

        // ─── Non-multithreaded ────────────────────
        BUILDER.comment("非多线程工厂（烹饪工厂、穿串工厂）的设置。",
                "baseParallel：每次操作处理的物品数量（未安装堆叠升级时）。",
                "注意：**最大并行数不是可配置项**，它由「基础并行数 × 堆叠升级倍率」自动算出——",
                "堆叠升级每级使倍率翻倍（上限 6 级 = ×64），因此 最大并行 = 基础并行 × 64。")
                .push("non_multithreaded");

        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String name = tier.name;
            String cnName = tierCnName(tier);
            int[] defaults = getNonMultithreadedDefaults(tier);
            NMT_BASE.put(tier, BUILDER
                    .comment(cnName + " 基础并行数（最大并行 = 本值 × 64，随堆叠升级提升）")
                    .defineInRange(name + "_base_parallel", defaults[0], 1, Integer.MAX_VALUE));
        }

        BUILDER.pop();

        // ─── Upgrade slot limits ──────────────────
        BUILDER.comment("所有机器的升级槽上限。",
                "factory_stack_max：各等级工厂的堆叠升级上限（0 = 不支持）。",
                "注意：**速度/能量升级上限不是配置项**——它由 Mekanism 枚举的 getMax() 固定为 8。",
                "配置得再高也装不上去：安装进度与实际装入数量两处都被 TileComponentUpgrade 卡在 getMax()，",
                "所以旧版本 *_speed_energy_max / basic_speed_max / basic_energy_max 已删除。")
                .push("upgrade_limits");

        // Factory per-tier limits
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String name = tier.name;
            String cnName = tierCnName(tier);
            int stackDefault = getStackUpgradeDefault(tier);
            FACTORY_STACK_MAX.put(tier, BUILDER
                    .comment(cnName + " 最大堆叠升级数（0 = 不支持）。每级使并行倍率翻倍，上界 "
                            + STACK_UPGRADE_MAX + " 级 = ×" + (1 << STACK_UPGRADE_MAX) + "。")
                    .defineInRange(name + "_stack_max", stackDefault, 0, STACK_UPGRADE_MAX));
        }

        BUILDER.pop();

        // ─── Network auto-pull (AE2) ──────────────
        BUILDER.comment("ME 网络「持续自动补料」设置（机器 GUI 的『自动』按钮，或 ME 拉料按钮旁的开关）。",
                "auto_pull_stack_limit：每种已勾选物品允许在机器输入槽堆积的上限（默认 64）。",
                "  · 调大即可从 ME 网络一次补进更多材料：设为 2147483646 时单种材料可堆到 21 亿；",
                "  · 注意这是**持续**行为：勾选后即使没有订单，机器也会不断把该材料从网络抽进自己的输入槽，",
                "    上限 = 本项 × 已勾选物品种类数（81 种 × 21 亿 = 1739 亿），请按需设置。",
                "  · 补料频率由服务器卡顿档位自动调节（正常档每 tick，降档 4/10 tick 且按坐标错开）。")
                .push("auto_pull");
        AUTO_PULL_STACK_LIMIT = BUILDER
                .comment("每种物品的补料上限（默认：64）")
                .defineInRange("auto_pull_stack_limit", 64, 1, Integer.MAX_VALUE - 1);
        BUILDER.pop();

        // ─── Ice Maker settings ───────────────────
        BUILDER.comment("急冻制冰机与制冰工厂设置。",
                "ice_cube_damage_mult：冰块命中实体时基础冰冻伤害的倍数（叠加冷萃升级后受此系数影响）。",
                "ice_attack_radius：冷萃攻击默认索敌半径（方块，可在机器 GUI 内调整，下限 4、上限不限）。",
                "ice_attack_interval：冷萃攻击间隔（游戏刻，默认 40 = 2 秒）。")
                .push("ice_maker");
        ICE_CUBE_DAMAGE_MULT = BUILDER
                .comment("冰块命中基础冰冻伤害倍数（默认：1.0）")
                .defineInRange("ice_cube_damage_mult", 1.0, 0.0, 1000.0);
        ICE_ATTACK_RADIUS = BUILDER
                .comment("冷萃攻击默认索敌半径（默认：16，下限 4、上限不限）")
                .defineInRange("ice_attack_radius", 16, 4, Integer.MAX_VALUE);
        ICE_ATTACK_INTERVAL = BUILDER
                .comment("冷萃攻击间隔（游戏刻，默认 40）")
                .defineInRange("ice_attack_interval", 40, 1, 1200);
        BUFF_CONNECTION_RENDER = BUILDER
                .comment("F10 攻击增益连线：是否渲染 juicer/bakery_oven 到攻击型机器的顶面彩虹连线（默认：开）")
                .define("buff_connection_render", true);
        ICE_ATTACK_HOSTILE_ENTITIES = BUILDER
                .comment("冷萃攻击的敌对生物列表（实体注册名）。",
                        "目标模式为“敌对”时仅攻击列表内实体，“动物”模式攻击列表外实体。",
                        "默认包含全部原版敌对生物、靶子假人（dummmmmmy:target_dummy）与冰火传说全部 Monster 系敌对生物。",
                        "开启 auto_scan_hostile_entities 后，其他模组的敌对生物（继承 Monster 或实现 Enemy）会自动并入，无需手动添加。")
                .defineListAllowEmpty(List.of("ice_attack_hostile_entities"),
                        MekckConfig::defaultIceAttackHostileEntities, s -> s instanceof String);
        ICE_ATTACK_AUTO_SCAN = BUILDER
                .comment("自动扫描全部注册的敌对生物（继承 Monster 或实现 Enemy 的实体）并入敌对名单。",
                        "关闭后仅使用上方 ice_attack_hostile_entities 列表（可手动剔除个别生物）。")
                .define("auto_scan_hostile_entities", true);
        BUILDER.pop();

        // ─── Chocolate Cannon settings ───────
        BUILDER.comment("巧克力大炮设置。",
                "ferrero_damage_mult：费列罗直击/范围结算/处决判定所用伤害的统一倍数（按已装升级数查档后受此系数影响，默认 1.0）。")
                .push("chocolate_cannon");
        FERRERO_DAMAGE_MULT = BUILDER
                .comment("费列罗伤害倍数（默认：1.0）")
                .defineInRange("ferrero_damage_mult", 1.0, 0.0, 1000.0);
        BUILDER.pop();

        // ─── Placement preview ─────────
        BUILDER.comment("放置预览设置（手持机器方块物品时显示的模型线框预览）。",
                "单方块机器与多方块机器可分别开关，默认均开启。")
                .push("placement_preview");
        MULTIBLOCK_PREVIEW = BUILDER
                .comment("多方块机器（生物反应堆/种植切配站/种植切配工厂等）放置预览（默认：开）")
                .define("multiblock_preview", true);
        SINGLE_BLOCK_PREVIEW = BUILDER
                .comment("单方块机器放置预览（默认：开）——除种植切配站外的所有基础机器 + 所有工厂版本（种植切配工厂/生物反应堆等多方块仍走多方块预览）")
                .define("single_block_preview", true);
        OTHER_MODS_MACHINE_PREVIEW = BUILDER
                .comment("其他模组的机器放置预览（默认：开）",
                        "仅对具机器特征的方块生效（有朝向 + 有运行状态(active/lit/powered/working 等) 或可存储能量），",
                        "普通装饰方块/原版方块不受影响。")
                .define("other_mods_machine_preview", true);
        PREVIEW_TRANSLUCENT = BUILDER
                .comment("半透明机器模型预览（默认：开）——放置预览中额外渲染半透明的机器模型，",
                        "与模型线框同时启用。")
                .define("translucent_model_preview", true);
        PREVIEW_WIREFRAME = BUILDER
                .comment("模型逐边线框预览（默认：关）——放置预览中的彩虹逐边线框（原版细线），",
                        "与半透明模型/包围盒线框可分别开关。")
                .define("wireframe_preview", false);
        PREVIEW_BOX = BUILDER
                .comment("包围盒线框预览（默认：开）——放置预览中机器模型顶点的彩虹包围盒线框，",
                        "粗细见 box_line_width；与半透明模型/逐边线框可分别开关。")
                .define("box_preview", true);
        BOX_LINE_WIDTH = BUILDER
                .comment("包围盒线框粗细倍数（0.5~3.0，默认 1.5）")
                .defineInRange("box_line_width", 1.5, 0.5, 3.0);
        PREVIEW_TRANSLUCENT_ALPHA = BUILDER
                .comment("半透明模型透明度（0.05~1.0，默认 0.35）")
                .defineInRange("translucent_model_alpha", 0.35, 0.05, 1.0);
        BUILDER.pop();

        // ─── Grill factory furnace recipes ─────────
        BUILDER.comment("烧烤工厂可处理原版熔炉 / 高炉配方的设置（默认全部开启）。",
                "晶钛矩阵~星云塑造为一组配置，奇点创世为另一组；JEI 催化剂显示开关独立。",
                "原版烟熏炉 / 篝火烹饪（熟肉、烤马铃薯、干燥海带）是全档位基础能力，不受本节开关约束。")
                .push("grill_furnace");
        CRYSTAL_MATRIX_GRILL_FURNACE = BUILDER
                .comment("晶钛矩阵~星云塑造烧烤工厂可处理熔炉 / 高炉配方（默认：开）")
                .define("crystal_matrix_grill_furnace_recipes", true);
        SINGULARITY_GRILL_FURNACE = BUILDER
                .comment("奇点创世烧烤工厂可处理熔炉 / 高炉配方（默认：开）")
                .define("singularity_grill_furnace_recipes", true);
        GRILL_FURNACE_JEI_CATALYST = BUILDER
                .comment("JEI 中显示晶钛矩阵以上烧烤工厂为熔炉 / 高炉配方催化剂（默认：开）。",
                        "烟熏炉 / 篝火催化剂对全档位显示，不受本开关约束。")
                .define("grill_furnace_jei_catalyst", true);
        BUILDER.pop();

        // ─── Bioreactor custom fuels ───────────────
        BUILDER.comment("生物反应堆（生物反应堆）自定义有机燃料设置（规则C，优先级最高）。",
                "每行格式：注册名=每单位mb，例如 minecraft:apple=400 表示每个苹果产生 400 mB 有机物。",
                "命中配置的燃料会覆盖规则A（Mekanism生物燃料配方）和规则B（食物营养值）。")
                .push("bioreactor");
        BIOREACTOR_FUELS = BUILDER
                .comment("自定义有机燃料列表。格式 examplemod:item=每单位mb；留空则仅使用规则A与规则B。")
                .defineListAllowEmpty(List.of("fuels"), () -> List.of(), s -> s instanceof String);
        BIOREACTOR_ETERNAL_FOODS = BUILDER
                .comment("「可接受不消耗」食物列表。",
                        "列表内物品放进生物反应堆会持续转化有机物流体，但物品本身不会被消耗（永久燃料）。",
                        "每行格式：注册名（如 artifacts:eternal_steak）或 注册名=每单位mb（如 relics:infinity_ham=1600）。",
                        "未写 =mb 时转换量固定为 10000 mb/个；每 tick 每槽转化 1 个单位。",
                        "默认包含不朽类食物：artifacts:everlasting_beef、artifacts:eternal_steak、botania:infinite_fruit，",
                        "以及 relics:infinity_ham=1600（产出与牛排 minecraft:cooked_beef 相同：营养 8 × 200 mb/个）。")
                .defineListAllowEmpty(List.of("eternal_foods"),
                        () -> List.of("artifacts:everlasting_beef", "artifacts:eternal_steak", "botania:infinite_fruit",
                                "relics:infinity_ham=1600"),
                        s -> s instanceof String);
        BUILDER.pop();

        // ─── Planting recipe generation ─────────
        BUILDER.comment("种植配方自动生成设置。",
                "服务器启动时扫描全部可种植的物品与方块，自动生成以下三套种植配方：",
                "  · mekck:plantcut —— 本模组的种植切配站 / 种植切配工厂；",
                "  · mekmm:planting —— 通用机械：更多机器的种植站（需安装 mekmm）；",
                "  · immersiveengineering:cloche（写入 mekck:plant_ie）—— 沉浸工程的园艺玻璃罩（需安装沉浸工程）。",
                "生成结果写入世界目录的 datapacks/mekck_planting，并在生成后自动执行一次 /reload。")
                .push("planting");
        AUTO_GENERATE_PLANTING = BUILDER
                .comment("启动时自动生成种植配方（默认：开）",
                        "关闭后不再扫描与生成，已生成的配方保持不变；",
                        "若想手动触发一次生成，可在游戏内使用种植配方生成命令。")
                .define("auto_generate_planting_recipes", true);
        PLANTING_DEBUG_TO_CHAT = BUILDER
                .comment("向首位进入服务器的玩家输出种植配方生成调试信息（默认：开）",
                        "开启后：首位玩家进入世界时会收到一份生成结果摘要，同时写出",
                        "  config/mekck/planting_generator_debug.log 调试日志。",
                        "关闭后：两者都不产生（普通游玩不需要这些信息）。",
                        "注：本项原为「TOML 开关 + 独立 planting_debug.json 开关」两道门，",
                        "  两者默认值还互相矛盾（TOML 默认开、JSON 默认关，实际等于关）。",
                        "  2026-09-16 合并为这一项，默认值沿用**实际行为**（关）。",
                        "  2026-09-26 用户拍板改回默认**开**（与早期 TOML 门的设计意图一致，方便验收种植配方生成结果）。")
                .define("print_planting_debug_to_chat", true);
        PLANTING_BLACKLIST = BUILDER
                .comment("种植配方黑名单：列表中的物品/方块注册名不会被自动生成种植配方",
                        "（涉及 mekmm:planting / immersiveengineering:cloche / mekck:plantcut 三套）。",
                        "格式为注册名，例如 vinery:spruce_lattice、minecraft:stick。",
                        "默认已包含 15 项（葡园酒香的 10 种棚架、木棍、以及 4 种不该当种子种的作物块）。",
                        "修改后需重启游戏或服务器生效。")
                .defineListAllowEmpty(List.of("blacklist"), () -> List.of(
                        "vinery:spruce_lattice",
                        "vinery:mangrove_lattice",
                        "vinery:bamboo_lattice",
                        "vinery:cherry_lattice",
                        "vinery:oak_lattice",
                        "vinery:birch_lattice",
                        "vinery:dark_oak_lattice",
                        "vinery:acacia_lattice",
                        "vinery:dark_cherry_lattice",
                        "vinery:jungle_lattice",
                        "minecraft:stick",
                        "trailandtales_delight:curd_block",
                        "trailandtales_delight:cherry_curd_block",
                        "trailandtales_delight:raw_bamboo_tube_rice",
                        "trailandtales_delight:bamboo_tube_rice_block"),
                        s -> s instanceof String);
        BUILDER.pop();

        // ─── Creative upgrade (49 random foods) ─────────
        BUILDER.comment("无尽贪婪终末合成「49 种随机食物 → 创造升级」设置。",
                "配方本体固定（data/mekck/recipes/creative_upgrade_from_49_foods.json），",
                "启动时重写存档数据包 datapacks/mekck_creative_upgrade 下的 49 个单元素 tag，")
                .push("creative_upgrade");
        CREATIVE_UPGRADE_ROTATION_MODE = BUILDER
                .comment("49 种随机食物的重新随机时机（默认：per_world）。",
                        "per_world   = 每个存档随机一次；不同存档不同，同一存档重启不变；",
                        "per_restart = 每次重启服务器都重新随机。",
                        "注意：/reload 与退出重进存档都不会触发重新随机。",
                        "（旧配置项 rotate_foods_on_startup 已废弃：true → per_restart、false → per_world，启动时自动迁移。）")
                .define("food_rotation_mode", "per_world");
        CREATIVE_UPGRADE_FOOD_HINT = BUILDER
                .comment("向首位进入服务器的玩家提示「本局 49 种食物已生成、可在 JEI 查看」（默认：开）")
                .define("print_food_hint_to_chat", true);
        BUILDER.pop();

        // ─── Plating (auto-generated combining recipes) ─────────
        BUILDER.comment("装盘配方自动生成设置。",
                "扫描所有已安装模组的宴席方块（农夫乐事 FeastBlock 及其附属实现），",
                "自动生成 Mekanism 融合机配方：宴席方块 + 容器 ×（份数 − 方块合成已含的容器数）→ 碗装食物 ×份数。",
                "若该功能导致加载异常，可将下方开关设为 false 完全停用。")
                .push("plating");
        AUTO_GENERATE_PLATING = BUILDER
                .comment("启动与 /reload 时自动生成装盘配方（默认：开）")
                .define("auto_generate_plating_recipes", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static final ForgeConfigSpec SPEC;

    /** 等级的中文名称（用于配置文件的注释）。 */
    private static String tierCnName(CuttingMachineFactoryTier tier) {
        return switch (tier) {
            case BASIC -> "基础";
            case ADVANCED -> "高级";
            case ELITE -> "精英";
            case ULTIMATE -> "终极";
            case ABSOLUTE -> "绝对";
            case SUPREME -> "至尊";
            case COSMIC -> "寰宇支配";
            case INFINITE -> "悖论无限";
            case BLAZE -> "烈焰炽焱";
            case CRYSTAL_MATRIX -> "晶钛矩阵";
            case NEBULA -> "星云塑造";
            case SINGULARITY -> "奇点创世";
        };
    }

    private static int[] getMultithreadedDefaults(CuttingMachineFactoryTier tier) {
        return switch (tier) {
            case BASIC, ADVANCED, ELITE, ULTIMATE -> new int[]{1, 1};
            case ABSOLUTE, SUPREME, COSMIC, INFINITE -> new int[]{1, 0};
            // 高等级：基础并行翻倍递增，使「基础并行 × 64」自然拉开（128 / 256 / 512）
            case BLAZE -> new int[]{2, 0};
            case CRYSTAL_MATRIX -> new int[]{4, 0};
            case NEBULA -> new int[]{8, 0};
            case SINGULARITY -> new int[]{2_147_483_646, 0};
        };
    }

    private static int[] getNonMultithreadedDefaults(CuttingMachineFactoryTier tier) {
        if (tier == CuttingMachineFactoryTier.SINGULARITY) {
            // 奇点创世：基础并行即为极限并行（不支持堆叠升级）
            return new int[]{2_147_483_646, 2_147_483_646};
        }
        // 显式按等级映射（不再用 ordinal 推导）：这样将来在等级序列中间插入新等级时，
        // 已有等级的默认并行数不会跟着漂移。
        int base = switch (tier) {
            case BASIC -> 1;
            case ADVANCED -> 3;
            case ELITE -> 5;
            case ULTIMATE -> 7;
            case ABSOLUTE -> 9;
            case SUPREME -> 11;
            case COSMIC -> 13;
            case INFINITE -> 15;
            // 高等级：与多线程工厂同样按 ×2 / ×4 / ×8 提升基础并行，使最大并行拉开
            case BLAZE -> 30;
            case CRYSTAL_MATRIX -> 60;
            case NEBULA -> 120;
            case SINGULARITY -> 25;
        };
        int maxMult = switch (tier) {
            case BASIC, ADVANCED, ELITE, ULTIMATE -> 1;
            case ABSOLUTE, SUPREME, COSMIC, INFINITE -> 64;
            case BLAZE -> 128;
            case CRYSTAL_MATRIX -> 256;
            case NEBULA -> 512;
            case SINGULARITY -> 2048;
        };
        // Clamp to avoid overflow
        long max = (long) base * maxMult;
        if (max > Integer.MAX_VALUE) max = Integer.MAX_VALUE;
        return new int[]{base, (int) max};
    }

    // ── Public accessors ───────────────────────────────────────────────

    /** Base parallel count for multithreaded factories. */
    public static int getMultithreadedBase(CuttingMachineFactoryTier tier) {
        ForgeConfigSpec.IntValue val = MT_BASE.get(tier);
        return val != null ? val.get() : getMultithreadedDefaults(tier)[0];
    }

    /**
     * Max parallel count for multithreaded factories.
     *
     * <p><b>不是独立的配置项</b>，而是推导值：{@link #getMultithreadedBase} ×
     * {@link #getFactoryStackUpgradeMax}。后者是真正的配置项（每级翻倍，上界
     * {@link #STACK_UPGRADE_MAX} 级 = ×64），故最大并行 = 基础并行 × 2^配置值。
     * 用 long 运算避免溢出。</p>
     *
     * <p>注意各工厂的 {@code getStackMultiplier()} 用的夹紧上界是同一个配置值，
     * 因此本方法算出的上限与实际可达的倍率自洽。</p>
     */
    public static int getMultithreadedMax(CuttingMachineFactoryTier tier) {
        return computedMaxParallel(getMultithreadedBase(tier), getFactoryStackUpgradeMax(tier));
    }

    /** Base parallel count for non-multithreaded factories. */
    public static int getNonMultithreadedBase(CuttingMachineFactoryTier tier) {
        ForgeConfigSpec.IntValue val = NMT_BASE.get(tier);
        return val != null ? val.get() : getNonMultithreadedDefaults(tier)[0];
    }

    /**
     * Max parallel count for non-multithreaded factories.
     *
     * <p>同 {@link #getMultithreadedMax}：由「基础并行 × 堆叠升级倍率」推导得出。</p>
     */
    public static int getNonMultithreadedMax(CuttingMachineFactoryTier tier) {
        return computedMaxParallel(getNonMultithreadedBase(tier), getFactoryStackUpgradeMax(tier));
    }

    /**
     * 计算最大并行数：基础并行 × 2^堆叠升级上限，用 long 运算后夹到 int 上限。
     * 堆叠升级上限为 0 时不翻倍（该等级不支持堆叠升级）。
     *
     * <p>注意：{@code stackUpgradeMax} 来自配置，已被 {@code defineInRange} 限制在
     * {@code [0, STACK_UPGRADE_MAX]}。下面的 {@code 30} 是 <b>位移溢出保护</b>
     * （long 左移超过 62 位无意义），<b>不是</b>策略上限——策略上限只有
     * {@link #STACK_UPGRADE_MAX} 一处，不要在这里再引入第二个数字。</p>
     */
    private static int computedMaxParallel(int baseParallel, int stackUpgradeMax) {
        long mult = 1L << Math.max(0, Math.min(stackUpgradeMax, 30));
        long max = (long) baseParallel * mult;
        return (int) Math.min(max, Integer.MAX_VALUE);
    }

    // ── Bioreactor accessors ──────────────────────────────────────────

    /** 自定义有机燃料列表（规则C）：条目格式 “注册名=每单位mb”。 */
    public static List<String> getBioreactorFuels() {
        return new ArrayList<>(BIOREACTOR_FUELS.get());
    }

    // ── Upgrade limit defaults ─────────────────────────────────────────

    private static int getStackUpgradeDefault(CuttingMachineFactoryTier tier) {
        // 是否支持堆叠升级由枚举的 supportsStackUpgrade() 单独定义，此处不再复述该规则。
        return tier.supportsStackUpgrade() ? STACK_UPGRADE_MAX : 0;
    }

    // ── Network auto-pull ──────────────────────────────────────────────

    /**
     * ME 网络「持续自动补料」时，每种已勾选物品允许在输入槽堆积的上限。
     * 默认 64；设为 {@code Integer.MAX_VALUE-1} 时单种材料可堆到 21 亿
     * （注意这是持续行为：上限 × 已勾选种类数 就是会被抽走的材料总量）。
     */
    public static int getAutoPullStackLimit() {
        ForgeConfigSpec.IntValue val = AUTO_PULL_STACK_LIMIT;
        return val != null ? val.get() : 64;
    }

    // ── Upgrade limit accessors ────────────────────────────────────────

    /** 基础机器（通用切菜机、种植切配站等）的速度升级上限：同 {@link #getFactorySpeedUpgradeMax}，固定取枚举自带值。 */
    public static int getBasicSpeedUpgradeMax() {
        return Upgrade.SPEED.getMax();
    }

    /** 基础机器的能量升级上限：同 {@link #getFactorySpeedUpgradeMax}，固定取枚举自带值。 */
    public static int getBasicEnergyUpgradeMax() {
        return Upgrade.ENERGY.getMax();
    }

    /** 启动与 /reload 时是否自动生成装盘（融合机）配方。 */
    public static boolean getAutoGeneratePlatingRecipes() {
        return AUTO_GENERATE_PLATING.get();
    }

    /** 启动时是否自动生成种植配方。 */
    public static boolean getAutoGeneratePlantingRecipes() {
        return AUTO_GENERATE_PLANTING.get();
    }

    /** 是否向首位进入服务器的玩家输出种植配方调试信息（合并后的唯一开关，2026-09-26 起默认开）。 */
    public static boolean getPlantingDebugToChat() {
        return PLANTING_DEBUG_TO_CHAT.get();
    }

    /** 种植配方黑名单（注册名字符串列表）。 */
    public static List<? extends String> getPlantingBlacklist() {
        return PLANTING_BLACKLIST.get();
    }

    /** 写入种植配方黑名单（供旧 JSON 迁移使用）。 */
    public static void setPlantingBlacklist(List<? extends String> values) {
        PLANTING_BLACKLIST.set(values);
    }

    /** 写入种植调试开关（供旧 JSON 迁移使用）。 */
    public static void setPlantingDebugToChat(boolean value) {
        PLANTING_DEBUG_TO_CHAT.set(value);
    }

    /**
     * 立即把当前配置写回 mekck-common.toml。
     * <p>{@code ConfigValue.set()} 是否落盘依赖 Forge 的保存时机，迁移旧 JSON 时不能赌 ——
     * 所以写完显式调一次本方法，确保玩家重启后设置仍在。</p>
     */
    public static void save() {
        try {
            if (configInstance != null) {
                configInstance.save();
            } else {
                LOGGER.warn("[mekck] config instance not available yet; migrated values will be saved by Forge later");
            }
        } catch (Throwable t) {
            // 保存失败不影响游戏运行；仅记录，便于排查
            LOGGER.warn("[mekck] could not save mekck-common.toml after migration", t);
        }
    }

    /**
     * 「创造升级」49 种食物的随机时机：{@code true} = 每次重启都重随（per_restart）；
     * {@code false} = 每个存档只随机一次（per_world，默认）。
     */
    public static boolean isCreativeUpgradePerRestart() {
        return "per_restart".equalsIgnoreCase(getCreativeUpgradeRotationMode());
    }

    /** 当前模式字符串（{@code per_world} / {@code per_restart}；非法值按默认 per_world 处理）。 */
    public static String getCreativeUpgradeRotationMode() {
        if (CREATIVE_UPGRADE_ROTATION_MODE == null) return "per_world";
        String v = CREATIVE_UPGRADE_ROTATION_MODE.get();
        return "per_restart".equalsIgnoreCase(v) ? "per_restart" : "per_world";
    }

    /** 迁移用：写入模式并落盘（写失败只记日志，不影响启动）。 */
    public static void setCreativeUpgradeRotationMode(String mode) {
        if (CREATIVE_UPGRADE_ROTATION_MODE == null) return;
        try {
            CREATIVE_UPGRADE_ROTATION_MODE.set("per_restart".equalsIgnoreCase(mode) ? "per_restart" : "per_world");
            SPEC.save();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("mekck").warn("[mekck] 写入 food_rotation_mode 失败", t);
        }
    }

    /** 是否向首位进入服务器的玩家提示本局创造升级食物已生成。 */
    public static boolean getCreativeUpgradeFoodHint() {
        return CREATIVE_UPGRADE_FOOD_HINT == null || CREATIVE_UPGRADE_FOOD_HINT.get();
    }

    /**
     * 速度升级上限：固定为 Mekanism 枚举自带值，<b>不可配置</b>。
     *
     * <p>{@code Upgrade.SPEED.getMax()} 是编译期常量 8。曾经的
     * {@code <tier>_speed_energy_max} 配置项（12 档默认 8/12/16/20/32、范围 0..64）
     * 在引入 Mekanism 升级组件后已失效——安装时会被
     * {@code TileComponentUpgrade} 卡在 {@code getMax()}，
     * 超出的部分永远装不进去，属于「配置能改但没效果」的静默失效，故删除。
     *
     * <p>本方法保留是因为仍有多个调用方（方块实体与菜单的上限显示）；
     * 那些调用点在机器体系迁移完成后会被一并移除。
     */
    public static int getFactorySpeedUpgradeMax(CuttingMachineFactoryTier tier) {
        return Upgrade.SPEED.getMax();
    }

    /** 能量升级上限：同 {@link #getFactorySpeedUpgradeMax}，固定取枚举自带值。 */
    public static int getFactoryEnergyUpgradeMax(CuttingMachineFactoryTier tier) {
        return Upgrade.ENERGY.getMax();
    }

    /** Max stack upgrades for a factory tier (0 = not supported). */
    public static int getFactoryStackUpgradeMax(CuttingMachineFactoryTier tier) {
        ForgeConfigSpec.IntValue val = FACTORY_STACK_MAX.get(tier);
        return val != null ? val.get() : getStackUpgradeDefault(tier);
    }

    // ── Ice Maker accessors ─────────────────────────────────────────

    /** 冰块命中基础冰冻伤害倍数（叠加冷萃升级后受此系数影响）。 */
    public static double getIceCubeDamageMult() {
        return ICE_CUBE_DAMAGE_MULT.get();
    }

    /** 冷萃攻击默认索敌半径（方块）。 */
    public static int getIceAttackRadius() {
        return ICE_ATTACK_RADIUS.get();
    }

    /** 冷萃攻击间隔（游戏刻）。 */
    public static int getIceAttackInterval() {
        return ICE_ATTACK_INTERVAL.get();
    }

    /** §F17 费列罗伤害倍数（直击/范围/处决统一乘算，默认 1.0）。 */
    public static double getFerreroDamageMult() {
        return FERRERO_DAMAGE_MULT.get();
    }

    /** F10 攻击增益彩虹连线渲染开关。 */
    public static boolean isBuffConnectionRenderEnabled() {
        return BUFF_CONNECTION_RENDER.get();
    }

    /** 冷萃攻击敌对生物列表默认值：全部原版敌对生物 + 靶子假人 + 冰火传说 Monster 系敌对生物。 */
    private static List<? extends String> defaultIceAttackHostileEntities() {
        return List.of(
                "minecraft:blaze",
                "minecraft:cave_spider",
                "minecraft:creeper",
                "minecraft:drowned",
                "minecraft:elder_guardian",
                "minecraft:ender_dragon",
                "minecraft:enderman",
                "minecraft:endermite",
                "minecraft:evoker",
                "minecraft:ghast",
                "minecraft:guardian",
                "minecraft:hoglin",
                "minecraft:husk",
                "minecraft:illusioner",
                "minecraft:magma_cube",
                "minecraft:phantom",
                "minecraft:piglin",
                "minecraft:piglin_brute",
                "minecraft:pillager",
                "minecraft:ravager",
                "minecraft:shulker",
                "minecraft:silverfish",
                "minecraft:skeleton",
                "minecraft:skeleton_horse",
                "minecraft:slime",
                "minecraft:spider",
                "minecraft:stray",
                "minecraft:vex",
                "minecraft:vindicator",
                "minecraft:warden",
                "minecraft:witch",
                "minecraft:wither",
                "minecraft:wither_skeleton",
                "minecraft:zoglin",
                "minecraft:zombie",
                "minecraft:zombie_horse",
                "minecraft:zombie_villager",
                "minecraft:zombified_piglin",
                "dummmmmmy:target_dummy",
                // 冰火传说：全部继承 Monster 的敌对生物（源码逐类核对）
                "iceandfire:cyclops",
                "iceandfire:dread_beast",
                "iceandfire:dread_ghoul",
                "iceandfire:dread_horse",
                "iceandfire:dread_knight",
                "iceandfire:dread_lich",
                "iceandfire:dread_scuttler",
                "iceandfire:dread_thrall",
                "iceandfire:ghost",
                "iceandfire:gorgon",
                "iceandfire:hydra",
                "iceandfire:siren",
                "iceandfire:stymphalian_bird",
                "iceandfire:troll"
        );
    }

    /** 判断实体类型是否在冷萃攻击敌对列表中（由配置文件控制）。 */
    public static boolean isIceAttackHostile(EntityType<?> type) {
        Set<ResourceLocation> set = iceAttackHostileCache;
        if (set == null) {
            set = buildIceAttackHostileSet();
            iceAttackHostileCache = set;
        }
        return set.contains(EntityType.getKey(type));
    }

    // ── Grill factory furnace recipes ────────────────────────────────

    /** 晶钛矩阵~星云塑造烧烤工厂可处理熔炉 / 烟熏炉 / 高炉配方。 */
    public static boolean isCrystalMatrixGrillFurnaceEnabled() {
        return CRYSTAL_MATRIX_GRILL_FURNACE.get();
    }

    /** 奇点创世烧烤工厂可处理熔炉 / 烟熏炉 / 高炉配方。 */
    public static boolean isSingularityGrillFurnaceEnabled() {
        return SINGULARITY_GRILL_FURNACE.get();
    }

    /** JEI 催化剂显示开关（晶钛矩阵以上烧烤工厂 → 熔炉 / 烟熏炉 / 高炉配方）。 */
    public static boolean isGrillFurnaceJeiCatalystEnabled() {
        return GRILL_FURNACE_JEI_CATALYST.get();
    }

    /** 多方块机器放置预览开关。 */
    public static boolean isMultiblockPreviewEnabled() {
        return MULTIBLOCK_PREVIEW.get();
    }

    /** 单方块机器放置预览开关。 */
    public static boolean isSingleBlockPreviewEnabled() {
        return SINGLE_BLOCK_PREVIEW.get();
    }

    /** 其他模组机器放置预览开关。 */
    public static boolean isOtherModsMachinePreviewEnabled() {
        return OTHER_MODS_MACHINE_PREVIEW.get();
    }

    /** 半透明机器模型预览开关。 */
    public static boolean isTranslucentModelPreviewEnabled() {
        return PREVIEW_TRANSLUCENT.get();
    }

    /** 模型线框预览开关。 */
    public static boolean isWireframePreviewEnabled() {
        return PREVIEW_WIREFRAME.get();
    }

    /** 包围盒线框预览开关。 */
    public static boolean isBoxPreviewEnabled() {
        return PREVIEW_BOX.get();
    }

    /** 包围盒线框粗细倍数（0.5~3.0）。 */
    public static float getBoxLineWidth() {
        return (float) (double) BOX_LINE_WIDTH.get();
    }

    /** 半透明模型透明度（0.05~1.0）。 */
    public static float getTranslucentModelAlpha() {
        return (float) (double) PREVIEW_TRANSLUCENT_ALPHA.get();
    }

    private static Set<ResourceLocation> buildIceAttackHostileSet() {
        Set<ResourceLocation> set = new HashSet<>();
        for (String entry : ICE_ATTACK_HOSTILE_ENTITIES.get()) {
            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (id != null) {
                set.add(id);
            } else {
                LOGGER.warn("冷萃攻击敌对生物列表配置项无效，已忽略：{}", entry);
            }
        }
        // 自动扫描：全部继承 Monster 或实现 Enemy 的注册实体（即原版/模组的「敌对生物」判据）
        // 视为敌对并入名单；ICE_ATTACK_AUTO_SCAN 关闭时仅用配置列表（保留手动剔除能力）。
        if (ICE_ATTACK_AUTO_SCAN.get()) {
            int scanned = 0;
            for (EntityType<?> type : net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES) {
                Class<? extends net.minecraft.world.entity.Entity> base = type.getBaseClass();
                if (net.minecraft.world.entity.monster.Monster.class.isAssignableFrom(base)
                        || net.minecraft.world.entity.monster.Enemy.class.isAssignableFrom(base)) {
                    ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
                    if (id != null && set.add(id)) {
                        scanned++;
                    }
                }
            }
            LOGGER.info("冷萃攻击敌对名单：配置 {} 项 + 自动扫描新增 {} 项 = 共 {} 项",
                    ICE_ATTACK_HOSTILE_ENTITIES.get().size(), scanned, set.size());
        }
        return set;
    }

    // ── Bioreactor non-consumable (eternal) foods ─────────────────────

    /** 判断物品是否为「可接受不消耗」食物（由配置文件 eternal_foods 控制）。 */
    public static boolean isBioreactorEternalFood(net.minecraft.world.item.Item item) {
        Map<ResourceLocation, Integer> map = bioreactorEternalFoodsCache;
        if (map == null) {
            map = buildBioreactorEternalFoodsMap();
            bioreactorEternalFoodsCache = map;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return id != null && map.containsKey(id);
    }

    /**
     * 「可接受不消耗」食物的自定义每单位 mb（条目写作 注册名=mb 时）；
     * 返回 null 表示未自定义，调用方应回退到默认固定转换量。
     */
    @Nullable
    public static Integer getBioreactorEternalFoodMb(net.minecraft.world.item.Item item) {
        Map<ResourceLocation, Integer> map = bioreactorEternalFoodsCache;
        if (map == null) {
            map = buildBioreactorEternalFoodsMap();
            bioreactorEternalFoodsCache = map;
        }
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item);
        return id == null ? null : map.get(id);
    }

    private static Map<ResourceLocation, Integer> buildBioreactorEternalFoodsMap() {
        Map<ResourceLocation, Integer> map = new java.util.HashMap<>();
        for (String entry : BIOREACTOR_ETERNAL_FOODS.get()) {
            String s = entry.trim();
            int eq = s.indexOf('=');
            String idPart = eq <= 0 ? s : s.substring(0, eq).trim();
            ResourceLocation id = ResourceLocation.tryParse(idPart);
            if (id == null) {
                LOGGER.warn("生物反应堆不消耗食物列表配置项无效，已忽略：{}", entry);
                continue;
            }
            Integer mb = null;
            if (eq > 0) {
                try {
                    mb = Math.max(1, Integer.parseInt(s.substring(eq + 1).trim()));
                } catch (NumberFormatException e) {
                    LOGGER.warn("生物反应堆不消耗食物列表 mb 无效，改用默认 10000：{}", entry);
                }
            }
            map.put(id, mb);
        }
        return map;
    }

    // ── Reload hook ────────────────────────────────────────────────────

    /** 已加载的 ModConfig 实例；迁移旧 JSON 后用它显式落盘（见 {@link #save()}）。 */
    private static net.minecraftforge.fml.config.ModConfig configInstance;

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        configInstance = event.getConfig();
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        configInstance = event.getConfig();
        // 配置值从 spec 实时读取，仅需清空缓存的解析结果。
        iceAttackHostileCache = null;
        bioreactorEternalFoodsCache = null;
    }
}