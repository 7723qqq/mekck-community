package cn.ism.mekck.kitchen;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 中央厨房的「机器系列」定义。
 *
 * <p>系列**按配方类型集合划分**：配方类型重叠的机器归入同一系列（例如平均切段机与万能切菜机
 * 都执行 farmersdelight:cutting，同属 CUTTING 系列）。同一系列在中央厨房中只能安装一个模块。</p>
 *
 * <p>构造参数：id / 图标物品（系列的基础机器）/ 配方类型 / 是否产热 / 是否制冷 / 工厂注册名后缀。</p>
 */
public enum KitchenFamily {

    CUTTING("cutting", "mekck:universal_cutting_machine",
            List.of("farmersdelight:cutting"), false, false, "cutting"),

    COOKING("cooking", "mekck:smart_cooking_pot",
            List.of("farmersdelight:cooking", "kaleidoscope_cookery:pot",
                    "kaleidoscope_cookery:stockpot", "youkaishomecoming:unordered_cooking"),
            false, false, "cooking"),

    SKEWERING("skewering", "mekck:smart_skewering_machine",
            List.of("barbequesdelight:skewering"), false, false, "skewering"),

    GRILLING("grilling", "mekck:electric_grill",
            List.of("barbequesdelight:grilling"), false, false, "grill"),

    GRINDING("grinding", "mekck:electric_grinding_machine",
            List.of("kaleidoscope_cookery:millstone", "bakeries:flour_sieve",
                    "farm_and_charm:mincer"), false, false, "grinding"),

    PLANTING("planting", "mekck:planting_cutting_station",
            List.of("mekck:plantcut"), false, false, "planting_cutting"),

    ICE("ice", "mekck:ice_maker",
            List.of("mekck:ice_make"), false, true, "ice"),

    SUSHI("sushi", "mekck:sushi_maker",
            List.of("youkaishomecoming:cuisine_ordered", "youkaishomecoming:cuisine_mixed",
                    "youkaishomecoming:cuisine_fixed"), false, false, null),

    FERMENTING("fermenting", "mekck:fermenter",
            List.of("youkaishomecoming:simple_fermentation", "bakeries:fermentation",
                    "brewery:brewing", "drinkbeer:brewing"), false, false, null),

    DEHYDRATING("dehydrating", "mekck:dehydrator",
            List.of("youkaishomecoming:drying_rack", "farm_and_charm:drying"), false, false, null),

    STEAMING("steaming", "mekck:steamer",
            List.of("youkaishomecoming:steaming"), true, false, null),

    AGING("aging", "mekck:winery",
            List.of("vinery:wine_fermentation", "kaleidoscope_tavern:barrel"), false, false, null),

    JUICING("juicing", "mekck:juicer",
            List.of("kaleidoscope_tavern:pressing_tub", "mekck:grape_pressing"), false, false, null),

    BAKING("baking", "mekck:bakery_oven",
            List.of("bakery:baking_station", "bakeries:oven", "bakeries:coffee"), true, false, null),

    STOVE("stove", "mekck:stove",
            List.of("farm_and_charm:stove", "farm_and_charm:roaster", "bakeries:stone_kiln",
                    "herbalbrews:kettle_brewing", "meadow:cooking"), true, false, null),

    SHAKING("shaking", "mekck:cocktail_shaker",
            List.of("kaleidoscope_tavern:shaker"), false, false, null),

    /**
     * 三明治系列：**没有配方类型**——三明治在 Some Assembly Required 中是「有序材料列表」的 NBT 组合，
     * 不是数据包配方。因此本系列由中央厨房的**样品槽**特殊处理（放一个三明治样品即可量产同款）。
     */
    SANDWICH("sandwich", "mekck:sandwich_assembler",
            List.of(), false, false, null),

    BLENDING("blending", "mekck:blender",
            List.of("bakeries:blender"), false, false, null);

    public final String id;
    /** 系列图标（基础机器的注册名）。 */
    public final String iconItem;
    /** 该系列可执行的配方类型。 */
    public final List<String> recipeTypes;
    /** 运行是否产生热量（产热系列，走发热侧温度）。 */
    public final boolean heatProducing;
    /** 运行是否需要制冷（制冷系列，走制冷侧温度）。 */
    public final boolean cooling;
    /** 工厂注册名的系列后缀（`mekck:{等级}_{后缀}_factory`）；无工厂版本的系列为 null。 */
    public final String factorySuffix;

    KitchenFamily(String id, String iconItem, List<String> recipeTypes,
                  boolean heatProducing, boolean cooling, String factorySuffix) {
        this.id = id;
        this.iconItem = iconItem;
        this.recipeTypes = recipeTypes;
        this.heatProducing = heatProducing;
        this.cooling = cooling;
        this.factorySuffix = factorySuffix;
    }

    /**
     * 该系列是否处理某个**没有原版配方类型**的虚拟配方 id。
     *
     * <p>森罗物语：烟火把配方数据放在自己的 GrillingDataManager 里，只暴露虚拟配方
     * （id 形如 {@code kaleidoscope_grilling:grilling/xxx} 与 {@code ...:threading/xxx}）。
     * 这里按 id 前缀把它们分别归入烧烤系列与穿串系列。</p>
     */
    public boolean handlesVirtualId(ResourceLocation id) {
        if (id == null || !cn.ism.mekck.util.KaleidoscopeGrillingCompat.MOD_ID.equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return switch (this) {
            case GRILLING -> path.startsWith("grilling/");
            case SKEWERING -> path.startsWith("threading/");
            default -> false;
        };
    }

    /** 该配方类型是否属于本系列。 */
    public boolean handles(ResourceLocation recipeType) {
        return recipeType != null && recipeTypes.contains(recipeType.toString());
    }

    /** 图标物品栈（未注册时返回空栈）。 */
    public net.minecraft.world.item.ItemStack icon() {
        var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation(iconItem));
        return item == null ? net.minecraft.world.item.ItemStack.EMPTY
                : new net.minecraft.world.item.ItemStack(item);
    }

    /** 由基础机器注册名反查系列；未匹配返回 null。 */
    public static KitchenFamily byIconItem(String itemId) {
        for (KitchenFamily f : values()) {
            if (f.iconItem.equals(itemId)) return f;
        }
        return null;
    }
}
