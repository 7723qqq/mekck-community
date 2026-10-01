package cn.ism.mekck.registry;

import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.RegistryObject;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkRegistries.RECIPE_SERIALIZERS;
import static cn.ism.mekck.registry.MekCkRegistries.RECIPE_TYPES;

/**
 * 配方类型与序列化器（mekck 自有 recipe type）。
 *
 * <p>本类由 {@code UniversalCuttingMachine} 拆出（注册中枢拆分）。成员文本与拆分前
 * 逐字一致；本类必须<b>早于任何注册事件</b>被触碰一次 —— 见 {@link #init()}。</p>
 */
public final class MekCkRecipeTypes {

    private MekCkRecipeTypes() {
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

    public static final RegistryObject<RecipeType<PlantingCuttingRecipe>> PLANTING_CUTTING_RECIPE_TYPE = RECIPE_TYPES.register("plantcut",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "plantcut")));

    public static final RegistryObject<RecipeSerializer<PlantingCuttingRecipe>> PLANTING_CUTTING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("plantcut",
            PlantingCuttingRecipe.Serializer::new);

    // Ice Make recipe (急冻制冰机 / 制冰工厂)

    public static final RegistryObject<RecipeType<IceMakeRecipe>> ICE_MAKE_RECIPE_TYPE = RECIPE_TYPES.register("ice_make",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "ice_make")));

    public static final RegistryObject<RecipeSerializer<IceMakeRecipe>> ICE_MAKE_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("ice_make",
            IceMakeRecipe.Serializer::new);

    // Ferrero recipe (巧克力大炮：物品+extra+2流体 → 费列罗巧克力)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.FerreroRecipe>> FERRERO_RECIPE_TYPE = RECIPE_TYPES.register("ferrero",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "ferrero")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.FerreroRecipe>> FERRERO_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("ferrero",
            cn.ism.mekck.recipe.FerreroRecipe.Serializer::new);

    // Nut Roasting recipe (坚果爆炒机：1 输入 → 1 输出)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.NutRoastingRecipe>> NUT_ROASTING_RECIPE_TYPE = RECIPE_TYPES.register("nut_roasting",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "nut_roasting")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.NutRoastingRecipe>> NUT_ROASTING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("nut_roasting",
            cn.ism.mekck.recipe.NutRoastingRecipe.Serializer::new);

    // Grinding recipe (§F19 D 半：电力研磨机磨粉，单入单出，照 nut_roasting 模板)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.GrindingRecipe>> GRINDING_RECIPE_TYPE = RECIPE_TYPES.register("grinding",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "grinding")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.GrindingRecipe>> GRINDING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("grinding",
            cn.ism.mekck.recipe.GrindingRecipe.Serializer::new);

    // Extracting recipe (§F19 C+E 半：智能萃取机专属，多物品+可选流体 → 流体|物品 双产物形态二选一)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.ExtractingRecipe>> EXTRACTING_RECIPE_TYPE = RECIPE_TYPES.register("extracting",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "extracting")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.ExtractingRecipe>> EXTRACTING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("extracting",
            cn.ism.mekck.recipe.ExtractingRecipe.Serializer::new);

    // Beverage assembly recipe (饮品调配机 F11 §四.2：杯 + 可选小料 + 饮品流体 → 杯装饮品)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.BeverageAssemblyRecipe>> BEVERAGE_ASSEMBLY_RECIPE_TYPE = RECIPE_TYPES.register("beverage_assembly",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "beverage_assembly")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.BeverageAssemblyRecipe>> BEVERAGE_ASSEMBLY_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("beverage_assembly",
            cn.ism.mekck.recipe.BeverageAssemblyRecipe.Serializer::new);

    // Packaging recipe (包材组装机 F7 §四.4：若干输入材料 → 1 个空容器/包材，无流体)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.PackagingRecipe>> PACKAGING_RECIPE_TYPE = RECIPE_TYPES.register("packaging",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "packaging")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.PackagingRecipe>> PACKAGING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("packaging",
            cn.ism.mekck.recipe.PackagingRecipe.Serializer::new);

    // Grape pressing recipe (鲜果榨汁机 葡萄压榨：N 份葡萄 + 空葡萄酒瓶 → 瓶装葡萄汁，纯物品无流体)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.GrapePressingRecipe>> GRAPE_PRESSING_RECIPE_TYPE = RECIPE_TYPES.register("grape_pressing",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "grape_pressing")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.GrapePressingRecipe>> GRAPE_PRESSING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("grape_pressing",
            cn.ism.mekck.recipe.GrapePressingRecipe.Serializer::new);

    // Skewering recipe (串烧工厂：主料 + 辅料 + 签子 → 串烧物，签子不消耗)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.MekCkSkeweringRecipe>> SKEWERING_RECIPE_TYPE = RECIPE_TYPES.register("skewering",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "skewering")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.MekCkSkeweringRecipe>> SKEWERING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("skewering",
            cn.ism.mekck.recipe.MekCkSkeweringRecipe.Serializer::new);

    // Grilling recipe (烧烤工厂自有：单输入 → 烤制产物，产物为独立的"烤"变体而非复用熟肉)

    public static final RegistryObject<RecipeType<cn.ism.mekck.recipe.MekCkGrillingRecipe>> GRILLING_RECIPE_TYPE = RECIPE_TYPES.register("grilling",
            () -> RecipeType.simple(new ResourceLocation(MOD_ID, "grilling")));

    public static final RegistryObject<RecipeSerializer<cn.ism.mekck.recipe.MekCkGrillingRecipe>> GRILLING_RECIPE_SERIALIZER = RECIPE_SERIALIZERS.register("grilling",
            cn.ism.mekck.recipe.MekCkGrillingRecipe.Serializer::new);

    // 状态效果（制冰攻击体系）：冰冻（原生冰封）/ 失温 / 永冻
}
