package cn.ism.mekck.integration.jei;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.integration.jei.BioreactorJeiRecipe;
import cn.ism.mekck.integration.jei.BioreactorRecipeCategory;
import cn.ism.mekck.integration.jei.IceMakeRecipeCategory;
import cn.ism.mekck.integration.jei.PlantingCuttingRecipeCategory;
import cn.ism.mekck.recipe.FerreroRecipe;
import cn.ism.mekck.recipe.IceMakeRecipe;
import cn.ism.mekck.recipe.NutRoastingRecipe;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import cn.ism.mekck.recipe.BioreactorFuels;
import cn.ism.mekck.compat.KaleidoscopeCompat;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;

import java.util.ArrayList;
import java.util.List;
import cn.ism.mekck.registry.MekCkFactories;
import cn.ism.mekck.registry.MekCkLegacyMachines;
import cn.ism.mekck.registry.MekCkRecipeTypes;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import static cn.ism.mekck.registry.MekCkFactories.FACTORY_BLOCKS;

@JeiPlugin
public class JEIPlugin implements IModPlugin {
    private static final ResourceLocation ID = new ResourceLocation("mekck", "jei_plugin");

    // Reuse Farmer's Delight's recipe types by using the same ResourceLocation
    public static final RecipeType<CuttingBoardRecipe> CUTTING_TYPE =
            RecipeType.create("farmersdelight", "cutting", CuttingBoardRecipe.class);
    public static final RecipeType<CookingPotRecipe> COOKING_TYPE =
            RecipeType.create("farmersdelight", "cooking", CookingPotRecipe.class);

    @SuppressWarnings("unchecked")
    public static final RecipeType<Recipe<?>> EXTREME_COOKING = RecipeType.create(
            "avaritia_delight",
            "extreme_cooking",
            (Class) Recipe.class
    );

    // BarbequesDelight recipe types - use Class.forName to match the exact recipe class
    // used by the category, because JEI's RecipeType.equals() checks both UID and recipe class
    private static final RecipeType<?> SKEWERING_TYPE = createRecipeType(
            "barbequesdelight", "skewering", "com.mao.barbequesdelight.content.recipe.SimpleSkeweringRecipe");
    private static final RecipeType<?> GRILLING_TYPE = createRecipeType(
            "barbequesdelight", "grilling", "com.mao.barbequesdelight.content.recipe.SimpleGrillingRecipe");

    // MekCK: PlantCut recipe type - our own recipe type
    public static final RecipeType<PlantingCuttingRecipe> PLANT_CUT_TYPE =
            RecipeType.create("mekck", "plantcut", PlantingCuttingRecipe.class);

    // MekCK: Wine Cellar (陈化窖 F20) 介绍页——纯容器、无配方，只为在 JEI 挂一页说明
    public static final RecipeType<WineCellarInfoRecipe> WINE_CELLAR_TYPE =
            RecipeType.create("mekck", "wine_cellar", WineCellarInfoRecipe.class);

    // MekCK: Bioreactor recipe type - item -> organic matter fluid (per-unit mb)
    public static final RecipeType<BioreactorJeiRecipe> BIOREACTOR_TYPE =
            RecipeType.create("mekck", "bioreactor", BioreactorJeiRecipe.class);

    // MekCK: Ice Make recipe type - 急冻制冰机 / 制冰工厂
    public static final RecipeType<IceMakeRecipe> ICE_MAKE_TYPE =
            RecipeType.create("mekck", "ice_make", IceMakeRecipe.class);

    // MekCK: Ferrero recipe type - 巧克力大炮
    public static final RecipeType<FerreroRecipe> FERRERO_TYPE =
            RecipeType.create("mekck", "ferrero", FerreroRecipe.class);

    // MekCK: Nut Roasting recipe type - 坚果爆炒机
    public static final RecipeType<NutRoastingRecipe> NUT_ROASTING_TYPE =
            RecipeType.create("mekck", "nut_roasting", NutRoastingRecipe.class);

    // MekCK: Grinding recipe type - 电力研磨机磨粉（§F19 D 半）
    public static final RecipeType<cn.ism.mekck.recipe.GrindingRecipe> GRINDING_TYPE =
            RecipeType.create("mekck", "grinding", cn.ism.mekck.recipe.GrindingRecipe.class);

    // MekCK: Extracting recipe type - 智能萃取机（§F19 C+E 半，流体|物品双产物形态）
    public static final RecipeType<cn.ism.mekck.recipe.ExtractingRecipe> EXTRACTING_TYPE =
            RecipeType.create("mekck", "extracting", cn.ism.mekck.recipe.ExtractingRecipe.class);

    // MekCK: Beverage assembly recipe type - 饮品调配机（F11 §四.2）
    public static final RecipeType<cn.ism.mekck.recipe.BeverageAssemblyRecipe> BEVERAGE_ASSEMBLY_TYPE =
            RecipeType.create("mekck", "beverage_assembly", cn.ism.mekck.recipe.BeverageAssemblyRecipe.class);

    // MekCK: Packaging recipe type - 包材组装机（F7 / F11 §四.4）
    public static final RecipeType<cn.ism.mekck.recipe.PackagingRecipe> PACKAGING_TYPE =
            RecipeType.create("mekck", "packaging", cn.ism.mekck.recipe.PackagingRecipe.class);

    // MekCK: Grape pressing recipe type - 鲜果榨汁机葡萄压榨（简报需求1）
    public static final RecipeType<cn.ism.mekck.recipe.GrapePressingRecipe> GRAPE_PRESSING_TYPE =
            RecipeType.create("mekck", "grape_pressing", cn.ism.mekck.recipe.GrapePressingRecipe.class);

    // MekCK: 自有配方类型 mekck:skewering / mekck:grilling 的 **JEI** RecipeType。
    // ⚠️ 这两个是 mezz.jei.api.recipe.RecipeType，与
    // MekCkRecipeTypes.SKEWERING_RECIPE_TYPE（原版 net.minecraft...RecipeType）
    // 是**两个不同的类**，不能互相赋值。UID 与 path 必须与注册表里那个逐字相同
    // （"mekck" / "skewering" / "grilling"），配方类必须写成真实的配方类——
    // JEI 的 RecipeType.equals 同时比 UID 与配方类，写成 Recipe.class 就配不上分类。
    public static final RecipeType<cn.ism.mekck.recipe.MekCkSkeweringRecipe> MEKCK_SKEWERING_TYPE =
            RecipeType.create("mekck", "skewering", cn.ism.mekck.recipe.MekCkSkeweringRecipe.class);
    public static final RecipeType<cn.ism.mekck.recipe.MekCkGrillingRecipe> MEKCK_GRILLING_TYPE =
            RecipeType.create("mekck", "grilling", cn.ism.mekck.recipe.MekCkGrillingRecipe.class);

    // KaleidoscopeCookery recipe types (pot/stockpot and their flex variants)
    private static final RecipeType<?> KC_STOCKPOT = createRecipeType(
            "kaleidoscope_cookery", "stockpot", "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe");
    private static final RecipeType<?> KC_FLEX_STOCKPOT = createRecipeType(
            "kaleidoscope_cookery", "flex_stockpot", "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe");
    private static final RecipeType<?> KC_POT = createRecipeType(
            "kaleidoscope_cookery", "pot", "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe");
    private static final RecipeType<?> KC_FLEX_POT = createRecipeType(
            "kaleidoscope_cookery", "flex_pot", "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe");
    private static final RecipeType<?> KC_MILLSTONE = createRecipeType(
            "kaleidoscope_cookery", "millstone", "com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe");

    // KaleidoscopeGrilling JEI 分类（自定义 wrapper 配方的标准 JEI RecipeType）
    private static final RecipeType<?> KG_GRILLING = createRecipeType(
            "kaleidoscope_grilling", "grilling", "cn.breezeth.kaleidoscope_grilling.jei.GrillingJeiRecipes$Grilling");
    private static final RecipeType<?> KG_THREADING = createRecipeType(
            "kaleidoscope_grilling", "threading", "cn.breezeth.kaleidoscope_grilling.jei.GrillingJeiRecipes$Threading");
    private static final RecipeType<?> KG_SECRET_THREADING = createRecipeType(
            "kaleidoscope_grilling", "secret_threading", "cn.breezeth.kaleidoscope_grilling.jei.GrillingJeiRecipes$Threading");
    private static final RecipeType<?> KG_SEASONING = createRecipeType(
            "kaleidoscope_grilling", "seasoning", "cn.breezeth.kaleidoscope_grilling.jei.GrillingJeiRecipes$Seasoning");

    // ── SimpleMachine 11 台机器的对应配方分类（JEI RecipeType，UID+class 与各 mod 分类一致）──
    // 妖怪们的归家（youkaishomecoming）：料理台 / 晾晒架 / 蒸笼 / 发酵
    private static final RecipeType<?> YH_CUISINE = createRecipeType(
            "youkaishomecoming", "cuisine", "dev.xkmc.youkaishomecoming.content.pot.table.recipe.CuisineRecipe");
    private static final RecipeType<?> YH_RACK = createRecipeType(
            "youkaishomecoming", "drying_rack", "dev.xkmc.youkaishomecoming.content.pot.rack.DryingRackRecipe");
    private static final RecipeType<?> YH_STEAM = createRecipeType(
            "youkaishomecoming", "steaming", "dev.xkmc.youkaishomecoming.content.pot.steamer.SteamingRecipe");
    private static final RecipeType<?> YH_FERMENT = createRecipeType(
            "youkaishomecoming", "ferment", "dev.xkmc.youkaishomecoming.content.pot.ferment.SimpleFermentationRecipe");
    // 葡园酒香（vinery）：橡木桶发酵 / 榨汁（JEI 分类用 apple_press_* UID）
    private static final RecipeType<?> VINERY_WINE = createRecipeType(
            "vinery", "wine_fermentation", "net.satisfy.vinery.core.recipe.FermentationBarrelRecipe");
    private static final RecipeType<?> VINERY_MASH = createRecipeType(
            "vinery", "apple_press_mashing", "net.satisfy.vinery.core.recipe.ApplePressMashingRecipe");
    private static final RecipeType<?> VINERY_FERMENT = createRecipeType(
            "vinery", "apple_press_fermenting", "net.satisfy.vinery.core.recipe.ApplePressFermentingRecipe");
    // 馥郁烘焙（bakery）：烤箱（JEI 分类 UID 为 caking）
    private static final RecipeType<?> BAKERY_CAKING = createRecipeType(
            "bakery", "caking", "net.satisfy.bakery.core.recipe.BakingStationRecipe");
    // 沉浸农艺（farm_and_charm）：灶台
    private static final RecipeType<?> FAC_STOVE = createRecipeType(
            "farm_and_charm", "stove", "net.satisfy.farm_and_charm.core.recipe.StoveRecipe");
    // 烘焙坊（bakeries）：烤箱（JEI 分类 UID 为 oven_recipe）
    private static final RecipeType<?> BAKERIES_OVEN = createRecipeType(
            "bakeries", "oven_recipe", "com.renyigesai.bakeries.recipe.oven.OvenRecipe");
    // 酒馆（kaleidoscope_tavern）：调酒（shaker）
    private static final RecipeType<?> TAVERN_SHAKER = createRecipeType(
            "kaleidoscope_tavern", "shaker", "com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.ShakerRecipe");
    // 酒馆：酒桶（barrel）/ 榨汁（pressing_tub）——与 tavern 自带 mezz 分类同 UID+同类名，催化剂挂上去即复用其分类（简报需求4 §4.1/§4.2）
    private static final RecipeType<?> TAVERN_BARREL = createRecipeType(
            "kaleidoscope_tavern", "barrel", "com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.BarrelRecipe");
    private static final RecipeType<?> TAVERN_PRESSING = createRecipeType(
            "kaleidoscope_tavern", "pressing_tub", "com.github.ysbbbbbb.kaleidoscopetavern.crafting.recipe.PressingTubRecipe");
    // 烘焙坊（bakeries）：咖啡（drink）与石窑（stone_kiln）
    private static final RecipeType<?> BAKERIES_DRINK = createRecipeType(
            "bakeries", "drink", "com.renyigesai.bakeries.recipe.CoffeeRecipe");
    private static final RecipeType<?> BAKERIES_STONE_KILN = createRecipeType(
            "bakeries", "stone_kiln", "com.renyigesai.bakeries.recipe.StoneKilnRecipe");
    private static final RecipeType<?> BAKERIES_BLENDER = createRecipeType(
            "bakeries", "blender", "com.renyigesai.bakeries.recipe.BlenderRecipe");
    private static final RecipeType<?> BAKERIES_FLOUR_SIEVE = createRecipeType(
            "bakeries", "flour_sieve", "com.renyigesai.bakeries.recipe.flour_sieve.FlourSieveRecipe");
    // let's do：沉浸农艺绞碎（mincer）、盛节精酿酿造（brewing）
    private static final RecipeType<?> FAC_MINCER = createRecipeType(
            "farm_and_charm", "mincer", "net.satisfy.farm_and_charm.core.recipe.MincerRecipe");
    private static final RecipeType<?> BREWERY_BREWING = createRecipeType(
            "brewery", "brewing", "net.satisfy.brewery.core.recipe.BrewingRecipe");
    // let's do：烤肉架（roaster）、茶壶（tea_kettle_brewing）、草甸烹饪锅（cooking_cauldron）
    private static final RecipeType<?> FAC_ROASTER = createRecipeType(
            "farm_and_charm", "roaster", "net.satisfy.farm_and_charm.core.recipe.RoasterRecipe");
    private static final RecipeType<?> HERBALBREWS_KETTLE = createRecipeType(
            "herbalbrews", "tea_kettle_brewing", "net.satisfy.herbalbrews.core.recipe.TeaKettleRecipe");
    private static final RecipeType<?> MEADOW_COOKING = createRecipeType(
            "meadow", "cooking_cauldron", "net.satisfy.meadow.core.recipes.CookingCauldronRecipe");
    private static final RecipeType<?> MEADOW_CHEESE = createRecipeType(
            "meadow", "cheese", "net.satisfy.meadow.core.recipes.CheeseFormRecipe");

    /**
     * Creates a RecipeType with the correct recipe class, using Class.forName to load
     * the recipe class from other mods. If the class is not found, falls back to Recipe.class.
     * This ensures RecipeType.equals() matches the category's RecipeType (same UID + same recipe class).
     */
    @SuppressWarnings("unchecked")
    /**
     * 中央厨房作为所有已支持系列配方类型的 JEI 催化剂：
     * 只要安装了对应模块，配方页就会显示中央厨房图标。
     */
    /**
     * 把三明治组装机注册为 Some Assembly Required「三明治站」JEI 分类的催化剂。
     * 通过反射读取 SAR 自己的 JEI 插件字段，避免对 SAR 产生编译期依赖。
     */
    private static void jeiRegistrationAddSandwichCatalyst(
            mezz.jei.api.registration.IRecipeCatalystRegistration registration) throws Exception {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("someassemblyrequired")) return;
        Class<?> sarJei = Class.forName("someassemblyrequired.integration.jei.JEIPlugin");
        Object type = sarJei.getField("SANDWICHING_STATION").get(null);
        if (type instanceof mezz.jei.api.recipe.RecipeType<?> recipeType) {
            // 三明治组装机本身 + 中央厨房（装入「三明治组装机」模块后同样可以量产）
            addCatalystTyped(registration,
                    new ItemStack(cn.ism.mekck.registry.MekCkStandaloneMachines.SANDWICH_ASSEMBLER_ITEM.get()),
                    recipeType);
            addCatalystTyped(registration,
                    new ItemStack(cn.ism.mekck.registry.MekCkStandaloneMachines.CENTRAL_KITCHEN_BLOCK.get()),
                    recipeType);
        }
    }

    /** 通配符捕获桥接：把 RecipeType<?> 交给 JEI 的泛型方法。 */
    private static <T> void addCatalystTyped(
            mezz.jei.api.registration.IRecipeCatalystRegistration registration,
            ItemStack stack, mezz.jei.api.recipe.RecipeType<T> type) {
        registration.addRecipeCatalyst(stack, type);
    }

    /**
     * 中央厨房作为 MekCK 自身配方分类（种植切配 / 制冰 / 费列罗 / 炒坚果 / 生物反应堆）的催化剂。
     * 其它模组的配方分类由各自模组注册，中央厨房在其中的显示依赖那些模组。
     */
    private static void jeiRegistrationAddKitchenCatalysts(
            mezz.jei.api.registration.IRecipeCatalystRegistration registration) {
        ItemStack kitchen = new ItemStack(cn.ism.mekck.registry.MekCkStandaloneMachines.CENTRAL_KITCHEN_BLOCK.get());

        // ① 本模组自身的配方分类
        registration.addRecipeCatalyst(kitchen, PLANT_CUT_TYPE);
        registration.addRecipeCatalyst(kitchen, ICE_MAKE_TYPE);
        registration.addRecipeCatalyst(kitchen, FERRERO_TYPE);
        registration.addRecipeCatalyst(kitchen, NUT_ROASTING_TYPE);
        registration.addRecipeCatalyst(kitchen, GRINDING_TYPE);
        registration.addRecipeCatalyst(kitchen, EXTRACTING_TYPE);
        registration.addRecipeCatalyst(kitchen, BEVERAGE_ASSEMBLY_TYPE);
        registration.addRecipeCatalyst(kitchen, PACKAGING_TYPE);
        registration.addRecipeCatalyst(kitchen, BIOREACTOR_TYPE);

        // ② 农夫乐事：切割与烹饪（中央厨房装入对应模块后即可处理）
        registration.addRecipeCatalyst(kitchen, CUTTING_TYPE);
        registration.addRecipeCatalyst(kitchen, COOKING_TYPE);

        // ③ 烧烤乐事：穿串与烧烤
        if (ModList.get().isLoaded("barbequesdelight")) {
            registration.addRecipeCatalyst(kitchen, SKEWERING_TYPE, GRILLING_TYPE);
        }

        // ④ 森罗物语：厨房（锅具与石磨）
        if (ModList.get().isLoaded("kaleidoscope_cookery")) {
            registration.addRecipeCatalyst(kitchen,
                    KC_POT, KC_FLEX_POT, KC_STOCKPOT, KC_FLEX_STOCKPOT, KC_MILLSTONE);
        }

        // ⑤ 森罗物语：烟火（烤制 / 穿串 / 秘制穿串 / 调味）
        if (ModList.get().isLoaded("kaleidoscope_grilling")) {
            registration.addRecipeCatalyst(kitchen,
                    KG_GRILLING, KG_THREADING, KG_SECRET_THREADING, KG_SEASONING);
        }

        // ⑥ 妖怪们的归家：料理台 / 晾晒架 / 蒸笼 / 发酵
        if (ModList.get().isLoaded("youkaishomecoming")) {
            registration.addRecipeCatalyst(kitchen, YH_CUISINE, YH_RACK, YH_STEAM, YH_FERMENT);
        }

        // ⑦ let's do 系列
        if (ModList.get().isLoaded("vinery")) {
            registration.addRecipeCatalyst(kitchen, VINERY_WINE, VINERY_MASH, VINERY_FERMENT);
        }
        if (ModList.get().isLoaded("bakery")) {
            registration.addRecipeCatalyst(kitchen, BAKERY_CAKING);
        }
        if (ModList.get().isLoaded("farm_and_charm")) {
            registration.addRecipeCatalyst(kitchen, FAC_STOVE, FAC_ROASTER, FAC_MINCER);
        }
        if (ModList.get().isLoaded("brewery")) {
            registration.addRecipeCatalyst(kitchen, BREWERY_BREWING);
        }
        if (ModList.get().isLoaded("herbalbrews")) {
            registration.addRecipeCatalyst(kitchen, HERBALBREWS_KETTLE);
        }
        if (ModList.get().isLoaded("meadow")) {
            registration.addRecipeCatalyst(kitchen, MEADOW_COOKING, MEADOW_CHEESE);
        }

        // ⑧ 烘焙坊：烤箱 / 咖啡 / 石窑 / 搅拌 / 筛粉
        if (ModList.get().isLoaded("bakeries")) {
            registration.addRecipeCatalyst(kitchen,
                    BAKERIES_OVEN, BAKERIES_DRINK, BAKERIES_STONE_KILN, BAKERIES_BLENDER, BAKERIES_FLOUR_SIEVE);
        }

        // ⑨ 酒馆：调酒
        if (ModList.get().isLoaded("kaleidoscope_tavern")) {
            registration.addRecipeCatalyst(kitchen, TAVERN_SHAKER);
        }
    }

    private static RecipeType<?> createRecipeType(String namespace, String path, String className) {
        try {
            Class<?> recipeClass = Class.forName(className, false, JEIPlugin.class.getClassLoader());
            return RecipeType.create(namespace, path, (Class) recipeClass);
        } catch (ClassNotFoundException e) {
            // Fallback to generic Recipe class if the mod is not installed
            return RecipeType.create(namespace, path, (Class) Recipe.class);
        }
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var helper = registration.getJeiHelpers().getGuiHelper();

        // Register the Planting & Cutting recipe category for mekck:plantcut recipes
        registration.addRecipeCategories(new PlantingCuttingRecipeCategory(
                helper,
                PLANT_CUT_TYPE,
                new ItemStack(MekCkFactories.PLANTING_CUTTING_STATION_BLOCK.get())
        ));

        // Register the Bioreactor recipe category (item -> organic matter fluid)
        registration.addRecipeCategories(new BioreactorRecipeCategory(
                helper,
                BIOREACTOR_TYPE,
                new ItemStack(MekCkStandaloneMachines.BIOREACTOR_BLOCK.get())
        ));

        // Register the Wine Cellar (陈化窖 F20) 介绍页分类
        registration.addRecipeCategories(new WineCellarInfoCategory(
                helper,
                WINE_CELLAR_TYPE,
                new ItemStack(MekCkStandaloneMachines.WINE_CELLAR_HANDLE.getBlock())
        ));

        // Register the Ice Make recipe category (急冻制冰机 / 制冰工厂)
        registration.addRecipeCategories(new IceMakeRecipeCategory(
                helper,
                ICE_MAKE_TYPE,
                new ItemStack(MekCkStandaloneMachines.ICE_MAKER_BLOCK.get())
        ));

        // Register the Ferrero recipe category (巧克力大炮)
        registration.addRecipeCategories(new FerreroRecipeCategory(
                helper,
                FERRERO_TYPE,
                new ItemStack(MekCkStandaloneMachines.CHOCOLATE_CANNON_BLOCK.get())
        ));

        // Register the Nut Roasting recipe category (坚果爆炒机)
        registration.addRecipeCategories(new NutRoastingRecipeCategory(
                helper,
                NUT_ROASTING_TYPE,
                new ItemStack(MekCkStandaloneMachines.NUT_ROASTER_BLOCK.get())
        ));

        // §F19：磨粉分类（电力研磨机）与萃取分类（智能萃取机）
        registration.addRecipeCategories(new GrindingRecipeCategory(
                helper,
                GRINDING_TYPE,
                MekCkFactories.GRINDING_MACHINE_HANDLE.getItemStack()
        ));
        registration.addRecipeCategories(new ExtractingRecipeCategory(
                helper,
                EXTRACTING_TYPE,
                new ItemStack(MekCkLegacyMachines.SMART_EXTRACTOR_ITEM.get())
        ));

        // Register the Beverage Assembly recipe category (饮品调配机 F11 §四.2)
        registration.addRecipeCategories(new BeverageAssemblyRecipeCategory(
                helper,
                BEVERAGE_ASSEMBLY_TYPE,
                new ItemStack(MekCkLegacyMachines.BEVERAGE_BLENDER_BLOCK.get())
        ));

        // Register the Packaging recipe category (包材组装机 F7/F11 §四.4)
        registration.addRecipeCategories(new PackagingRecipeCategory(
                helper,
                PACKAGING_TYPE,
                new ItemStack(MekCkLegacyMachines.PACKAGING_STATION_BLOCK.get())
        ));

        // Register the Grape Pressing recipe category (鲜果榨汁机葡萄压榨 简报需求1)
        registration.addRecipeCategories(new GrapePressingRecipeCategory(
                helper,
                GRAPE_PRESSING_TYPE,
                new ItemStack(MekCkLegacyMachines.JUICER_BLOCK.get())
        ));

        // 自有配方类型 mekck:skewering / mekck:grilling 的分类。
        // ⚠️ 用的是 JEI 的 RecipeType 常量（MEKCK_*_TYPE），不是注册表里那个原版
        // RecipeType——两者是不同的类。配方类必须写成真实配方类，理由见常量声明处。
        registration.addRecipeCategories(new SkeweringRecipeCategory(
                helper,
                MEKCK_SKEWERING_TYPE,
                new ItemStack(MekCkStandaloneMachines.SKEWERING_MACHINE_BLOCK.get())
        ));
        registration.addRecipeCategories(new GrillingRecipeCategory(
                helper,
                MEKCK_GRILLING_TYPE,
                new ItemStack(MekCkFactories.GRILL_BLOCK.get())
        ));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // Register mekck:plantcut recipes from the recipe manager
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            Level level = mc.level;
            RecipeManager recipeManager = level.getRecipeManager();
            net.minecraft.world.item.crafting.RecipeType<PlantingCuttingRecipe> plantCutType =
                    MekCkRecipeTypes.PLANTING_CUTTING_RECIPE_TYPE.get();
            if (plantCutType != null) {
                List<PlantingCuttingRecipe> recipes = recipeManager.getAllRecipesFor(plantCutType);
                if (!recipes.isEmpty()) {
                    registration.addRecipes(PLANT_CUT_TYPE, recipes);
                }
            }

            // Register Bioreactor recipes: every item that yields organic matter (rules C>A>B)
            List<BioreactorJeiRecipe> bioRecipes = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                ItemStack stack = new ItemStack(item);
                int mb = BioreactorFuels.getMBPerUnit(stack, level);
                if (mb > 0) {
                    bioRecipes.add(new BioreactorJeiRecipe(stack, mb));
                }
            }
            if (!bioRecipes.isEmpty()) {
                registration.addRecipes(BIOREACTOR_TYPE, bioRecipes);
            }

            // Register mekck:ice_make recipes from the recipe manager
            List<IceMakeRecipe> iceRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.ICE_MAKE_RECIPE_TYPE.get());
            if (!iceRecipes.isEmpty()) {
                registration.addRecipes(ICE_MAKE_TYPE, iceRecipes);
            }

            // Register mekck:ferrero recipes from the recipe manager
            List<FerreroRecipe> ferreroRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.FERRERO_RECIPE_TYPE.get());
            if (!ferreroRecipes.isEmpty()) {
                registration.addRecipes(FERRERO_TYPE, ferreroRecipes);
            }

            // Register mekck:nut_roasting recipes from the recipe manager
            List<NutRoastingRecipe> nutRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.NUT_ROASTING_RECIPE_TYPE.get());
            if (!nutRecipes.isEmpty()) {
                registration.addRecipes(NUT_ROASTING_TYPE, nutRecipes);
            }

            // Register mekck:grinding recipes (§F19 D 半：磨粉)
            List<cn.ism.mekck.recipe.GrindingRecipe> grindingRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.GRINDING_RECIPE_TYPE.get());
            if (!grindingRecipes.isEmpty()) {
                registration.addRecipes(GRINDING_TYPE, grindingRecipes);
            }

            // Register mekck:extracting recipes (§F19 C+E 半：萃取)
            List<cn.ism.mekck.recipe.ExtractingRecipe> extractingRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.EXTRACTING_RECIPE_TYPE.get());
            if (!extractingRecipes.isEmpty()) {
                registration.addRecipes(EXTRACTING_TYPE, extractingRecipes);
            }

            // Register mekck:beverage_assembly recipes from the recipe manager
            List<cn.ism.mekck.recipe.BeverageAssemblyRecipe> beverageRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.BEVERAGE_ASSEMBLY_RECIPE_TYPE.get());
            if (!beverageRecipes.isEmpty()) {
                registration.addRecipes(BEVERAGE_ASSEMBLY_TYPE, beverageRecipes);
            }

            // Register mekck:packaging recipes from the recipe manager
            List<cn.ism.mekck.recipe.PackagingRecipe> packagingRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.PACKAGING_RECIPE_TYPE.get());
            if (!packagingRecipes.isEmpty()) {
                registration.addRecipes(PACKAGING_TYPE, packagingRecipes);
            }

            // Register mekck:grape_pressing recipes from the recipe manager (鲜果榨汁机葡萄压榨)
            List<cn.ism.mekck.recipe.GrapePressingRecipe> grapeRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.GRAPE_PRESSING_RECIPE_TYPE.get());
            if (!grapeRecipes.isEmpty()) {
                registration.addRecipes(GRAPE_PRESSING_TYPE, grapeRecipes);
            }

            // 自有配方类型 mekck:skewering / mekck:grilling 的配方本体。
            // 此前只注册了催化剂、没注册配方，也没有分类 ⇒ 这 16 条数据包配方在 JEI 里查不到。
            List<cn.ism.mekck.recipe.MekCkSkeweringRecipe> skeweringRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.SKEWERING_RECIPE_TYPE.get());
            if (!skeweringRecipes.isEmpty()) {
                registration.addRecipes(MEKCK_SKEWERING_TYPE, skeweringRecipes);
            }
            List<cn.ism.mekck.recipe.MekCkGrillingRecipe> grillingRecipes = recipeManager.getAllRecipesFor(
                    MekCkRecipeTypes.GRILLING_RECIPE_TYPE.get());
            if (!grillingRecipes.isEmpty()) {
                registration.addRecipes(MEKCK_GRILLING_TYPE, grillingRecipes);
            }
        }

        // 陈化窖（F20）介绍页：始终挂一条（未装 vinery 时示例瓶为空、仅留文字）
        registration.addRecipes(WINE_CELLAR_TYPE, List.of(makeWineCellarInfoRecipe()));
    }

    /** 造陈化窖介绍页的示例瓶：装了 vinery 就用 {@code vinery:wine_bottle} 带 Year NBT 演示“普通→更陈”；否则空。 */
    private static WineCellarInfoRecipe makeWineCellarInfoRecipe() {
        net.minecraft.resources.ResourceLocation bottleId = new net.minecraft.resources.ResourceLocation("vinery", "wine_bottle");
        Item bottleItem = ForgeRegistries.ITEMS.getValue(bottleId);
        if (bottleItem == null || bottleItem == net.minecraft.world.item.Items.AIR) {
            return new WineCellarInfoRecipe(ItemStack.EMPTY, ItemStack.EMPTY);
        }
        ItemStack young = new ItemStack(bottleItem);
        young.getOrCreateTag().putInt("Year", 20);   // 年份较高 = 较新
        ItemStack aged = new ItemStack(bottleItem);
        aged.getOrCreateTag().putInt("Year", 5);     // 年份较低 = 更陈（被往过去推）
        return new WineCellarInfoRecipe(young, aged);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        // 鲜果榨汁机：葡萄压榨（mekck:grape_pressing）催化剂
        registration.addRecipeCatalyst(new ItemStack(MekCkLegacyMachines.JUICER_BLOCK.get()), GRAPE_PRESSING_TYPE);

        // 陈化窖（F20）：其介绍页分类的催化剂（在 JEI 搜酒/点机器可看到这页说明）
        registration.addRecipeCatalyst(new ItemStack(MekCkStandaloneMachines.WINE_CELLAR_HANDLE.getBlock()), WINE_CELLAR_TYPE);

        // Register all cutting machines as catalysts for the cutting recipe type
        //
        // 一律走 FACTORY_BLOCKS 遍历，**不要**改成逐档枚举常量：这里原本手写了 11 行
        // BASIC…SINGULARITY，唯独漏了 BLAZE ⇒ 烈焰等级的切菜工厂在 JEI 里查不到催化剂。
        // 同一个「手写档位清单漏 BLAZE」的缺陷在 IceFactoryBlock 已经炸过一次
        // （2026-09-16，放置时抛 IllegalArgumentException），此处是第二次复现。
        // 护栏：TestJeiCatalystCoverage#cuttingCatalystsCoverEveryTier。
        registration.addRecipeCatalyst(new ItemStack(MekCkFactories.MACHINE_BLOCK.get()), CUTTING_TYPE);
        for (var entry : MekCkFactories.FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), CUTTING_TYPE);
        }

        // Register all cooking machines as catalysts for the cooking recipe type
        registration.addRecipeCatalyst(new ItemStack(MekCkStandaloneMachines.COOKING_POT_BLOCK.get()), COOKING_TYPE);
        for (var entry : MekCkFactories.COOKING_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), COOKING_TYPE);
        }

        // Register the Endless Greed cooking factory as a catalyst for the extreme_cooking recipe type
        registration.addRecipeCatalyst(
                new ItemStack(MekCkFactories.COOKING_FACTORY_BLOCKS.get(CuttingMachineFactoryTier.SINGULARITY).get()),
                EXTREME_COOKING
        );

        // Register skewering machines as catalysts for barbequesdelight:skewering recipe type
        registration.addRecipeCatalyst(new ItemStack(MekCkStandaloneMachines.SKEWERING_MACHINE_BLOCK.get()), SKEWERING_TYPE);
        for (var entry : MekCkFactories.SKEWERING_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), SKEWERING_TYPE);
        }

        // Register grill machines as catalysts for barbequesdelight:grilling recipe type
        registration.addRecipeCatalyst(new ItemStack(MekCkFactories.GRILL_BLOCK.get()), GRILLING_TYPE);
        for (var entry : MekCkFactories.GRILL_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), GRILLING_TYPE);
        }

        // mekck:skewering（串烧工厂自有配方类型）——无条件注册，该类型恒存在。
        // 语义：签子(载体，不消耗) + 主料 + 辅料 → 串烧物。
        // ⚠️ 必须用 MEKCK_SKEWERING_TYPE（JEI 的 RecipeType，配方类写成真实配方类），
        // 不能 RecipeType.create("mekck", "skewering", Recipe.class) 另造一个：
        // JEI 的 RecipeType.equals 同时比 UID 与配方类，另造的那个与分类用的类型不相等，
        // 催化剂会挂在一个永远没有分类的类型上（这正是修复前的状态）。
        registration.addRecipeCatalyst(
                new ItemStack(MekCkStandaloneMachines.SKEWERING_MACHINE_BLOCK.get()), MEKCK_SKEWERING_TYPE);
        for (var entry : MekCkFactories.SKEWERING_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), MEKCK_SKEWERING_TYPE);
        }

        // mekck:grilling（烧烤工厂自有配方类型）——无条件注册，该类型恒存在。
        // 语义：单一输入 → 烤制产物。
        registration.addRecipeCatalyst(
                new ItemStack(MekCkFactories.GRILL_BLOCK.get()), MEKCK_GRILLING_TYPE);
        for (var entry : MekCkFactories.GRILL_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), MEKCK_GRILLING_TYPE);
        }

        // 原版烟熏炉 / 篝火烹饪（熟肉 ×7、烤马铃薯、干燥海带）——全档位烧烤工厂均可处理，
        // 不受档位与配置门禁约束。此处显示范围必须与机器实际行为一致。
        for (var entry : MekCkFactories.GRILL_FACTORY_BLOCKS.entrySet()) {
            ItemStack grillFactory = new ItemStack(entry.getValue().get());
            registration.addRecipeCatalyst(grillFactory, mezz.jei.api.constants.RecipeTypes.SMOKING);
            registration.addRecipeCatalyst(grillFactory, mezz.jei.api.constants.RecipeTypes.CAMPFIRE_COOKING);
        }

        // 晶钛矩阵以上烧烤工厂 → 原版熔炉 / 高炉配方催化剂（配置文件开关，默认开）
        // 烟熏炉 / 篝火已上移为全档位能力，不在本开关管辖范围内。
        // 注意：JEI 内置熔炉类型的 path 是 "furnace"（不是 "smelting"），直接复用官方常量最稳妥
        if (cn.ism.mekck.config.MekckConfig.isGrillFurnaceJeiCatalystEnabled()) {
            mezz.jei.api.recipe.RecipeType<?>[] furnaceTypes = {
                    mezz.jei.api.constants.RecipeTypes.SMELTING,
                    mezz.jei.api.constants.RecipeTypes.BLASTING
            };
            if (cn.ism.mekck.config.MekckConfig.isCrystalMatrixGrillFurnaceEnabled()) {
                for (CuttingMachineFactoryTier highTier : new CuttingMachineFactoryTier[]{
                        CuttingMachineFactoryTier.CRYSTAL_MATRIX, CuttingMachineFactoryTier.NEBULA}) {
                    ItemStack block = new ItemStack(MekCkFactories.GRILL_FACTORY_BLOCKS.get(highTier).get());
                    for (mezz.jei.api.recipe.RecipeType<?> ft : furnaceTypes) {
                        registration.addRecipeCatalyst(block, ft);
                    }
                }
            }
            if (cn.ism.mekck.config.MekckConfig.isSingularityGrillFurnaceEnabled()) {
                ItemStack block = new ItemStack(
                        MekCkFactories.GRILL_FACTORY_BLOCKS.get(CuttingMachineFactoryTier.SINGULARITY).get());
                for (mezz.jei.api.recipe.RecipeType<?> ft : furnaceTypes) {
                    registration.addRecipeCatalyst(block, ft);
                }
            }
        }

        // Register all planting & cutting machines as catalysts for the mekck:plantcut recipe type
        registration.addRecipeCatalyst(
                new ItemStack(MekCkFactories.PLANTING_CUTTING_STATION_BLOCK.get()),
                PLANT_CUT_TYPE
        );
        for (var entry : MekCkFactories.PLANTING_CUTTING_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), PLANT_CUT_TYPE);
        }

        // 生物反应堆作为自身配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkStandaloneMachines.BIOREACTOR_BLOCK.get()),
                BIOREACTOR_TYPE
        );

        // §F34：鲜果榨汁机作为其三条通道配方分类的催化剂（此前只作 grape_pressing 类别图标、未挂 catalyst，
        //   导致 JEI 催化剂查询只能看到它做瓶装葡萄汁、查不到苹果汁/酒馆果汁）。服务端三通道匹配本已齐全，
        //   此处仅补齐客户端显示层：①自研葡萄压榨 ②vinery 苹果泥/苹果汁 ③kaleidoscope_tavern 压榨槽。
        ItemStack juicer = new ItemStack(MekCkLegacyMachines.JUICER_BLOCK.get());
        registration.addRecipeCatalyst(juicer, GRAPE_PRESSING_TYPE);
        if (ModList.get().isLoaded("vinery")) {
            registration.addRecipeCatalyst(juicer, VINERY_MASH);
            registration.addRecipeCatalyst(juicer, VINERY_FERMENT);
        }
        if (ModList.get().isLoaded("kaleidoscope_tavern")) {
            registration.addRecipeCatalyst(juicer, TAVERN_PRESSING);
        }

        // 三明治组装机：作为 SAR「三明治站」JEI 分类的催化剂（反射取它的配方类型，不做硬依赖）
        try {
            jeiRegistrationAddSandwichCatalyst(registration);
        } catch (Throwable ignored) {
        }

        // 中央厨房：作为所有系列配方类型的催化剂（jei 中显示"可用中央厨房处理"）
        try {
            jeiRegistrationAddKitchenCatalysts(registration);
        } catch (Throwable ignored) {
        }

        // 急冻制冰机及所有制冰工厂作为 mekck:ice_make 配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkStandaloneMachines.ICE_MAKER_BLOCK.get()),
                ICE_MAKE_TYPE
        );
        for (var entry : MekCkFactories.ICE_FACTORY_BLOCKS.entrySet()) {
            registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), ICE_MAKE_TYPE);
        }

        // 巧克力大炮作为 mekck:ferrero 配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkStandaloneMachines.CHOCOLATE_CANNON_BLOCK.get()),
                FERRERO_TYPE
        );

        // 坚果爆炒机作为 mekck:nut_roasting 配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkStandaloneMachines.NUT_ROASTER_BLOCK.get()),
                NUT_ROASTING_TYPE
        );

        // §F19：研磨机/萃取机作为各自新分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkFactories.GRINDING_MACHINE_HANDLE.getBlock()),
                GRINDING_TYPE
        );
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.SMART_EXTRACTOR_BLOCK.get()),
                EXTRACTING_TYPE
        );

        // 饮品调配机作为 mekck:beverage_assembly 配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.BEVERAGE_BLENDER_BLOCK.get()),
                BEVERAGE_ASSEMBLY_TYPE
        );

        // 包材组装机作为 mekck:packaging 配方分类的催化剂
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.PACKAGING_STATION_BLOCK.get()),
                PACKAGING_TYPE
        );

        // 寿司卷制机 → 妖怪们的归家料理台（cuisine）配方分类
        if (net.minecraftforge.fml.ModList.get().isLoaded("youkaishomecoming")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.SUSHI_MAKER_BLOCK.get()),
                    createRecipeType("youkaishomecoming", "cuisine", "dev.xkmc.youkaishomecoming.content.pot.table.recipe.CuisineRecipe"));
        }

        // 发酵机 → 妖怪们的归家发酵分类
        if (net.minecraftforge.fml.ModList.get().isLoaded("youkaishomecoming")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.FERMENTER_BLOCK.get()),
                    createRecipeType("youkaishomecoming", "ferment", "dev.xkmc.youkaishomecoming.content.pot.ferment.FermentationRecipe"));
        }

        // 平均切段机 → 农夫乐事切割分类（切段配方）
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.AVERAGE_SLICER_BLOCK.get()),
                CUTTING_TYPE);

        // 饭团成型机 → 农夫乐事烹饪分类（米饭/饭团配方）
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.RICE_BALL_MAKER_BLOCK.get()),
                COOKING_TYPE);

        // Register cooking pot and all cooking factories as catalysts for KaleidoscopeCookery recipes
        if (KaleidoscopeCompat.isLoaded()) {
            RecipeType<?>[] kcTypes = {KC_STOCKPOT, KC_FLEX_STOCKPOT, KC_POT, KC_FLEX_POT};
            ItemStack cookingPotStack = new ItemStack(MekCkStandaloneMachines.COOKING_POT_BLOCK.get());
            for (RecipeType<?> kcType : kcTypes) {
                registration.addRecipeCatalyst(cookingPotStack, kcType);
                for (var entry : MekCkFactories.COOKING_FACTORY_BLOCKS.entrySet()) {
                    registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), kcType);
                }
            }

            // 电力研磨机 + 研磨工厂 → 石磨配方分类
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkFactories.GRINDING_MACHINE_HANDLE.getBlock()),
                    KC_MILLSTONE);
            for (var entry : MekCkFactories.GRINDING_FACTORY_BLOCKS.entrySet()) {
                registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), KC_MILLSTONE);
            }
        }

        // 烟火 (kaleidoscope_grilling) JEI 分类：烧烤/穿串/调味 催化
        if (ModList.get().isLoaded("kaleidoscope_grilling")) {
            // 烧烤机器（烧烤架 + 烧烤工厂）→ 仅烤制/调味分类
            RecipeType<?>[] grillTypes = {KG_GRILLING, KG_SEASONING};
            ItemStack grillStack = new ItemStack(MekCkFactories.GRILL_BLOCK.get());
            for (RecipeType<?> kgType : grillTypes) {
                registration.addRecipeCatalyst(grillStack, kgType);
                for (var entry : MekCkFactories.GRILL_FACTORY_BLOCKS.entrySet()) {
                    registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), kgType);
                }
            }
            // 穿串机器（穿串机 + 穿串工厂）→ 穿串/秘制穿串分类（烧烤机器不参与穿串）
            RecipeType<?>[] threadingTypes = {KG_THREADING, KG_SECRET_THREADING};
            ItemStack skewerStack = new ItemStack(MekCkStandaloneMachines.SKEWERING_MACHINE_BLOCK.get());
            for (RecipeType<?> t : threadingTypes) {
                registration.addRecipeCatalyst(skewerStack, t);
                for (var entry : MekCkFactories.SKEWERING_FACTORY_BLOCKS.entrySet()) {
                    registration.addRecipeCatalyst(new ItemStack(entry.getValue().get()), t);
                }
            }
        }

        // ── SimpleMachine 11 台机器 → 对应配方分类催化剂 ──
        // 平均切段机（farmersdelight:cutting 已定义）
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.AVERAGE_SLICER_BLOCK.get()), CUTTING_TYPE);
        // 凝乳成型机（trailandtales 凝乳配方为 farmersdelight:cooking 类型）
        registration.addRecipeCatalyst(
                new ItemStack(MekCkLegacyMachines.CURD_MAKER_BLOCK.get()), COOKING_TYPE);
        // 智能料理台（妖怪们的归家料理台）
        if (ModList.get().isLoaded("youkaishomecoming")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.SUSHI_MAKER_BLOCK.get()), YH_CUISINE);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.DEHYDRATOR_BLOCK.get()), YH_RACK);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STEAMER_BLOCK.get()), YH_STEAM);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.FERMENTER_BLOCK.get()), YH_FERMENT);
        }
        // 葡园酒香
        if (ModList.get().isLoaded("vinery")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.WINERY_BLOCK.get()), VINERY_WINE);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.JUICER_BLOCK.get()), VINERY_MASH, VINERY_FERMENT);
            // 发酵机不再作为陈酿桶（wine_fermentation）催化剂：它不处理 vinery 陈酿桶配方（用户 2026-09-22 验收要求）
        }
        // 馥郁烘焙
        if (ModList.get().isLoaded("bakery")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.BAKERY_OVEN_BLOCK.get()), BAKERY_CAKING);
        }
        // 沉浸农艺
        if (ModList.get().isLoaded("farm_and_charm")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STOVE_BLOCK.get()), FAC_STOVE);
            // 电力研磨机 → 绞碎（mincer）配方分类
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkFactories.GRINDING_MACHINE_HANDLE.getBlock()), FAC_MINCER);
        }
        // 盛节精酿：发酵机 → 酿造（brewing）配方分类
        if (ModList.get().isLoaded("brewery")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.FERMENTER_BLOCK.get()), BREWERY_BREWING);
        }
        // let's do：智能烤炉 → 烤肉架 / 茶壶 / 草甸烹饪锅分类
        if (ModList.get().isLoaded("farm_and_charm")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STOVE_BLOCK.get()), FAC_ROASTER);
        }
        if (ModList.get().isLoaded("herbalbrews")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STOVE_BLOCK.get()), HERBALBREWS_KETTLE);
        }
        if (ModList.get().isLoaded("meadow")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STOVE_BLOCK.get()), MEADOW_COOKING);
            // 凝乳成型机 → 奶酪压榨（cheese）配方分类
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.CURD_MAKER_BLOCK.get()), MEADOW_CHEESE);
        }
        // 烘焙坊（bakeries）：烘焙机 → 烤箱 + 咖啡分类；智能烤炉 → 石窑分类
        if (ModList.get().isLoaded("bakeries")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.BAKERY_OVEN_BLOCK.get()), BAKERIES_OVEN, BAKERIES_DRINK);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.STOVE_BLOCK.get()), BAKERIES_STONE_KILN);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.BLENDER_BLOCK.get()), BAKERIES_BLENDER);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkFactories.GRINDING_MACHINE_HANDLE.getBlock()), BAKERIES_FLOUR_SIEVE);
        }
        // 简单的茶：智能茶艺机 → 原版合成分类（它的配方都是 crafting_shapeless）
        if (ModList.get().isLoaded("simplytea")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.TEA_BREWER_BLOCK.get()),
                    mezz.jei.api.constants.RecipeTypes.CRAFTING);
        }
        // 酒馆（kaleidoscope_tavern）：调酒机 → 调酒配方分类（shaker）
        if (ModList.get().isLoaded("kaleidoscope_tavern")) {
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.COCKTAIL_SHAKER_BLOCK.get()), TAVERN_SHAKER);
            // 陈酿机 → 酒桶（barrel）催化剂；鲜果榨汁机 → 榨汁（pressing_tub）催化剂（复用 tavern 自带分类）
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.WINERY_BLOCK.get()), TAVERN_BARREL);
            registration.addRecipeCatalyst(
                    new ItemStack(MekCkLegacyMachines.JUICER_BLOCK.get()), TAVERN_PRESSING);
        }
    }

    @Override
    public ResourceLocation getPluginUid() {
        return ID;
    }
}