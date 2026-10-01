package cn.ism.mekck.registry;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.registry.MekCkRegistries.ITEMS;

/**
 * 不依附方块的物品：指南手册、升级卡、工厂安装器、原子刀、食材与串烧/烤制产物。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkItems {

    private MekCkItems() {
    }

    /**
     * 触碰式初始化：由 {@code UniversalCuttingMachine} 的构造器调用。
     *
     * <h3>为什么需要它</h3>
     * 条目是在本类的<b>静态初始化器</b>里加进
     * {@link cn.ism.mekck.registry.MekCkRegistries} 的 {@code DeferredRegister} 的，
     * 而 {@code DeferredRegister} 是在 {@code register(bus)} 时挂上注册事件监听器、
     * 事件触发时才去读那张表。若本类因「谁都没引用」而晚于注册事件才初始化，
     * 它的条目会<b>静默地一个都不注册</b> —— 方块放下去变空气、菜单取不到，
     * 而且没有任何报错。所以构造器必须显式触碰每一个注册类，
     * 而不是依赖「反正会被引用到」。
     */
    public static void init() {
    }

    public static final RegistryObject<Item> GUIDE_HANDBOOK_ITEM = ITEMS.register("guide_handbook",
            () -> new cn.ism.mekck.item.GuideHandbookItem(new Item.Properties().stacksTo(1)));

    /** 存储升级卡（mekck:upgrade_storage）：提升并行线程数与缓冲容量，{@code Upgrade.getMax()} 为 6。 */

    public static final RegistryObject<Item> STORAGE_UPGRADE_ITEM = ITEMS.register("upgrade_storage",
            () -> new cn.ism.mekck.upgrade.MekCkStorageUpgradeItem(new Item.Properties().stacksTo(64)));
    /** 随机化升级卡（mekck:upgrade_randomize）：随机化本局 49 种可用食物，{@code Upgrade.getMax()} 为 1。 */

    public static final RegistryObject<Item> RANDOMIZE_UPGRADE_ITEM = ITEMS.register("upgrade_randomize",
            () -> new cn.ism.mekck.upgrade.MekCkRandomizeUpgradeItem(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<Item> BLAZE_TIER_INSTALLER_ITEM = ITEMS.register(
            "blaze_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.BLAZE, false));

    public static final RegistryObject<Item> CRYSTAL_MATRIX_TIER_INSTALLER_ITEM = ITEMS.register(
            "crystal_matrix_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.CRYSTAL_MATRIX, false));

    public static final RegistryObject<Item> NEBULA_TIER_INSTALLER_ITEM = ITEMS.register(
            "nebula_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.NEBULA, false));

    public static final RegistryObject<Item> SINGULARITY_TIER_INSTALLER_ITEM = ITEMS.register(
            "singularity_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.SINGULARITY, true));


    static {
        cn.ism.mekck.item.ColdBrewUpgradeItem.registerAll(ITEMS);
        // 费列罗升级物品（巧克力大炮专用，5 种）
        cn.ism.mekck.item.FerreroUpgradeItem.registerAll(ITEMS);
    }

    // 原子刀（part2 移植）：Mekanism 能量驱动的农夫乐事刀具（刀具挖掘/收获/砧板处理）

    public static final RegistryObject<Item> ATOMIC_KNIFE = ITEMS.register("atomic_knife",
            () -> new cn.ism.mekck.item.ItemAtomicKnife(new Item.Properties()));

    // 榛子（坚果爆炒机原料与费列罗配方输入，饥饿值 1 / 饱和度 0.5）、炒榛子（饥饿值 2 / 饱和度 1）与费列罗巧克力（产物 / 弹药）

    public static final RegistryObject<Item> HAZELNUT_ITEM = ITEMS.register("hazelnut",
            () -> new Item(new Item.Properties().food(new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(1).saturationMod(0.25F).build())));

    public static final RegistryObject<Item> ROASTED_HAZELNUT_ITEM = ITEMS.register("roasted_hazelnut",
            () -> new Item(new Item.Properties().food(new net.minecraft.world.food.FoodProperties.Builder()
                    .nutrition(2).saturationMod(0.25F).build())));

    public static final RegistryObject<Item> FERRERO_CHOCOLATE_ITEM = ITEMS.register("ferrero_chocolate", () -> new Item(new Item.Properties()));

    // 榛子粉（§F19 D 半：炒榛子研磨产物，纯中间品、不作食物、无其它用途）

    public static final RegistryObject<Item> HAZELNUT_POWDER_ITEM = ITEMS.register("hazelnut_powder", () -> new Item(new Item.Properties()));

    // ── 串烧物（mekck:skewering 的产物，签子已随主料烤入）─────────────────
    // 刻意<b>不</b>设 food 属性：营养值需要与主料/辅料逐条对齐才算平衡，
    // 那是内容设计的活，不在机制落地里编。串烧工厂的价值是并行处理 + 签子返还。

    public static final RegistryObject<Item> BEEF_SKEWER_ITEM = ITEMS.register("beef_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> PORK_SKEWER_ITEM = ITEMS.register("pork_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> CHICKEN_SKEWER_ITEM = ITEMS.register("chicken_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> MUTTON_SKEWER_ITEM = ITEMS.register("mutton_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> FISH_SKEWER_ITEM = ITEMS.register("fish_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> VEGETABLE_SKEWER_ITEM = ITEMS.register("vegetable_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> MUSHROOM_SKEWER_ITEM = ITEMS.register("mushroom_skewer", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> SWEETBERRY_SKEWER_ITEM = ITEMS.register("sweetberry_skewer", () -> new Item(new Item.Properties()));

    // ── 烤制产物（mekck:grilling 的产物，与烟熏炉的"熟"区分开的"烤"线）──────
    // 同样刻意不设 food 属性：营养值要与主料逐条对齐才算平衡，属内容设计。

    public static final RegistryObject<Item> GRILLED_BEEF_ITEM = ITEMS.register("grilled_beef", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_PORK_ITEM = ITEMS.register("grilled_pork", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_CHICKEN_ITEM = ITEMS.register("grilled_chicken", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_MUTTON_ITEM = ITEMS.register("grilled_mutton", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_COD_ITEM = ITEMS.register("grilled_cod", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_SALMON_ITEM = ITEMS.register("grilled_salmon", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_CARROT_ITEM = ITEMS.register("grilled_carrot", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> GRILLED_MUSHROOM_ITEM = ITEMS.register("grilled_mushroom", () -> new Item(new Item.Properties()));

    /**
     * 费列罗**发射物**的渲染载体（隐藏物品：无配方、不进创造标签页）。
     *
     * <p>物品栏里的费列罗带金箔纸，而打出去的实体应当显示"裸巧克力球" ——
     * {@code FerreroRenderer extends ThrownItemRenderer} 渲染的永远是实体返回的那个物品
     * （{@code FerreroEntity#getDefaultItem()}），所以必须给它一个单独的物品来承载裸球贴图。
     * 资源已就绪：{@code textures/item/ferrero_projectile.png} + {@code models/item/ferrero_projectile.json}。</p>
     */

    public static final RegistryObject<Item> FERRERO_PROJECTILE_ITEM =
            ITEMS.register("ferrero_projectile", () -> new Item(new Item.Properties()));

    // 榛子可可酱流体（费列罗配方流体输入 2）：流体类型 + 静止/流动流体 + 世界液体方块 + 桶

    /**
     * 创造模式「功能方块」标签页里，<b>本注册类自己的</b>条目。
     *
     * <p>原先这段清单整个写在 {@code UniversalCuttingMachine#addCreativeTabContents} 里
     * —— 81 行里交织着 6 个归属的 accept 调用。新增机器时既要改注册、
     * 还要记得回头改这个清单，而清单里没人会提醒你。</p>
     *
     * <p>调用方已确认 tab key（见 {@code UniversalCuttingMachine}）。</p>
     */
    public static void addToCreativeTab(net.minecraftforge.event.BuildCreativeModeTabContentsEvent event) {
        event.accept(GUIDE_HANDBOOK_ITEM);
        event.accept(BLAZE_TIER_INSTALLER_ITEM.get());
        event.accept(CRYSTAL_MATRIX_TIER_INSTALLER_ITEM.get());
        event.accept(NEBULA_TIER_INSTALLER_ITEM.get());
        event.accept(SINGULARITY_TIER_INSTALLER_ITEM.get());
        event.accept(ROASTED_HAZELNUT_ITEM.get());
        for (RegistryObject<Item> coldBrewItem : ColdBrewUpgradeItem.REGISTRY.values()) {
            event.accept(coldBrewItem.get());
        }
        for (RegistryObject<Item> ferreroItem : cn.ism.mekck.item.FerreroUpgradeItem.REGISTRY.values()) {
            event.accept(ferreroItem.get());
        }
        event.accept(HAZELNUT_ITEM.get());
        event.accept(FERRERO_CHOCOLATE_ITEM.get());
        event.accept(HAZELNUT_POWDER_ITEM.get());
        event.accept(ATOMIC_KNIFE.get());
    }
}
