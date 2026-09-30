package cn.ism.mekck;

import cn.ism.mekck.block.CookingFactoryBlock;
import cn.ism.mekck.block.CuttingMachineFactoryBlock;
import cn.ism.mekck.block.GrillBlock;
import cn.ism.mekck.block.GrillFactoryBlock;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.block.BioreactorBlock;
import cn.ism.mekck.block.BioreactorBoundingBlock;
import cn.ism.mekck.block.PlantingCuttingStationBlock;
import cn.ism.mekck.block.SkeweringMachineBlock;
import cn.ism.mekck.block.SkeweringFactoryBlock;
import cn.ism.mekck.block.SmartCookingPotBlock;
import cn.ism.mekck.block.ElectricGrindingMachineBlock;
import cn.ism.mekck.block.GrindingFactoryBlock;
import cn.ism.mekck.block.UniversalCuttingMachineBlock;
import cn.ism.mekck.blockentity.GrillBlockEntity;
import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity;
import cn.ism.mekck.blockentity.UniversalCuttingMachineBlockEntity;
import cn.ism.mekck.block.IceMakerBlock;
import cn.ism.mekck.block.IceFactoryBlock;
import cn.ism.mekck.block.WineCellarBlock;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import cn.ism.mekck.blockentity.WineCellarBlockEntity;
import cn.ism.mekck.menu.IceMakerMenu;
import cn.ism.mekck.menu.IceFactoryMenu;
import cn.ism.mekck.menu.WineCellarMenu;
import cn.ism.mekck.client.IceMakerScreen;
import cn.ism.mekck.client.IceFactoryScreen;
import cn.ism.mekck.client.WineCellarScreen;
import cn.ism.mekck.recipe.IceMakeRecipe;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import cn.ism.mekck.client.CookingFactoryScreen;
import cn.ism.mekck.client.CuttingMachineFactoryScreen;
import cn.ism.mekck.client.GrillFactoryScreen;
import cn.ism.mekck.client.MekCkOutlineRenderer;
import cn.ism.mekck.client.GrillScreen;
import cn.ism.mekck.client.PlantingCuttingFactoryScreen;
import cn.ism.mekck.client.BioreactorScreen;
import cn.ism.mekck.client.PlantingCuttingStationScreen;
import cn.ism.mekck.client.SkeweringFactoryScreen;
import cn.ism.mekck.client.SkeweringMachineScreen;
import cn.ism.mekck.client.SmartCookingPotScreen;
import cn.ism.mekck.client.ElectricGrindingMachineScreen;
import cn.ism.mekck.client.GrindingFactoryScreen;
import cn.ism.mekck.client.UniversalCuttingMachineScreen;
import cn.ism.mekck.menu.CookingFactoryMenu;
import cn.ism.mekck.menu.CuttingMachineFactoryMenu;
import cn.ism.mekck.menu.GrillFactoryMenu;
import cn.ism.mekck.menu.GrillMenu;
import cn.ism.mekck.menu.PlantingCuttingFactoryMenu;
import cn.ism.mekck.menu.BioreactorMenu;
import cn.ism.mekck.menu.PlantingCuttingStationMenu;
import cn.ism.mekck.menu.SkeweringFactoryMenu;
import cn.ism.mekck.menu.SkeweringMachineMenu;
import cn.ism.mekck.menu.SmartCookingPotMenu;
import cn.ism.mekck.menu.ElectricGrindingMachineMenu;
import cn.ism.mekck.menu.GrindingFactoryMenu;
import cn.ism.mekck.menu.UniversalCuttingMachineMenu;
import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.item.ColdBrewUpgradeItem;
import cn.ism.mekck.item.MekCkBlockItem;
import cn.ism.mekck.block.ChocolateCannonBlock;
import cn.ism.mekck.block.NutRoasterBlock;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.blockentity.NutRoasterBlockEntity;
import cn.ism.mekck.menu.ChocolateCannonMenu;
import cn.ism.mekck.menu.NutRoasterMenu;
import cn.ism.mekck.client.ChocolateCannonScreen;
import cn.ism.mekck.client.FerreroRenderer;
import cn.ism.mekck.client.NutRoasterScreen;
import cn.ism.mekck.network.ModMessages;
import cn.ism.mekck.util.MekCkMultiblock;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeHasBounding;
import cn.ism.mekck.command.PlantingRecipeGenerator;
import cn.ism.mekck.config.MekckConfig;
import cn.ism.mekck.recipe.PlantingCuttingRecipe;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import cn.ism.mekck.util.LagMonitor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

@Mod(UniversalCuttingMachine.MOD_ID)
public final class UniversalCuttingMachine {
    public static final String MOD_ID = "mekck";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    /** 通用机械指南手册（右键打开 GuideME 指南 mekguide:mek）。 */
    public static final RegistryObject<Item> GUIDE_HANDBOOK_ITEM = ITEMS.register("guide_handbook",
            () -> new cn.ism.mekck.item.GuideHandbookItem(new Item.Properties().stacksTo(1)));

    /** 存储升级卡（mekck:upgrade_storage）：提升并行线程数与缓冲容量，{@code Upgrade.getMax()} 为 6。 */
    public static final RegistryObject<Item> STORAGE_UPGRADE_ITEM = ITEMS.register("upgrade_storage",
            () -> new cn.ism.mekck.upgrade.MekCkStorageUpgradeItem(new Item.Properties().stacksTo(64)));
    /** 随机化升级卡（mekck:upgrade_randomize）：随机化本局 49 种可用食物，{@code Upgrade.getMax()} 为 1。 */
    public static final RegistryObject<Item> RANDOMIZE_UPGRADE_ITEM = ITEMS.register("upgrade_randomize",
            () -> new cn.ism.mekck.upgrade.MekCkRandomizeUpgradeItem(new Item.Properties().stacksTo(64)));
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);

    // Recipe type and serializer registries
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS = DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, MOD_ID);
    public static final DeferredRegister<RecipeType<?>> RECIPE_TYPES = DeferredRegister.create(ForgeRegistries.RECIPE_TYPES, MOD_ID);

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
    public static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MOD_ID);
    public static final RegistryObject<cn.ism.mekck.effect.FrozenEffect> FROZEN_EFFECT = MOB_EFFECTS.register(
            "frozen", cn.ism.mekck.effect.FrozenEffect::new);
    public static final RegistryObject<cn.ism.mekck.effect.HypothermiaEffect> HYPOTHERMIA_EFFECT = MOB_EFFECTS.register(
            "hypothermia", cn.ism.mekck.effect.HypothermiaEffect::new);
    public static final RegistryObject<cn.ism.mekck.effect.EternalFreezeEffect> ETERNAL_FREEZE_EFFECT = MOB_EFFECTS.register(
            "eternal_freeze", cn.ism.mekck.effect.EternalFreezeEffect::new);

    // Ice cube entity (急冻制冰机攻击生成的冰块)
    public static final RegistryObject<EntityType<cn.ism.mekck.entity.IceCubeEntity>> ICE_CUBE_ENTITY;

    // Ferrero chocolate entity (巧克力大炮攻击生成的费列罗巧克力)
    public static final RegistryObject<EntityType<cn.ism.mekck.entity.FerreroEntity>> FERRERO_ENTITY;

    // Roasted hazelnut entity (坚果爆炒机发射的炒榛子弹射物)
    public static final RegistryObject<EntityType<cn.ism.mekck.entity.RoastedHazelnutEntity>> ROASTED_HAZELNUT_ENTITY;

    // Basic machine
    public static final RegistryObject<Block> MACHINE_BLOCK = BLOCKS.register("universal_cutting_machine", UniversalCuttingMachineBlock::new);
    public static final RegistryObject<Item> MACHINE_ITEM = ITEMS.register("universal_cutting_machine",
            () -> new MekCkBlockItem(MACHINE_BLOCK.get(), new Item.Properties(),
                    1, UniversalCuttingMachineBlockEntity.ENERGY_PER_TICK, UniversalCuttingMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<UniversalCuttingMachineBlockEntity>> MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "universal_cutting_machine",
            () -> BlockEntityType.Builder.of(UniversalCuttingMachineBlockEntity::new, MACHINE_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<UniversalCuttingMachineMenu>> MACHINE_MENU = MENUS.register(
            "universal_cutting_machine", () -> IForgeMenuType.create(UniversalCuttingMachineMenu::new));

    // Electric Grinding Machine (basic machine, processes kaleidoscope_cookery millstone recipes)
    public static final RegistryObject<Block> GRINDING_MACHINE_BLOCK = BLOCKS.register("electric_grinding_machine", ElectricGrindingMachineBlock::new);
    public static final RegistryObject<Item> GRINDING_MACHINE_ITEM = ITEMS.register("electric_grinding_machine",
            () -> new MekCkBlockItem(GRINDING_MACHINE_BLOCK.get(), new Item.Properties(),
                    1, ElectricGrindingMachineBlockEntity.ENERGY_PER_TICK, ElectricGrindingMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<ElectricGrindingMachineBlockEntity>> GRINDING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "electric_grinding_machine",
            () -> BlockEntityType.Builder.of(ElectricGrindingMachineBlockEntity::new, GRINDING_MACHINE_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<ElectricGrindingMachineMenu>> GRINDING_MACHINE_MENU = MENUS.register(
            "electric_grinding_machine", () -> IForgeMenuType.create(ElectricGrindingMachineMenu::new));

    // Planting & Cutting Station
    public static final RegistryObject<Block> PLANTING_CUTTING_STATION_BLOCK = BLOCKS.register("planting_cutting_station", PlantingCuttingStationBlock::new);
    public static final RegistryObject<Item> PLANTING_CUTTING_STATION_ITEM = ITEMS.register("planting_cutting_station",
            () -> new MekCkBlockItem(PLANTING_CUTTING_STATION_BLOCK.get(), new Item.Properties(),
                    1, PlantingCuttingStationBlockEntity.ENERGY_PER_TICK, PlantingCuttingStationBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<PlantingCuttingStationBlockEntity>> PLANTING_CUTTING_STATION_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "planting_cutting_station",
            () -> BlockEntityType.Builder.of(PlantingCuttingStationBlockEntity::new, PLANTING_CUTTING_STATION_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<PlantingCuttingStationMenu>> PLANTING_CUTTING_STATION_MENU = MENUS.register(
            "planting_cutting_station", () -> IForgeMenuType.create(PlantingCuttingStationMenu::new));

    // Ice Maker (急冻制冰机)
    public static final RegistryObject<Block> ICE_MAKER_BLOCK = BLOCKS.register("ice_maker", IceMakerBlock::new);
    public static final RegistryObject<Item> ICE_MAKER_ITEM = ITEMS.register("ice_maker",
            () -> new MekCkBlockItem(ICE_MAKER_BLOCK.get(), new Item.Properties(),
                    1, IceMakerBlockEntity.ENERGY_PER_TICK, IceMakerBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<IceMakerBlockEntity>> ICE_MAKER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "ice_maker", () -> BlockEntityType.Builder.of(IceMakerBlockEntity::new, ICE_MAKER_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<IceMakerMenu>> ICE_MAKER_MENU = MENUS.register(
            "ice_maker", () -> IForgeMenuType.create(IceMakerMenu::new));

    // Wine Cellar (陈化窖/时间悖论产生器，F20：独立容器方块，无固定 energy/tick ⇒ MekCkBlockItem 默认构造)
    public static final RegistryObject<Block> WINE_CELLAR_BLOCK = BLOCKS.register("wine_cellar", WineCellarBlock::new);
    public static final RegistryObject<Item> WINE_CELLAR_ITEM = ITEMS.register("wine_cellar",
            () -> new MekCkBlockItem(WINE_CELLAR_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<WineCellarBlockEntity>> WINE_CELLAR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "wine_cellar", () -> BlockEntityType.Builder.of(WineCellarBlockEntity::new, WINE_CELLAR_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<WineCellarMenu>> WINE_CELLAR_MENU = MENUS.register(
            "wine_cellar", () -> IForgeMenuType.create(WineCellarMenu::new));

    // Central Kitchen (中央厨房：终极机器)
    public static final RegistryObject<Block> CENTRAL_KITCHEN_BLOCK = BLOCKS.register("central_kitchen",
            () -> new cn.ism.mekck.block.CentralKitchenBlock(
                    net.minecraft.world.level.block.state.BlockBehaviour.Properties
                            .copy(net.minecraft.world.level.block.Blocks.IRON_BLOCK)
                            .strength(3.5F).requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Item> CENTRAL_KITCHEN_ITEM = ITEMS.register("central_kitchen",
            () -> new MekCkBlockItem(CENTRAL_KITCHEN_BLOCK.get(), new Item.Properties(),
                    1, 0, 5_000_000));
    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.CentralKitchenBlockEntity>> CENTRAL_KITCHEN_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("central_kitchen", () -> BlockEntityType.Builder.of(
                    cn.ism.mekck.blockentity.CentralKitchenBlockEntity::new, CENTRAL_KITCHEN_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<cn.ism.mekck.menu.CentralKitchenMenu>> CENTRAL_KITCHEN_MENU = MENUS.register(
            "central_kitchen", () -> IForgeMenuType.create(cn.ism.mekck.menu.CentralKitchenMenu::new));

    // 三明治组装机（联动 Some Assembly Required，单等级、无工厂版本）
    public static final RegistryObject<Block> SANDWICH_ASSEMBLER_BLOCK = BLOCKS.register("sandwich_assembler",
            cn.ism.mekck.block.SandwichAssemblerBlock::new);
    public static final RegistryObject<Item> SANDWICH_ASSEMBLER_ITEM = ITEMS.register("sandwich_assembler",
            () -> new MekCkBlockItem(SANDWICH_ASSEMBLER_BLOCK.get(), new Item.Properties(), 1, 20, 100_000));
    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity>> SANDWICH_ASSEMBLER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("sandwich_assembler", () -> BlockEntityType.Builder.of(
                    cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity::new, SANDWICH_ASSEMBLER_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<cn.ism.mekck.menu.SandwichAssemblerMenu>> SANDWICH_ASSEMBLER_MENU = MENUS.register(
            "sandwich_assembler", () -> IForgeMenuType.create(cn.ism.mekck.menu.SandwichAssemblerMenu::new));

    // 制冰工厂开关：原先是 `public static final boolean ICE_FACTORY_ENABLED = false`，
    // 现改为读 MekckConfig 的 `ice_factory.enable_ice_factory`（默认 false，行为与从前一致）。
    // 刻意**不留**一个 static 缓存字段 —— 字段初始化式在类首次加载时求值，可能早于配置文件
    // 被读取，那样加了配置也永远拿到默认值、开关形同虚设。三个使用点见下方各处。
    //
    // ⚠️ 这是**注册表开关**，不是显示开关：客户端与服务端必须配成同一个值，
    // 否则注册表同步校验会在登录时直接拒绝（详见 MekckConfig 里该配置项的注释）。
    //
    // 【勘误】原先这段注释给出的两条「不能开」的理由，第四轮逐条查证后**都不成立**：
    //   ① 「缺 12 张战利品表 ⇒ 破坏后什么都不掉」——错。制冰工厂走
    //      IceFactoryBlock.onRemove 自行掉落（machine.saveToItem(stack) + Containers.dropItemStack），
    //      且 getDrops 被覆写成 List.of()。实测破坏链路为
    //      Block.dropResources → BlockStateBase.getDrops(LootParams.Builder) →
    //      BlockBehaviour.getDrops(BlockState, LootParams.Builder)，
    //      正是被覆写的那个方法 ⇒ **战利品表根本不会被查询**，补 12 张表只会变成死文件。
    //      （顺带一提：data/mekck/loot_tables/blocks/ 下那 5 张遗留机器的表——planting_cutting_station、
    //      electric_grill、electric_grinding_machine、universal_cutting_machine、smart_skewering_machine
    //      ——同样因为这个覆写而从未生效，属于另一个待清理项。）
    //   ② 「byte 下标上限会静默丢存档」——错。BigStackItemHandler.serializeNBT 写的是
    //      putInt("Slot", i)，反序列用 getInt 并带 0 ≤ slot < getSlots() 越界检查。
    //      MekCkSlotNbt 那层兜底针对的是**Mek 自己的** mekanism.api.DataHandlerUtils
    //      （javap 实测 putByte/getByte），遗留 BE 路径本来就不经过它，也就没有那个病。
    // 换言之该族早已掉落与存档齐备，缺的只是一个能被翻开的开关。
    //
    // @see cn.ism.mekck.config.MekckConfig#isIceFactoryEnabled
    // @see cn.ism.mekck.block.IceFactoryBlock#onRemove
    // @see cn.ism.mekck.util.BigStackItemHandler#serializeNBT

    // Chocolate Cannon (巧克力大炮：费列罗巧克力加工 + 攻击机器，无工厂版本)
    public static final RegistryObject<Block> CHOCOLATE_CANNON_BLOCK = BLOCKS.register("chocolate_cannon", ChocolateCannonBlock::new);
    public static final RegistryObject<Item> CHOCOLATE_CANNON_ITEM = ITEMS.register("chocolate_cannon",
            () -> new MekCkBlockItem(CHOCOLATE_CANNON_BLOCK.get(), new Item.Properties(),
                    1, ChocolateCannonBlockEntity.ENERGY_PER_TICK, ChocolateCannonBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<ChocolateCannonBlockEntity>> CHOCOLATE_CANNON_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "chocolate_cannon", () -> BlockEntityType.Builder.of(ChocolateCannonBlockEntity::new, CHOCOLATE_CANNON_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<ChocolateCannonMenu>> CHOCOLATE_CANNON_MENU = MENUS.register(
            "chocolate_cannon", () -> IForgeMenuType.create(ChocolateCannonMenu::new));

    // Nut Roaster (坚果爆炒机：炒坚果加工 + 发射炒榛子攻击)
    public static final RegistryObject<Block> NUT_ROASTER_BLOCK = BLOCKS.register("nut_roaster", NutRoasterBlock::new);
    public static final RegistryObject<Item> NUT_ROASTER_ITEM = ITEMS.register("nut_roaster",
            () -> new MekCkBlockItem(NUT_ROASTER_BLOCK.get(), new Item.Properties(),
                    1, NutRoasterBlockEntity.ENERGY_PER_TICK, NutRoasterBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<BlockEntityType<NutRoasterBlockEntity>> NUT_ROASTER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nut_roaster", () -> BlockEntityType.Builder.of(NutRoasterBlockEntity::new, NUT_ROASTER_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<NutRoasterMenu>> NUT_ROASTER_MENU = MENUS.register(
            "nut_roaster", () -> IForgeMenuType.create(NutRoasterMenu::new));

    // ── 四合一基础机器（本轮新增，均无工厂版本）────────────────────────────
    public static final RegistryObject<Block> SUSHI_MAKER_BLOCK = BLOCKS.register("sushi_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.SUSHI_MAKER));
    public static final RegistryObject<Item> SUSHI_MAKER_ITEM = ITEMS.register("sushi_maker",
            () -> new MekCkBlockItem(SUSHI_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.SUSHI_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> AVERAGE_SLICER_BLOCK = BLOCKS.register("average_slicer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.AVERAGE_SLICER));
    public static final RegistryObject<Item> AVERAGE_SLICER_ITEM = ITEMS.register("average_slicer",
            () -> new MekCkBlockItem(AVERAGE_SLICER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.AVERAGE_SLICER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> RICE_BALL_MAKER_BLOCK = BLOCKS.register("rice_ball_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.RICE_BALL_MAKER));
    public static final RegistryObject<Item> RICE_BALL_MAKER_ITEM = ITEMS.register("rice_ball_maker",
            () -> new MekCkBlockItem(RICE_BALL_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.RICE_BALL_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> CURD_MAKER_BLOCK = BLOCKS.register("curd_maker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.CURD_MAKER));
    public static final RegistryObject<Item> CURD_MAKER_ITEM = ITEMS.register("curd_maker",
            () -> new MekCkBlockItem(CURD_MAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.CURD_MAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> DEHYDRATOR_BLOCK = BLOCKS.register("dehydrator",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.DEHYDRATOR));
    public static final RegistryObject<Item> DEHYDRATOR_ITEM = ITEMS.register("dehydrator",
            () -> new MekCkBlockItem(DEHYDRATOR_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.DEHYDRATOR.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> FERMENTER_BLOCK = BLOCKS.register("fermenter",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.FERMENTER));
    public static final RegistryObject<Item> FERMENTER_ITEM = ITEMS.register("fermenter",
            () -> new MekCkBlockItem(FERMENTER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.FERMENTER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> STEAMER_BLOCK = BLOCKS.register("steamer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.STEAMER));
    public static final RegistryObject<Item> STEAMER_ITEM = ITEMS.register("steamer",
            () -> new MekCkBlockItem(STEAMER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.STEAMER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> WINERY_BLOCK = BLOCKS.register("winery",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.WINERY));
    public static final RegistryObject<Item> WINERY_ITEM = ITEMS.register("winery",
            () -> new MekCkBlockItem(WINERY_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.WINERY.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> JUICER_BLOCK = BLOCKS.register("juicer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.JUICER));
    public static final RegistryObject<Item> JUICER_ITEM = ITEMS.register("juicer",
            () -> new MekCkBlockItem(JUICER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.JUICER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));
    public static final RegistryObject<Block> BAKERY_OVEN_BLOCK = BLOCKS.register("bakery_oven",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BAKERY_OVEN));
    public static final RegistryObject<Item> BAKERY_OVEN_ITEM = ITEMS.register("bakery_oven",
            () -> new MekCkBlockItem(BAKERY_OVEN_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BAKERY_OVEN.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<Block> STOVE_BLOCK = BLOCKS.register("stove",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.STOVE));
    public static final RegistryObject<Item> STOVE_ITEM = ITEMS.register("stove",
            () -> new MekCkBlockItem(STOVE_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.STOVE.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 调酒机（酒馆 shaker 联动：3 个酒类/原料 → 鸡尾酒）。 */
    public static final RegistryObject<Block> COCKTAIL_SHAKER_BLOCK = BLOCKS.register("cocktail_shaker",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.COCKTAIL_SHAKER));
    public static final RegistryObject<Item> COCKTAIL_SHAKER_ITEM = ITEMS.register("cocktail_shaker",
            () -> new MekCkBlockItem(COCKTAIL_SHAKER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.COCKTAIL_SHAKER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 搅拌机（烘焙坊 blender 联动：1~9 输入 → 1 输出，9 输入槽）。 */
    public static final RegistryObject<Block> BLENDER_BLOCK = BLOCKS.register("blender",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BLENDER));
    public static final RegistryObject<Item> BLENDER_ITEM = ITEMS.register("blender",
            () -> new MekCkBlockItem(BLENDER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BLENDER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    // ── 三个最终等级的工厂安装器（无尽贪婪材料配色，奇点创世为 16 帧变色动画） ──
    public static final RegistryObject<Item> BLAZE_TIER_INSTALLER_ITEM = ITEMS.register(
            "blaze_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.BLAZE, "炽火之斩骨刃", false));
    public static final RegistryObject<Item> CRYSTAL_MATRIX_TIER_INSTALLER_ITEM = ITEMS.register(
            "crystal_matrix_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.CRYSTAL_MATRIX, "水晶矩阵锭", false));
    public static final RegistryObject<Item> NEBULA_TIER_INSTALLER_ITEM = ITEMS.register(
            "nebula_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.NEBULA, "中子锭", false));
    public static final RegistryObject<Item> SINGULARITY_TIER_INSTALLER_ITEM = ITEMS.register(
            "singularity_tier_installer",
            () -> new cn.ism.mekck.item.MekCkTierInstallerItem(new Item.Properties().stacksTo(16),
                    CuttingMachineFactoryTier.SINGULARITY, "无尽锭", true));

    public static final RegistryObject<Block> TEA_BREWER_BLOCK = BLOCKS.register("tea_brewer",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.TEA_BREWER));
    public static final RegistryObject<Item> TEA_BREWER_ITEM = ITEMS.register("tea_brewer",
            () -> new MekCkBlockItem(TEA_BREWER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.TEA_BREWER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 智能萃取机（F8/F11 §四.1）：create:mixing 流体产物（茶/咖啡/溶糖/糖浆系 + oreo 酱破例）。 */
    public static final RegistryObject<Block> SMART_EXTRACTOR_BLOCK = BLOCKS.register("smart_extractor",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.SMART_EXTRACTOR));
    public static final RegistryObject<Item> SMART_EXTRACTOR_ITEM = ITEMS.register("smart_extractor",
            () -> new MekCkBlockItem(SMART_EXTRACTOR_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.SMART_EXTRACTOR.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 饮品调配机（F8/F11 §四.2）：读自有类型 {@code mekck:beverage_assembly}。 */
    public static final RegistryObject<Block> BEVERAGE_BLENDER_BLOCK = BLOCKS.register("beverage_blender",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.BEVERAGE_BLENDER));
    public static final RegistryObject<Item> BEVERAGE_BLENDER_ITEM = ITEMS.register("beverage_blender",
            () -> new MekCkBlockItem(BEVERAGE_BLENDER_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.BEVERAGE_BLENDER.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    /** 包材组装机（F7/F11 §四.4）：只读自有类型 {@code mekck:packaging}，仅产包材。 */
    public static final RegistryObject<Block> PACKAGING_STATION_BLOCK = BLOCKS.register("packaging_station",
            () -> new cn.ism.mekck.block.SimpleMachineBlock(cn.ism.mekck.MachineKind.PACKAGING_STATION));
    public static final RegistryObject<Item> PACKAGING_STATION_ITEM = ITEMS.register("packaging_station",
            () -> new MekCkBlockItem(PACKAGING_STATION_BLOCK.get(), new Item.Properties(),
                    1, cn.ism.mekck.MachineKind.PACKAGING_STATION.energyPerTick, cn.ism.mekck.blockentity.SimpleMachineBlockEntity.ENERGY_CAPACITY));

    public static final RegistryObject<BlockEntityType<cn.ism.mekck.blockentity.SimpleMachineBlockEntity>> SIMPLE_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "simple_machine", () -> BlockEntityType.Builder.of(cn.ism.mekck.blockentity.SimpleMachineBlockEntity::new,
                    SUSHI_MAKER_BLOCK.get(), AVERAGE_SLICER_BLOCK.get(), RICE_BALL_MAKER_BLOCK.get(), CURD_MAKER_BLOCK.get(),
                    DEHYDRATOR_BLOCK.get(), FERMENTER_BLOCK.get(), STEAMER_BLOCK.get(),
                    WINERY_BLOCK.get(), JUICER_BLOCK.get(), BAKERY_OVEN_BLOCK.get(), STOVE_BLOCK.get(),
                    COCKTAIL_SHAKER_BLOCK.get(), BLENDER_BLOCK.get(), TEA_BREWER_BLOCK.get(),
                    SMART_EXTRACTOR_BLOCK.get(), BEVERAGE_BLENDER_BLOCK.get(), PACKAGING_STATION_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<cn.ism.mekck.menu.SimpleMachineMenu>> SIMPLE_MACHINE_MENU = MENUS.register(
            "simple_machine", () -> IForgeMenuType.create(cn.ism.mekck.menu.SimpleMachineMenu::new));

    // 冷萃升级物品（5 种）
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
    public static final RegistryObject<FluidType> HAZELNUT_COCOA_PASTE_FLUID_TYPE = FLUID_TYPES.register("hazelnut_cocoa_paste", () -> new FluidType(
            FluidType.Properties.create()
                    .descriptionId("fluid_type.mekck.hazelnut_cocoa_paste")
                    .density(1100)
                    .viscosity(2500)
                    .temperature(320)) {
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_still");
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_flow");
                }

                @Override
                public int getTintColor() {
                    return 0xFF6B3A1F;
                }
            });
        }
    });
    public static final RegistryObject<ForgeFlowingFluid.Source> HAZELNUT_COCOA_PASTE_SOURCE = FLUIDS.register("hazelnut_cocoa_paste",
            () -> new ForgeFlowingFluid.Source(hazelnutCocoaPasteFluidProperties()));
    public static final RegistryObject<ForgeFlowingFluid.Flowing> HAZELNUT_COCOA_PASTE_FLOWING = FLUIDS.register("hazelnut_cocoa_paste_flowing",
            () -> new ForgeFlowingFluid.Flowing(hazelnutCocoaPasteFluidProperties()));
    public static final RegistryObject<LiquidBlock> HAZELNUT_COCOA_PASTE_BLOCK = BLOCKS.register("hazelnut_cocoa_paste",
            () -> new LiquidBlock(HAZELNUT_COCOA_PASTE_SOURCE, net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                    .noCollission().strength(100.0F).pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
                    .noLootTable()));
    public static final RegistryObject<Item> HAZELNUT_COCOA_PASTE_BUCKET = ITEMS.register("hazelnut_cocoa_paste_bucket",
            () -> new BucketItem(HAZELNUT_COCOA_PASTE_SOURCE, new Item.Properties().stacksTo(1).craftRemainder(net.minecraft.world.item.Items.BUCKET)));

    private static ForgeFlowingFluid.Properties hazelnutCocoaPasteFluidProperties() {
        return new ForgeFlowingFluid.Properties(HAZELNUT_COCOA_PASTE_FLUID_TYPE, HAZELNUT_COCOA_PASTE_SOURCE, HAZELNUT_COCOA_PASTE_FLOWING)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(10)
                .block(HAZELNUT_COCOA_PASTE_BLOCK).bucket(HAZELNUT_COCOA_PASTE_BUCKET);
    }

    // 有机物流体（生物反应堆专用，无方块/桶，仅供流体罐存储）
    public static final RegistryObject<FluidType> ORGANIC_MATTER_FLUID_TYPE = FLUID_TYPES.register("organic_matter", () -> new FluidType(
            FluidType.Properties.create()
                    .descriptionId("fluid_type.mekck.organic_matter")
                    .density(1050)
                    .viscosity(2500)) {
        @Override
        public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
            consumer.accept(new IClientFluidTypeExtensions() {
                @Override
                public net.minecraft.resources.ResourceLocation getStillTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_still");
                }

                @Override
                public net.minecraft.resources.ResourceLocation getFlowingTexture() {
                    return new net.minecraft.resources.ResourceLocation("minecraft:block/water_flow");
                }

                @Override
                public int getTintColor() {
                    return 0xFF2E8B57;
                }
            });
        }
    });
    public static final RegistryObject<ForgeFlowingFluid.Source> ORGANIC_MATTER_SOURCE = FLUIDS.register("organic_matter",
            () -> new ForgeFlowingFluid.Source(organicMatterFluidProperties()));
    public static final RegistryObject<ForgeFlowingFluid.Flowing> ORGANIC_MATTER_FLOWING = FLUIDS.register("organic_matter_flowing",
            () -> new ForgeFlowingFluid.Flowing(organicMatterFluidProperties()));

    private static ForgeFlowingFluid.Properties organicMatterFluidProperties() {
        // 液体流动参数：仅作存储使用，不生成方块
        return new ForgeFlowingFluid.Properties(ORGANIC_MATTER_FLUID_TYPE, ORGANIC_MATTER_SOURCE, ORGANIC_MATTER_FLOWING)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(10);
    }

    // 生物反应堆（2×2×3 多方块，物品→有机物流体→能量）
    public static final RegistryObject<Block> BIOREACTOR_BLOCK = BLOCKS.register("bioreactor", BioreactorBlock::new);
    public static final RegistryObject<Item> BIOREACTOR_ITEM = ITEMS.register("bioreactor",
            () -> new cn.ism.mekck.item.BioreactorBlockItem(BIOREACTOR_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<BioreactorBlockEntity>> BIOREACTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "bioreactor",
            () -> BlockEntityType.Builder.of(BioreactorBlockEntity::new, BIOREACTOR_BLOCK.get()).build(null));
    // 生物反应堆的绑定块（2×2×3 其余 11 格）：专用类，碰撞/拾取外形为整块立方体，保证不可穿模
    public static final RegistryObject<Block> BIOREACTOR_BOUNDING_BLOCK = BLOCKS.register("bioreactor_bounding", BioreactorBoundingBlock::new);
    public static final RegistryObject<MenuType<BioreactorMenu>> BIOREACTOR_MENU = MENUS.register(
            "bioreactor", () -> IForgeMenuType.create(BioreactorMenu::new));

    // Smart Cooking Pot
    public static final RegistryObject<Block> COOKING_POT_BLOCK = BLOCKS.register("smart_cooking_pot", SmartCookingPotBlock::new);
    public static final RegistryObject<Item> COOKING_POT_ITEM = ITEMS.register("smart_cooking_pot",
            () -> new MekCkBlockItem(COOKING_POT_BLOCK.get(), new Item.Properties(), 1, 20, 100000, true));
    public static final RegistryObject<BlockEntityType<SmartCookingPotBlockEntity>> COOKING_POT_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smart_cooking_pot",
            () -> BlockEntityType.Builder.of(SmartCookingPotBlockEntity::new, COOKING_POT_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<SmartCookingPotMenu>> COOKING_POT_MENU = MENUS.register(
            "smart_cooking_pot", () -> IForgeMenuType.create(SmartCookingPotMenu::new));

    // Smart Skewering Machine
    public static final RegistryObject<Block> SKEWERING_MACHINE_BLOCK = BLOCKS.register("smart_skewering_machine", SkeweringMachineBlock::new);
    public static final RegistryObject<Item> SKEWERING_MACHINE_ITEM = ITEMS.register("smart_skewering_machine",
            () -> new MekCkBlockItem(SKEWERING_MACHINE_BLOCK.get(), new Item.Properties(), 1, 20, 100000, true));
    public static final RegistryObject<BlockEntityType<SkeweringMachineBlockEntity>> SKEWERING_MACHINE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "smart_skewering_machine",
            () -> BlockEntityType.Builder.of(SkeweringMachineBlockEntity::new, SKEWERING_MACHINE_BLOCK.get()).build(null));
    public static final RegistryObject<MenuType<SkeweringMachineMenu>> SKEWERING_MACHINE_MENU = MENUS.register(
            "smart_skewering_machine", () -> IForgeMenuType.create(SkeweringMachineMenu::new));

    // Electric Grill
    // 阶段 3：改走 Mek 的注册器，**注册名一字不改**（仍是 mekck:electric_grill），
    // 旧存档里已放置的方块因此不会变空气。
    public static final mekanism.common.registration.impl.BlockDeferredRegister GRILL_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRILL_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRILL_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烧烤架方块（Mek 体系下的真实句柄）。 */
    public static final mekanism.common.registration.impl.BlockRegistryObject<GrillBlock, MekCkBlockItem> GRILL_HANDLE;
    /** 已注册的烧烤架 tile 类型，供 BlockType 的延迟 Supplier 回查。 */
    public static final mekanism.common.registration.impl.TileEntityTypeRegistryObject<GrillBlockEntity> GRILL_TILE;
    /** 烧烤架容器类型（注册名与旧的 mekck:electric_grill 逐字相同）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrillMenu> GRILL_CONTAINER;

    /**
     * 兼容面：{@code JEIPlugin} 与本类 1618 行按旧类型 {@code RegistryObject} 读这两个字段，
     * {@code registryView} 让它们一行都不用改。
     */
    public static final RegistryObject<Block> GRILL_BLOCK;
    public static final RegistryObject<Item> GRILL_ITEM;

    private static final UnaryOperator<BlockBehaviour.Properties> GRILL_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Cooking Factory blocks, items, and block entities
    // Cooking Factory：阶段 3 Task 7 改走 Mek 的注册器，
    // **注册名一字不改**（仍是 mekck:<tier>_cooking_factory）。
    public static final mekanism.common.registration.impl.BlockDeferredRegister COOKING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister COOKING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister COOKING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烹饪方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<CookingFactoryBlock, MekCkBlockItem>> COOKING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cooking.CookingFactoryTile>> COOKING_FACTORY_TILES =
            new LinkedHashMap<>();
    /** 兼容面：外部文件按旧类型读这两个 map，{@code registryView} 让它们一行都不用改。 */
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> COOKING_FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> COOKING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:cooking_factory 逐字相同）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<CookingFactoryMenu> COOKING_FACTORY_CONTAINER;

    private static final UnaryOperator<BlockBehaviour.Properties> COOKING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Skewering Factory blocks, items, and block entities
    // Skewering Factory：阶段 3 Task 5 改走 Mek 的注册器，
    // **注册名一字不改**（仍是 mekck:<tier>_skewering_factory）。
    public static final mekanism.common.registration.impl.BlockDeferredRegister SKEWERING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister SKEWERING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister SKEWERING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的穿串方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<SkeweringFactoryBlock, MekCkBlockItem>> SKEWERING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.skewering.SkeweringFactoryTile>> SKEWERING_FACTORY_TILES =
            new LinkedHashMap<>();
    /** 兼容面：外部文件按旧类型读这两个 map，{@code registryView} 让它们一行都不用改。 */
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> SKEWERING_FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> SKEWERING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:skewering_factory 逐字相同）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<SkeweringFactoryMenu> SKEWERING_FACTORY_CONTAINER;

    private static final UnaryOperator<BlockBehaviour.Properties> SKEWERING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Grill Factory blocks, items, and block entities
    // 阶段 3 Task 3：改走 Mek 的注册器，**注册名一字不改**（仍是 mekck:<tier>_grill_factory），
    // 旧存档里已放置的方块因此不会变空气。
    public static final mekanism.common.registration.impl.BlockDeferredRegister GRILL_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRILL_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRILL_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的烧烤方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<GrillFactoryBlock, MekCkBlockItem>> GRILL_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.grill.GrillFactoryTile>> GRILL_FACTORY_TILES =
            new LinkedHashMap<>();
    /**
     * 兼容面：本任务之外的文件按旧类型 {@code RegistryObject} 读这两个 map，
     * {@code registryView} 让外部一行都不用改。
     */
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> GRILL_FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> GRILL_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:grill_factory 逐字相同）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrillFactoryMenu> GRILL_FACTORY_CONTAINER;

    private static final UnaryOperator<BlockBehaviour.Properties> GRILL_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Planting & Cutting Factory blocks, items, and block entities
    public static final mekanism.common.registration.impl.BlockDeferredRegister PLANTING_CUTTING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister PLANTING_CUTTING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister PLANTING_CUTTING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的种植切配方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<PlantingCuttingFactoryBlock, MekCkBlockItem>> PLANTING_CUTTING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile>> PLANTING_CUTTING_FACTORY_TILES =
            new LinkedHashMap<>();
    /**
     * 已注册的方块 / 物品（按等级索引）。
     *
     * <p><b>兼容面</b>：Mek 的注册器产出的是自己的
     * {@code BlockRegistryObject}，而本任务之外的文件（{@code MekckAe2} /
     * {@code JEIPlugin} / {@code MekCkBlockItem}）仍按原版 {@code RegistryObject}
     * 读这两个 map。用 {@code registryView} 造一个指向同一注册项的视图，
     * 这样外部文件一行都不用改。
     */
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> PLANTING_CUTTING_FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> PLANTING_CUTTING_FACTORY_ITEMS = new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名与旧的 mekck:planting_cutting_factory 逐字相同）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<PlantingCuttingFactoryMenu> PLANTING_CUTTING_CONTAINER;

    private static final UnaryOperator<BlockBehaviour.Properties> PLANTING_CUTTING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Planting & Cutting Factory block entity references


    // Grill Factory block entity references
    // 旧的 11 个 *_GRILL_FACTORY_BLOCK_ENTITY 字段与 GRILL_FACTORY_BLOCK_ENTITIES 已删
    // （阶段 3 Task 3）：它们强绑已删的 GrillFactoryBlockEntity，同一个注册名只能挂一个
    // BlockEntityType。活的 tile 句柄在 GRILL_FACTORY_TILES。

    // Skewering Factory block entity references
    // 穿串工厂的旧 BE 字段与 SKEWERING_FACTORY_BLOCK_ENTITIES 已删（阶段 3 Task 5）：
    // 它们强绑已删的 SkeweringFactoryBlockEntity，同一个注册名只能挂一个 BlockEntityType。
    // 活的 tile 句柄在 SKEWERING_FACTORY_TILES。

    // Factory blocks, items, and block entities
    //
    // ⚠️ 切菜工厂的**实际注册**在下面 CUTTING_FACTORY_*_REG 那一组（Mek 的 BlockDeferredRegister /
    //    TileEntityTypeDeferredRegister / ContainerTypeDeferredRegister），注册 ID 不变
    //    （仍是 mekck:<tier>_cutting_factory），因此旧存档里已放置的方块不会变成空气。
    //    下面这两个 map 保留旧类型不变，是**给本任务之外的文件用的兼容面**：
    //    TierInstallerHandler 与 JEIPlugin 一律按 Map<..., RegistryObject<Block>> 遍历读取。
    //    它们现在装的是 registryView(...) 造出来的同名注册项视图，语义与原来完全一致。
    //
    // ⚠️ 这里**没有** 11 个 BASIC_FACTORY_BLOCK / ADVANCED_FACTORY_BLOCK 之类的逐档别名字段。
    //    它们此前存在且恰好只有 11 个（唯独没有 BLAZE），于是 JEPlugin 的切菜催化剂注册
    //    顺着手写成了 11 行 —— 烈焰等级的切菜工厂在 JEI 里查不到。别名字段一旦没人读，
    //    留着就只会诱导下一个人再手写一遍档位清单。要单档引用请写
    //    `FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)`。
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> FACTORY_ITEMS = new LinkedHashMap<>();

    // 切菜工厂原先的 11 个 *_FACTORY_BLOCK_ENTITY 字段与 FACTORY_BLOCK_ENTITIES 集合，
    // 已随 blockentity/CuttingMachineFactoryBlockEntity 于阶段 2 Task 5 一并删除：
    // 它们恒为 null（同一个注册名 mekck:<tier>_cutting_factory 只能挂一个 BlockEntityType，
    // 旧的早已被 CuttingFactoryTile 顶替），留着等于永久静默的 null。活的 tile 句柄在下面的
    // CUTTING_FACTORY_TILES。
    //
    // 「11 个」不是笔误：档位枚举有 12 个值，但 BLAZE 从来就没有对应的 *_FACTORY_BLOCK_ENTITY
    // 字段（删除前实测如此），Task 5 不新增也不补齐。
    //
    // ⚠️ 不要按名字相似去删其余家族的 *_FACTORY_BLOCK_ENTITY：本类现存 55 个这类字段，
    //    全部仍被其余 6 个家族的旧 BlockEntity 真实使用（各 XxxFactoryBlock.getTileType
    //    与 XxxFactoryBlockEntity 构造都按名读它们），删任何一个都会编译断。

    // 逐档别名字段（BASIC_FACTORY_BLOCK / … / SINGULARITY_FACTORY_ITEM 共 22 个）已删除：
    // 实测全仓零读取方，且恰好缺 BLAZE 一档，是 JEI 切菜催化剂漏档的直接诱因。
    // 需要单档引用时写 FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)。

    public static final RegistryObject<MenuType<CuttingMachineFactoryMenu>> FACTORY_MENU;

    // ── 切菜工厂的 Mek 原生注册（阶段 2 Task 4）──────────────────────────
    //
    // 为什么单独一组注册而不是复用 Mekck 自己的 DeferredRegister：
    //   * BlockDeferredRegister 会自动挂一个 BlockItem，而切菜工厂的方块物品是
    //     MekCkBlockItem（带等级 tooltip 与 saveToItem），走 register(id, supplier, itemFn) 覆盖；
    //   * TileEntityTypeDeferredRegister 建的是 TileEntityMekanism 的注册对象，
    //     CUTTING_FACTORY_TILES 供 BlockType 的延迟 Supplier 回查；
    //   * ContainerTypeDeferredRegister 注册进同一个 MENU_TYPES 注册表，
    //     所以下面 registryView("factory") 造出来的 FACTORY_MENU 视图指向的就是它，
    //     注册名 mekck:factory 与旧实现逐字相同。

    public static final mekanism.common.registration.impl.BlockDeferredRegister CUTTING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister CUTTING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister CUTTING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的切菜方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<CuttingMachineFactoryBlock, MekCkBlockItem>> CUTTING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的切菜 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.cutting.CuttingFactoryTile>> CUTTING_FACTORY_TILES =
            new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（与旧的 {@code mekck:factory} 同名）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<CuttingMachineFactoryMenu> FACTORY_CONTAINER;

    /**
     * 切菜方块的属性微调。
     *
     * <p>{@code BlockTile} 的构造器已经铺好了
     * {@code Properties.of().strength(3.5F, 16.0F).requiresCorrectToolForDrops()}
     * （实测 {@code BlockTile} 构造器字节码偏移 3~16），因此这里只需补回旧实现独有的一项
     * {@code sound(METAL)}——丢失它会让挖/放的声音变成默认的石头声。</p>
     *
     * <p><b>必须声明在下方 static 块之前</b>：静态字段初始化器与 static 块按<b>文本顺序</b>执行，
     * 在 static 块里引用一个声明在它之后的 static final 字段会编译报「非法前向引用」。</p>
     */
    private static final UnaryOperator<BlockBehaviour.Properties> CUTTING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // Grinding Factory blocks, items, and block entities
    //
    // ── 研磨工厂的 Mek 原生注册（阶段 3 Task 1）───────────────────────────────
    // 与切菜工厂同一套做法：注册名不变（仍是 mekck:<tier>_grinding_factory），
    // 变的是方块与 tile 的实现类。旧存档的内容由 MekCkLegacyMachineNbt
    // 在读档时整体翻译，新存档由 MekCkMachineTile 写出。
    public static final mekanism.common.registration.impl.BlockDeferredRegister GRINDING_FACTORY_BLOCKS_REG =
            new mekanism.common.registration.impl.BlockDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.TileEntityTypeDeferredRegister GRINDING_FACTORY_TILES_REG =
            new mekanism.common.registration.impl.TileEntityTypeDeferredRegister(MOD_ID);
    public static final mekanism.common.registration.impl.ContainerTypeDeferredRegister GRINDING_FACTORY_CONTAINERS_REG =
            new mekanism.common.registration.impl.ContainerTypeDeferredRegister(MOD_ID);

    /** 已注册的研磨方块（按等级索引），Mek 体系下的真实句柄。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.BlockRegistryObject<GrindingFactoryBlock, MekCkBlockItem>> GRINDING_FACTORY_HANDLES =
            new LinkedHashMap<>();
    /** 已注册的研磨 tile 类型（按等级索引），供 BlockType 的延迟 Supplier 回查。 */
    public static final Map<CuttingMachineFactoryTier,
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<cn.ism.mekck.machine.grinding.GrindingFactoryTile>> GRINDING_FACTORY_TILES =
            new LinkedHashMap<>();

    /** 12 个等级共用一个容器类型（注册名仍是 mekck:grinding_factory）。 */
    public static final mekanism.common.registration.impl.ContainerTypeRegistryObject<GrindingFactoryMenu> GRINDING_FACTORY_CONTAINER;

    /** 研磨方块的属性微调：补回旧实现独有的 sound(METAL)。 */
    private static final UnaryOperator<BlockBehaviour.Properties> GRINDING_FACTORY_PROPERTIES =
            props -> props.sound(net.minecraft.world.level.block.SoundType.METAL);

    // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / ClientEvents）
    // 按旧类型 Map<..., RegistryObject<Block>> 读这两个 map。
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Block>> GRINDING_FACTORY_BLOCKS = new LinkedHashMap<>();
    public static final Map<CuttingMachineFactoryTier, RegistryObject<Item>> GRINDING_FACTORY_ITEMS = new LinkedHashMap<>();

    // 旧的 11 个 *_GRINDING_FACTORY_BLOCK_ENTITY 字段与 GRINDING_FACTORY_BLOCK_ENTITIES 已删（阶段 3 Task 1）：
    // 它们强绑已删的 GrindingFactoryBlockEntity，同一个注册名只能挂一个 BlockEntityType。
    // 活的 tile 句柄在 GRINDING_FACTORY_TILES。
    public static final RegistryObject<MenuType<GrindingFactoryMenu>> GRINDING_FACTORY_MENU;

    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<Block>> ICE_FACTORY_BLOCKS = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);
    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<Item>> ICE_FACTORY_ITEMS = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);
    public static final java.util.Map<CuttingMachineFactoryTier, RegistryObject<BlockEntityType<IceFactoryBlockEntity>>> ICE_FACTORY_BLOCK_ENTITIES = new java.util.EnumMap<>(CuttingMachineFactoryTier.class);

    /**
     * 制冰工厂菜单类型 —— <b>在 {@link MekckConfig#isIceFactoryEnabled()} 为 false 时为 {@code null}</b>。
     *
     * <p>这个 {@code null} 是<b>刻意保留的空哨兵</b>，不是「忘了赋值」：整族禁用时
     * 12 个方块与方块实体都不注册，若仍注册一个 {@code MenuType}，
     * 就会往注册表里塞一个没有任何方块能打开的条目 —— 而本开关的语义是
     * 「关掉整族」，注册表里就不该留残迹。</p>
     *
     * <p><b>唯一读取方是 {@link IceFactoryMenu} 的 {@code super(...)} 调用</b>，
     * 而它为 null 时不可能被构造（两道门）：</p>
     * <ol>
     *   <li>服务端：只有 {@code IceFactoryBlockEntity} 的 {@code createMenu} 会 new 它，
     *       而那个方块实体挂在 {@code IceFactoryBlock} 上 —— 禁用时方块不存在；</li>
     *   <li>客户端：只能由本字段注册出的 {@code MenuType} 反射构造，
     *       而禁用时 {@code MenuType} 同样没注册。</li>
     * </ol>
     * <p>所以那个 {@code .get()} 不会真的抛 NPE。护栏：
     * {@code TestIceFactoryToggle} 会核对「为 null ⇔ 开关为关」这两个分支同时成立。</p>
     */
    public static final RegistryObject<MenuType<IceFactoryMenu>> ICE_FACTORY_MENU;

    static {
        // Register all factory blocks
        //
        // 切菜工厂（阶段 2 Task 4）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_cutting_factory），旧存档里已放置的方块因此不会变空气。
        FACTORY_CONTAINER = CUTTING_FACTORY_CONTAINERS_REG.register(
                "factory", cn.ism.mekck.machine.cutting.CuttingFactoryTile.class, CuttingMachineFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_cutting_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_cutting_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_cutting_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            // 三个 Supplier 都只在 Mek 真正求值的时刻（放置 / 开 GUI）才被调用，那时注册早已完成。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.cutting.CuttingFactoryTile> blockType =
                    CuttingMachineFactoryBlock.blockTypeFor(tier, () -> FACTORY_CONTAINER, () -> findFactoryTile(CUTTING_FACTORY_TILES, tier, "切菜工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<CuttingMachineFactoryBlock, MekCkBlockItem> handle =
                    CUTTING_FACTORY_BLOCKS_REG.register(id,
                            () -> new CuttingMachineFactoryBlock(blockType, tier, CUTTING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            CUTTING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底（实测字节码：ifeq 取 serverTicker / else 取
            // clientTicker，直接 areturn）。不填就是 null，而 Level 只在 ticker 非 null 时才
            // 驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            CUTTING_FACTORY_TILES.put(tier, CUTTING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.cutting.CuttingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin）按旧类型读这两个 map。
            FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        // 逐档别名赋值（原 24 行 BASIC_FACTORY_BLOCK = FACTORY_BLOCKS.get(...) 等）已删除，
        // 理由同字段声明处：全仓零读取方，且缺 BLAZE 档导致 JEI 漏注册催化剂。
        // 单档引用请直接写 FACTORY_BLOCKS.get(CuttingMachineFactoryTier.XXX)。

        // 切菜各档的 tile 句柄在 CUTTING_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_FACTORY_BLOCK_ENTITY = FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 2 Task 5 一并删除。

        // 烹饪工厂（阶段 3 Task 7）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_cooking_factory）。
        COOKING_FACTORY_CONTAINER = COOKING_FACTORY_CONTAINERS_REG.register(
                "cooking_factory", cn.ism.mekck.machine.cooking.CookingFactoryTile.class,
                CookingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getCookingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_cooking_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_cooking_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_cooking_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.cooking.CookingFactoryTile> blockType =
                    CookingFactoryBlock.blockTypeFor(tier, () -> COOKING_FACTORY_CONTAINER,
                            () -> findFactoryTile(COOKING_FACTORY_TILES, tier, "烹饪工厂"));

            // 末位 true = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            mekanism.common.registration.impl.BlockRegistryObject<CookingFactoryBlock, MekCkBlockItem> handle =
                    COOKING_FACTORY_BLOCKS_REG.register(id,
                            () -> new CookingFactoryBlock(blockType, tier, COOKING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, true));
            COOKING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            COOKING_FACTORY_TILES.put(tier, COOKING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.cooking.CookingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            COOKING_FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            COOKING_FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        // 穿串工厂（阶段 3 Task 5）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_skewering_factory）。
        SKEWERING_FACTORY_CONTAINER = SKEWERING_FACTORY_CONTAINERS_REG.register(
                "skewering_factory", cn.ism.mekck.machine.skewering.SkeweringFactoryTile.class,
                SkeweringFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getSkeweringBlockId();
            Component desc = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_skewering_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_skewering_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_skewering_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.skewering.SkeweringFactoryTile> blockType =
                    SkeweringFactoryBlock.blockTypeFor(tier, () -> SKEWERING_FACTORY_CONTAINER,
                            () -> findFactoryTile(SKEWERING_FACTORY_TILES, tier, "穿串工厂"));

            // 末位 true = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            mekanism.common.registration.impl.BlockRegistryObject<SkeweringFactoryBlock, MekCkBlockItem> handle =
                    SKEWERING_FACTORY_BLOCKS_REG.register(id,
                            () -> new SkeweringFactoryBlock(blockType, tier, SKEWERING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), desc, tier, true));
            SKEWERING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            SKEWERING_FACTORY_TILES.put(tier, SKEWERING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.skewering.SkeweringFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            SKEWERING_FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            SKEWERING_FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        // 烧烤工厂（阶段 3 Task 3）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_grill_factory），
        // 旧存档里已放置的方块因此不会变空气。
        GRILL_FACTORY_CONTAINER = GRILL_FACTORY_CONTAINERS_REG.register(
                "grill_factory", cn.ism.mekck.machine.grill.GrillFactoryTile.class, GrillFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getGrillingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_grill_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_grill_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_grill_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.grill.GrillFactoryTile> blockType =
                    GrillFactoryBlock.blockTypeFor(tier, () -> GRILL_FACTORY_CONTAINER,
                            () -> findFactoryTile(GRILL_FACTORY_TILES, tier, "烧烤工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<GrillFactoryBlock, MekCkBlockItem> handle =
                    GRILL_FACTORY_BLOCKS_REG.register(id,
                            () -> new GrillFactoryBlock(blockType, tier, GRILL_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            GRILL_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            GRILL_FACTORY_TILES.put(tier, GRILL_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.grill.GrillFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / MekckAe2）
            // 按旧类型读这两个 map。
            GRILL_FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            GRILL_FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        // 电力烧烤架（阶段 3）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:electric_grill）。
        GRILL_CONTAINER = GRILL_CONTAINERS_REG.register(
                "electric_grill", GrillBlockEntity.class, GrillMenu::new);
        {
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            // 两个 Supplier 都只在 Mek 真正求值的时刻（放置 / 开 GUI）才被调用，那时注册早已完成。
            mekanism.common.content.blocktype.BlockTypeTile<GrillBlockEntity> blockType =
                    GrillBlock.blockTypeFor(() -> GRILL_CONTAINER, () -> findGrillTile());

            // 末位 false = MekCkBlockItem 的 isCooking 标志（tooltip 用），旧实现同款。
            GRILL_HANDLE = GRILL_BLOCKS_REG.register("electric_grill",
                    () -> new GrillBlock(blockType, GRILL_PROPERTIES),
                    block -> new MekCkBlockItem(block, new Item.Properties(), 1, 20, 100000, false));
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时
            // 才驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            GRILL_TILE = GRILL_TILES_REG.register(GRILL_HANDLE,
                    (pos, state) -> new GrillBlockEntity(GRILL_HANDLE, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile));

            GRILL_BLOCK = registryView("electric_grill", ForgeRegistries.BLOCKS);
            GRILL_ITEM = registryView("electric_grill", ForgeRegistries.ITEMS);
        }

        // 种植切配工厂（阶段 3 Task 2）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_planting_cutting_factory），
        // 旧存档里已放置的方块因此不会变空气。
        PLANTING_CUTTING_CONTAINER = PLANTING_CUTTING_FACTORY_CONTAINERS_REG.register(
                "planting_cutting_factory", cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile.class,
                PlantingCuttingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getPlantingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_planting_cutting_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_planting_cutting_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_planting_cutting_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile> blockType =
                    PlantingCuttingFactoryBlock.blockTypeFor(tier, () -> PLANTING_CUTTING_CONTAINER,
                            () -> findFactoryTile(PLANTING_CUTTING_FACTORY_TILES, tier, "种植切配工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<PlantingCuttingFactoryBlock, MekCkBlockItem> handle =
                    PLANTING_CUTTING_FACTORY_BLOCKS_REG.register(id,
                            () -> new PlantingCuttingFactoryBlock(blockType, tier, PLANTING_CUTTING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            PLANTING_CUTTING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是
            // 原样返回存进去的那个，没有任何兜底（实测字节码：ifeq 取 serverTicker / else 取
            // clientTicker，直接 areturn）。不填就是 null，而 Level 只在 ticker 非 null 时才
            // 驱动方块实体 —— 机器会「放置成功、界面能开、就是不干活」。
            PLANTING_CUTTING_FACTORY_TILES.put(tier, PLANTING_CUTTING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.plantingcutting.PlantingCuttingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件（TierInstallerHandler / JEIPlugin / MekckAe2）
            // 按旧类型读这两个 map。
            PLANTING_CUTTING_FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            PLANTING_CUTTING_FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        // 研磨工厂（阶段 3 Task 1）：方块/物品/tile/容器全部走 Mek 的注册器，
        // **注册名一字不改**（仍是 mekck:<tier>_grinding_factory），旧存档里已放置的方块因此不会变空气。
        GRINDING_FACTORY_CONTAINER = GRINDING_FACTORY_CONTAINERS_REG.register(
                "grinding_factory", cn.ism.mekck.machine.grinding.GrindingFactoryTile.class, GrindingFactoryMenu::new);
        for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
            String id = tier.getGrindingBlockId();
            Component description = switch (tier) {
                case NEBULA -> Component.translatable("tooltip.mekck.nebula_grinding_factory");
                case BLAZE -> Component.translatable("tooltip.mekck.blaze_grinding_factory");
                case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_grinding_factory");
                default -> null;
            };
            // BlockType 需要 tile 与容器，但两者都必须先有方块 —— 用延迟 Supplier 破这个环。
            mekanism.common.content.blocktype.BlockTypeTile<cn.ism.mekck.machine.grinding.GrindingFactoryTile> blockType =
                    GrindingFactoryBlock.blockTypeFor(tier, () -> GRINDING_FACTORY_CONTAINER, () -> findFactoryTile(GRINDING_FACTORY_TILES, tier, "研磨工厂"));

            mekanism.common.registration.impl.BlockRegistryObject<GrindingFactoryBlock, MekCkBlockItem> handle =
                    GRINDING_FACTORY_BLOCKS_REG.register(id,
                            () -> new GrindingFactoryBlock(blockType, tier, GRINDING_FACTORY_PROPERTIES),
                            block -> new MekCkBlockItem(block, new Item.Properties(), description, tier, false));
            GRINDING_FACTORY_HANDLES.put(tier, handle);
            // 两个 ticker 都必须显式给：TileEntityTypeRegistryObject.getTicker(boolean) 只是原样返回存进去的那个，
            // 没有任何兜底。不填就是 null，而 Level 只在 ticker 非 null 时才驱动方块实体——
            // 机器会「放置成功、界面能开、就是不干活」。
            GRINDING_FACTORY_TILES.put(tier, GRINDING_FACTORY_TILES_REG.register(handle,
                    (pos, state) -> new cn.ism.mekck.machine.grinding.GrindingFactoryTile(handle, pos, state),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickClient(level, pos, state, tile),
                    (level, pos, state, tile) -> mekanism.common.tile.base.TileEntityMekanism.tickServer(level, pos, state, tile)));

            // 兼容面：本任务之外的文件按旧类型读这两个 map。
            GRINDING_FACTORY_BLOCKS.put(tier, registryView(id, ForgeRegistries.BLOCKS));
            GRINDING_FACTORY_ITEMS.put(tier, registryView(id, ForgeRegistries.ITEMS));
        }

        if (MekckConfig.isIceFactoryEnabled()) {
            // Register all ice factory blocks (急冻制冰工厂) —— 禁用时整段跳过，代码保留
            for (CuttingMachineFactoryTier tier : CuttingMachineFactoryTier.values()) {
                String id = tier.getIceMakerBlockId();
                RegistryObject<Block> block = BLOCKS.register(id, () -> new IceFactoryBlock(tier));
                RegistryObject<Item> item = ITEMS.register(id, () -> {
                    Component description = switch (tier) {
                        case NEBULA -> Component.translatable("tooltip.mekck.nebula_ice_factory");
                        case BLAZE -> Component.translatable("tooltip.mekck.blaze_ice_factory");
                        case SINGULARITY -> Component.translatable("tooltip.mekck.singularity_ice_factory");
                        default -> null;
                    };
                    return new MekCkBlockItem(block.get(), new Item.Properties(), description, tier, false);
                });
                RegistryObject<BlockEntityType<IceFactoryBlockEntity>> be = BLOCK_ENTITIES.register(
                        id, () -> BlockEntityType.Builder.of(
                                (pos, state) -> new IceFactoryBlockEntity(tier, pos, state),
                                block.get()).build(null));
                ICE_FACTORY_BLOCKS.put(tier, block);
                ICE_FACTORY_ITEMS.put(tier, item);
                ICE_FACTORY_BLOCK_ENTITIES.put(tier, be);
            }
            ICE_FACTORY_MENU = MENUS.register("ice_factory", () -> IForgeMenuType.create(IceFactoryMenu::new));
        } else {
            // 制冰工厂禁用：菜单不注册（方块/物品/方块实体循环整体跳过）
            ICE_FACTORY_MENU = null;
        }

        // 冰块实体（急冻制冰机攻击用）
        ICE_CUBE_ENTITY = ENTITY_TYPES.register("ice_cube", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.IceCubeEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.6F, 0.6F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ice_cube").toString()));

        // 费列罗巧克力实体（巧克力大炮攻击用）
        FERRERO_ENTITY = ENTITY_TYPES.register("ferrero_chocolate", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.FerreroEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.35F, 0.35F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ferrero_chocolate").toString()));

        // 炒榛子实体（坚果爆炒机发射的弹射物）
        ROASTED_HAZELNUT_ENTITY = ENTITY_TYPES.register("roasted_hazelnut", () -> EntityType.Builder
                .of(cn.ism.mekck.entity.RoastedHazelnutEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.35F, 0.35F)
                .setShouldReceiveVelocityUpdates(true)
                .build(ResourceLocation.fromNamespaceAndPath(MOD_ID, "roasted_hazelnut").toString()));

        // Assign specific planting & cutting factory references

        // Assign specific cooking factory references

        // 烧烤各档的 tile 句柄在 GRILL_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_GRILL_FACTORY_BLOCK_ENTITY = GRILL_FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 3 Task 3 一并删除。

        // 穿串各档的 tile 句柄在 SKEWERING_FACTORY_TILES（见上面的注册循环）。
        // 原先这里还有 11 行 BASIC_SKEWERING_FACTORY_BLOCK_ENTITY = SKEWERING_FACTORY_BLOCK_ENTITIES.get(...)，
        // 因目标 map 恒空而恒为 null，已随阶段 3 Task 5 一并删除。

        // Shared menu type for all factories
        //
        // Task 4 起容器由 Mek 的 ContainerTypeDeferredRegister 注册（同样落在 MENU_TYPES 注册表、
        // 同样叫 mekck:factory）。这里只造一个指向同一注册项的 RegistryObject 视图，
        // 让 ClientEvents 里的 MenuScreens.register(FACTORY_MENU.get(), ...) 照旧能写。
        FACTORY_MENU = registryView("factory", ForgeRegistries.MENU_TYPES);
        // 同理：研磨工厂的容器现在也是 Mek 的 ContainerTypeRegistryObject，
        // 这里造一个指向同一注册项的视图，让 ClientEvents 里的
        // MenuScreens.register(GRINDING_FACTORY_MENU.get(), ...) 照旧能写。
        GRINDING_FACTORY_MENU = registryView("grinding_factory", ForgeRegistries.MENU_TYPES);
    }

    /**
     * 造一个指向<b>已由别人注册</b>的注册项的 {@link RegistryObject} 视图。
     *
     * <h3>为什么需要它</h3>
     * 切菜工厂现在走 Mek 的 {@code BlockDeferredRegister} / {@code ContainerTypeDeferredRegister}，
     * 它们返回的是 {@code BlockRegistryObject} / {@code ContainerTypeRegistryObject}，
     * 而这两个类<b>不是</b> Forge 的 {@code RegistryObject}
     * （{@code WrappedRegistryObject} 只实现 {@code Supplier}；{@code RegistryObject} 本身是 final class，
     * 无法用适配器糊过去）。
     *
     * <p>但 {@code FACTORY_BLOCKS} / {@code FACTORY_ITEMS} / {@code FACTORY_MENU} 是本类的公开 API，
     * 本任务<b>之外</b>还有三个文件按原类型读它们：
     * {@code util/TierInstallerHandler}（两个 private 辅助方法，参数类型写死
     * {@code Map<..., RegistryObject<Block>>}）、{@code integration/jei/JEIPlugin}、
     * {@code ClientEvents}。改这三个字段的类型会波及任务清单之外的文件。
     *
     * <p>{@link RegistryObject#create} 正是为此存在的公开工厂：它按注册名订阅注册表，
     * 在注册事件里填充值，语义与 {@code DeferredRegister} 返回的那个一模一样，
     * 且类型就是 {@code RegistryObject<T>}。</p>
     *
     * @param id       不含命名空间的注册名
     * @param registry 目标注册表
     */
    private static <T, U extends T> RegistryObject<U> registryView(String id, net.minecraftforge.registries.IForgeRegistry<T> registry) {
        return RegistryObject.create(new ResourceLocation(MOD_ID, id), registry);
    }

    /**
     * 取回已注册的烧烤架 tile 类型 —— 给 {@code BlockTypeTile} 的延迟 Supplier 用。
     *
     * <p>必须延迟：{@code TILE_ENTITIES.register(block, ...)} 要求先有方块，而方块的
     * {@code BlockType} 构造时就要 tile 的 Supplier，形成先后依赖。理由同
     * {@link #findFactoryTile}。</p>
     */
    private static mekanism.common.registration.impl.TileEntityTypeRegistryObject<GrillBlockEntity> findGrillTile() {
        if (GRILL_TILE == null) {
            throw new IllegalStateException("电力烧烤架 tile 尚未注册（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return GRILL_TILE;
    }

    /**
     * 按等级取回某家族已注册的 tile 类型 —— 给 {@code BlockTypeTile} 的延迟 Supplier 用。
     *
     * <h3>为什么必须延迟</h3>
     * Mek 的 {@code TILES_REG.register(blockHandle, …)} 要求<b>先有方块</b>，
     * 而方块的 {@code BlockType} 构造时就已经要 tile 的 {@code Supplier} —— 先后依赖成环，
     * 只能靠延迟 Supplier 打破。两个 Supplier 都只在 Mek 真正求值的时刻
     * （放置 / 开 GUI）才被调用，那时注册早已完成。
     *
     * <h3>为什么值得抽成一个方法</h3>
     * 原先 5 个家族各有一份手抄的 {@code findXxxFactoryTile}，正文<b>逐字相同</b>、
     * 只有报错里的中文家族名不同。手抄 5 遍的错误信息本身就是隐患：某天把家族名拼错，
     * 排查的人会被指向错误的模块，而真正出错的是另一处。现在家族名由调用点传入。
     *
     * <p>这里的 {@code null} 检查不是防御性冗余，而是<b>唯一</b>能把「Supplier 被过早求值」
     * 这类注册顺序错误变成可读异常的地方 —— 静默返回 null 会让 Mek 在远端才 NPE，
     * 堆栈指向完全无关的类。</p>
     *
     * @param tiles  该家族的 {@code <tier, TileEntityTypeRegistryObject>} 表
     * @param tier   正在注册的档位
     * @param family 中文家族名，仅用于报错信息
     */
    private static <T extends net.minecraft.world.level.block.entity.BlockEntity>
            mekanism.common.registration.impl.TileEntityTypeRegistryObject<T> findFactoryTile(
                    java.util.Map<CuttingMachineFactoryTier,
                            mekanism.common.registration.impl.TileEntityTypeRegistryObject<T>> tiles,
                    CuttingMachineFactoryTier tier,
                    String family) {
        mekanism.common.registration.impl.TileEntityTypeRegistryObject<T> found = tiles.get(tier);
        if (found == null) {
            throw new IllegalStateException(family + " tile 尚未注册：tier=" + tier
                    + "（BlockTypeTile 的 Supplier 被过早求值）");
        }
        return found;
    }

    private static boolean firstPlayerJoined = false;
    /** 创造升级的 49 种食物提示是否已发过（与种植调试信息各自独立）。 */
    private static boolean creativeUpgradeHintSent = false;

    public UniversalCuttingMachine(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MENUS.register(bus);
        RECIPE_SERIALIZERS.register(bus);
        RECIPE_TYPES.register(bus);
        FLUID_TYPES.register(bus);
        FLUIDS.register(bus);
        ENTITY_TYPES.register(bus);
        MOB_EFFECTS.register(bus);
        // 切菜工厂（阶段 2 Task 4）：注册名仍是 mekck:<tier>_cutting_factory，
        // 但注册器换成 Mek 的——方块因此带上 AttributeGui / AttributeEnergy /
        // AttributeStateFacing / AttributeUpgradeSupport，tile 也就顺理成章地
        // 变成 TileEntityMekanism 家族的一员。
        CUTTING_FACTORY_BLOCKS_REG.register(bus);
        CUTTING_FACTORY_TILES_REG.register(bus);
        CUTTING_FACTORY_CONTAINERS_REG.register(bus);
        // 研磨工厂（阶段 3 Task 1）：与切菜同模，注册名不变。
        GRINDING_FACTORY_BLOCKS_REG.register(bus);
        GRINDING_FACTORY_TILES_REG.register(bus);
        GRINDING_FACTORY_CONTAINERS_REG.register(bus);
        // 烧烤工厂（阶段 3 Task 3）：与切菜/研磨同模，注册名不变。
        GRILL_FACTORY_BLOCKS_REG.register(bus);
        GRILL_FACTORY_TILES_REG.register(bus);
        GRILL_FACTORY_CONTAINERS_REG.register(bus);
        // 穿串工厂（阶段 3 Task 5）：与切菜/研磨/烧烤同模，注册名不变。
        SKEWERING_FACTORY_BLOCKS_REG.register(bus);
        SKEWERING_FACTORY_TILES_REG.register(bus);
        SKEWERING_FACTORY_CONTAINERS_REG.register(bus);
        // 烹饪工厂（阶段 3 Task 7）：与切菜/研磨/烧烤/穿串同模，注册名不变。
        COOKING_FACTORY_BLOCKS_REG.register(bus);
        COOKING_FACTORY_TILES_REG.register(bus);
        COOKING_FACTORY_CONTAINERS_REG.register(bus);
        // 种植切配工厂（阶段 3 Task 2）—— **这三行此前漏了**，是实机启动才暴露的缺陷。
        //
        // 症状（2026-09-30 实例 latest.log）：
        //   [FATAL] Mod 'mekck' encountered an error in a deferred task:
        //   java.lang.NullPointerException: Registry Object not present: mekck:planting_cutting_factory
        //       at UniversalCuttingMachine$ClientEvents.lambda$onClientSetup$0(UniversalCuttingMachine.java:2075)
        // 机制：静态块里 {@code PLANTING_CUTTING_FACTORY_*_REG.register(...)} 只是造出
        // {@code RegistryObject} 壳子（所以字段非 null、编译也不报错），
        // **真正把内容写进 Forge 注册表的是这里这个 register(bus)**。少了它，
        // 该族的方块 / tile / 容器一个都没注册，客户端 {@code MenuScreens.register(...CONTAINER.get()...)}
        // 一取就抛 {@code Registry Object not present}。
        //
        // 为什么长期没被发现：它被上一个缺陷（打包产物缺 refmap ⇒ MixinItemStack 应用失败 ⇒
        // 启动即崩）完全掩盖了——那时根本走不到客户端初始化。修好 refmap 之后它才露出来。
        // ⚠️ 以后新增工厂家族时，**三件套要成组出现**：静态块里 register(...) + 构造函数里 register(bus)。
        PLANTING_CUTTING_FACTORY_BLOCKS_REG.register(bus);
        PLANTING_CUTTING_FACTORY_TILES_REG.register(bus);
        PLANTING_CUTTING_FACTORY_CONTAINERS_REG.register(bus);
        // 电力烧烤架（阶段 3）：同上，三件套必须成组出现。
        // 少了这三行的症状与 planting 那次逐字相同：静态块里的 register(...) 只造出
        // RegistryObject 壳子（字段非 null、编译通过），真正写进 Forge 注册表的是这里；
        // 缺了就是「方块放下去变空气 + 客户端 MenuScreens.register(GRILL_CONTAINER.get(), ...)
        // 抛 Registry Object not present」。
        GRILL_BLOCKS_REG.register(bus);
        GRILL_TILES_REG.register(bus);
        GRILL_CONTAINERS_REG.register(bus);
        bus.addListener(this::addCreativeTabContents);
        bus.addListener(this::onCommonSetup);
        // 配置文件生成到 config/mekck/mekck-common.toml（与 planting 等配置文件同目录）
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MekckConfig.SPEC, "mekck/mekck-common.toml");
        bus.register(MekckConfig.class);

        // ⚠️ GuideME 指南注册必须在这里（mod 构造期）完成，**不能**放到 FMLClientSetupEvent。
        //   GuideME 的 GuideReloadListener 在**首次资源重载**时遍历 GuideRegistry.getStaticGuides()
        //   来加载页面；实测时间线：
        //     Reloading ResourceManager   19:48:07.802   ← GuideME 在此扫页面
        //     我们注册                     19:48:35.320   ← 晚了 27 秒 ⇒ 零页面
        //   后果是书能打开但提示 Page 'mekguide:index.md' could not be found（页面 map 为空）。
        //   AE2 就是这么做的（在 AppEngClient 构造器里 builder(...).build()），照抄。
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            cn.ism.mekck.util.GuideMECompat.registerGuidebook();
        }

        // 应用能源2（AE2）可选联动：为烹饪工厂/穿串工厂挂接 IInWorldGridNodeHost 能力
        // （AE2 未安装时 AE2Compat.isLoaded() 为 false，直接短路）
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, (AttachCapabilitiesEvent<BlockEntity> event) -> {
            if (cn.ism.mekck.util.AE2Compat.isLoaded()) {
                cn.ism.mekck.util.AE2Compat.attachCapabilities(event);
            }
        });

        // 服务器tick监控：测量每tick耗时供 LagMonitor 动态调速
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
            if (event.phase == TickEvent.Phase.START) {
                LagMonitor.onTickStart();
            } else {
                LagMonitor.onTickEnd();
            }
        });

        // Register recipe generation on ServerStartedEvent (all resources available)
        // and execute /reload to load the generated datapack
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            // ── 两处「启动写世界数据包」在这里合并，只做一次 /reload ──
            boolean changed = false;

            // ① 种植配方：配置开关默认开启；关闭后不再生成（已有配方保持不变）
            if (cn.ism.mekck.config.MekckConfig.getAutoGeneratePlantingRecipes()) {
                if (PlantingRecipeGenerator.generate(event.getServer())) {
                    changed = true;
                    PlantingRecipeGenerator.LOGGER.info("Planting recipes generated.");
                }
            }

            // ② 创造升级「49 种随机食物」：整局固定，每次服务器启动重写 49 个单元素 tag
            try {
                if (cn.ism.mekck.world.CreativeUpgradeFoodRotator.rotate(event.getServer())) {
                    changed = true;
                }
            } catch (Throwable t) {
                // 绝不能让本功能把服务器启动搞崩；失败时保留旧 tag
                cn.ism.mekck.world.CreativeUpgradeFoodRotator.LOGGER.error(
                    "[创造升级] 随机化过程异常，已跳过（保留旧 tag）。", t);
            }

            if (changed) {
                event.getServer().getCommands().performPrefixedCommand(
                    event.getServer().createCommandSourceStack(), "reload"
                );
                PlantingRecipeGenerator.LOGGER.info("Generated datapack content reloaded.");
            }
        });

        // 玩家登出：清掉其「昂贵请求节流」记录（第三轮补）。
        //
        // 不清也不影响正确性（key 是 UUID，重新登录自然重新计时），但那是个
        // ConcurrentHashMap，长期服务器 + 大量进出服会无界增长。顺手清掉。
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                cn.ism.mekck.network.PacketGuard.forgetCooldown(serverPlayer);
            }
        });

        // 创造升级：向首位进服的玩家提示本局 49 种食物已随机生成
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (creativeUpgradeHintSent) {
                return;
            }
            if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
                return;
            }
            creativeUpgradeHintSent = true;
            if (!cn.ism.mekck.config.MekckConfig.getCreativeUpgradeFoodHint()) {
                return;
            }
            if (cn.ism.mekck.world.CreativeUpgradeFoodRotator.currentPicks() == null) {
                return; // 本次没有执行随机化（per_world 沿用了旧结果）⇒ 不提示，免得玩家以为又变了
            }
            // 文案按模式区分：per_world（默认，仅首次生成时提示一次）/ per_restart（每次都提示）
            serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                cn.ism.mekck.world.CreativeUpgradeFoodRotator.chatHintKey()), false);
        });

        // Send debug info to the first player who joins the server
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (!firstPlayerJoined && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                firstPlayerJoined = true;
                // 配置开关：默认开启；关闭后仅写日志，不在聊天栏显示
                if (cn.ism.mekck.config.MekckConfig.getPlantingDebugToChat()) {
                    PlantingRecipeGenerator.sendDebugInfoToPlayer(serverPlayer);
                }
            }
        });
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // 自定义进度触发器：mekck 机器接入 ME 网络（网络厨师学徒）
        net.minecraft.advancements.CriteriaTriggers.register(cn.ism.mekck.advancement.NetworkConnectedTrigger.get());
        event.enqueueWork(ModMessages::register);
        // §F33：machinepreview 多方块形状登记必须在注册事件之后——构造期 DeferredHolder 尚未解析，
        //   直接 .get() 会抛 "Registry Object not present: mekck:planting_cutting_station"、被 setup() 内 catch
        //   吞成一条 warn 并从该处中断，导致后续种植工厂/生物反应堆尺寸全部漏登记。
        //   FMLCommonSetupEvent 时点所有方块已注册，enqueueWork 里调安全；未装 machinepreview 时 setup() 内部直接返回。
        event.enqueueWork(cn.ism.mekck.util.MachinePreviewCompat::setup);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(GUIDE_HANDBOOK_ITEM);
            event.accept(MACHINE_ITEM.get());
            for (RegistryObject<Item> factoryItem : FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            event.accept(GRINDING_MACHINE_ITEM.get());
            for (RegistryObject<Item> factoryItem : GRINDING_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            event.accept(COOKING_POT_ITEM.get());
            for (RegistryObject<Item> factoryItem : COOKING_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            event.accept(SKEWERING_MACHINE_ITEM.get());
            for (RegistryObject<Item> factoryItem : SKEWERING_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            event.accept(GRILL_ITEM.get());
            for (RegistryObject<Item> factoryItem : GRILL_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            for (RegistryObject<Item> factoryItem : PLANTING_CUTTING_FACTORY_ITEMS.values()) {
                event.accept(factoryItem.get());
            }
            event.accept(PLANTING_CUTTING_STATION_ITEM.get());
            event.accept(BIOREACTOR_ITEM.get());
            event.accept(ICE_MAKER_ITEM.get());
            event.accept(WINE_CELLAR_ITEM.get());
            event.accept(CENTRAL_KITCHEN_ITEM.get());
            event.accept(SANDWICH_ASSEMBLER_ITEM.get());
            if (MekckConfig.isIceFactoryEnabled()) {
                for (RegistryObject<Item> factoryItem : ICE_FACTORY_ITEMS.values()) {
                    event.accept(factoryItem.get());
                }
            }
            event.accept(CHOCOLATE_CANNON_ITEM.get());
            event.accept(NUT_ROASTER_ITEM.get());
            event.accept(SUSHI_MAKER_ITEM.get());
            event.accept(AVERAGE_SLICER_ITEM.get());
            event.accept(RICE_BALL_MAKER_ITEM.get());
            event.accept(CURD_MAKER_ITEM.get());
            event.accept(DEHYDRATOR_ITEM.get());
            event.accept(FERMENTER_ITEM.get());
            event.accept(STEAMER_ITEM.get());
            event.accept(WINERY_ITEM.get());
            event.accept(JUICER_ITEM.get());
            event.accept(BAKERY_OVEN_ITEM.get());
            event.accept(STOVE_ITEM.get());
            event.accept(COCKTAIL_SHAKER_ITEM.get());
            event.accept(BLENDER_ITEM.get());
            event.accept(TEA_BREWER_ITEM.get());
            event.accept(SMART_EXTRACTOR_ITEM.get());
            event.accept(BEVERAGE_BLENDER_ITEM.get());
            event.accept(PACKAGING_STATION_ITEM.get());
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
            event.accept(HAZELNUT_COCOA_PASTE_BUCKET.get());
            event.accept(ATOMIC_KNIFE.get());
        }
    }

    /**
     * 多方块机器放置轮廓预览（通用）：
     * 手持任意“绑定方块式”机器物品（本模组种植切配站/工厂、Mekanism 数字型采矿机/改装台、
     * mekmm 种植站/大型机器等，凡 BlockState 带 {@code AttributeHasBounding} 的）时，
     * 在将要放置的位置显示半透明体积轮廓；位置不可放时不显示。
     */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ClientWorldEvents {
        /** 会话级：首次手持机器出现预览时提示一次配置开关说明。 */
        private static boolean previewTipShown = false;
        private static final org.slf4j.Logger OUTLINE_LOGGER =
                org.slf4j.LoggerFactory.getLogger("MekCK.Outline");

        private ClientWorldEvents() {
        }

        /** 冰封视觉：有「冰冻」或「永冻」效果的实体渲染一个与实体同大的半透明冰壳（仅视觉效果）。 */
        @SubscribeEvent
        @SuppressWarnings("rawtypes")
        public static void onPostRenderLiving(RenderLivingEvent.Post event) {
            LivingEntity entity = event.getEntity();
            int ticks = -1;
            MobEffectInstance frozen = entity.getEffect(FROZEN_EFFECT.get());
            if (frozen != null) ticks = frozen.getDuration();
            MobEffectInstance eternal = entity.getEffect(ETERNAL_FREEZE_EFFECT.get());
            if (eternal != null) ticks = Math.max(ticks, eternal.getDuration());
            if (ticks >= 0) {
                cn.ism.mekck.client.FrozenIceRender.render(entity,
                        event.getPoseStack(), event.getMultiBufferSource(), event.getPackedLight(), ticks);
            }
        }

        /** 本模组的单方块机器：除多方块（种植切配站/种植切配工厂/生物反应堆）外，mekck 命名空间下
         * 的所有基础机器与工厂版本均属之（含巧克力大炮/坚果爆炒机等非工厂机器，及未来新增机器）。 */
        private static boolean isOurSingleBlockMachine(net.minecraft.world.level.block.Block block) {
            if (block instanceof cn.ism.mekck.block.PlantingCuttingStationBlock
                    || block instanceof cn.ism.mekck.block.PlantingCuttingFactoryBlock
                    || block instanceof cn.ism.mekck.block.BioreactorBlock) {
                return false; // 多方块（处理器前面已按多方块路径处理，此处兜底）
            }
            net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
            return id != null && id.getNamespace().equals(UniversalCuttingMachine.MOD_ID);
        }

        /** 机器判定缓存（每方块类型一次）。 */
        /**
         * 多方块机器“占地面积”轮廓：把最底层（minY）的每个方块当作完整方块，
         * 只画与地面接触的底边（跳过被相邻块挡住的内部边），不画垂直棱与顶面，
         * 视觉上就是机器占地的投影轮廓。
         */
        private static void renderGroundOutline(com.mojang.blaze3d.vertex.PoseStack poseStack,
                                                com.mojang.blaze3d.vertex.VertexConsumer lines,
                                                java.util.List<net.minecraft.core.BlockPos> positions,
                                                Vec3 view, net.minecraft.core.BlockPos target) {
            if (positions.isEmpty()) return;
            int minY = Integer.MAX_VALUE;
            for (net.minecraft.core.BlockPos p1 : positions) {
                minY = Math.min(minY, p1.getY());
            }
            java.util.Set<Long> ground = new java.util.HashSet<>();
            for (net.minecraft.core.BlockPos p1 : positions) {
                if (p1.getY() == minY) {
                    ground.add(((long) p1.getX() << 32) | (p1.getZ() & 0xFFFFFFFFL));
                }
            }
            if (ground.isEmpty()) return;
            // 与模型线框同一坐标系：translate(target - view)，画相对 target 的局部坐标，
            // 避免对 RenderHighlight poseStack 空间做假设导致白线错位。
            poseStack.pushPose();
            poseStack.translate(target.getX() - view.x, target.getY() - view.y, target.getZ() - view.z);
            org.joml.Matrix4f m = poseStack.last().pose();
            float y = minY - target.getY();
            for (long k : ground) {
                int x = (int) (k >> 32);
                int z = (int) (k & 0xFFFFFFFFL);
                int lx = x - target.getX();
                int lz = z - target.getZ();
                groundEdge(lines, m, ground, lx, y, lz, lx + 1, y, lz);          // 北
                groundEdge(lines, m, ground, lx + 1, y, lz, lx + 1, y, lz + 1);  // 东
                groundEdge(lines, m, ground, lx + 1, y, lz + 1, lx, y, lz + 1);  // 南
                groundEdge(lines, m, ground, lx, y, lz + 1, lx, y, lz);          // 西
            }
            poseStack.popPose();
        }

        /** 画一条底边；若相邻方向有同层块（内部边）则跳过。 */
        private static void groundEdge(com.mojang.blaze3d.vertex.VertexConsumer lines, org.joml.Matrix4f m,
                                       java.util.Set<Long> ground, int x0, float y0, int z0, int x1, float y1, int z1) {
            boolean hidden;
            if (z0 == z1) {
                int nx = (x0 + x1) / 2;
                hidden = ground.contains(((long) nx << 32) | ((long) (z0 - 1) & 0xFFFFFFFFL))
                        || ground.contains(((long) nx << 32) | ((long) (z0 + 1) & 0xFFFFFFFFL));
            } else {
                int nz = (z0 + z1) / 2;
                hidden = ground.contains(((long) (x0 - 1) << 32) | (nz & 0xFFFFFFFFL))
                        || ground.contains(((long) (x0 + 1) << 32) | (nz & 0xFFFFFFFFL));
            }
            if (hidden) return;
            lines.vertex(m, x0, y0, z0).color(1.0F, 1.0F, 1.0F, 0.9F).normal(0.0F, 1.0F, 0.0F).endVertex();
            lines.vertex(m, x1, y1, z1).color(1.0F, 1.0F, 1.0F, 0.9F).normal(0.0F, 1.0F, 0.0F).endVertex();
        }

        private static final java.util.Map<net.minecraft.world.level.block.Block, Boolean> OTHER_MOD_MACHINE_CACHE =
                new java.util.concurrent.ConcurrentHashMap<>();

        /** 其他模组的单方块机器判定：模组方块 + 有方块实体 + 有朝向 + (有运行状态属性 或 可存能量)。 */
        private static boolean isOtherModMachine(net.minecraft.world.level.block.Block block,
                                                 net.minecraft.world.level.block.state.BlockState state) {
            net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block);
            if (id == null || id.getNamespace().equals("minecraft")) {
                return false;
            }
            Boolean cached = OTHER_MOD_MACHINE_CACHE.get(block);
            if (cached != null) {
                return cached;
            }
            boolean result = false;
            try {
                boolean hasFacing = state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                        || state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
                boolean hasRunningState = hasRunningStateProperty(state);
                boolean hasEnergy = probesEnergy(block, state);
                // 通用机器：有朝向 + （运行状态 或 可存能量）
                result = hasFacing && (hasRunningState || hasEnergy);
                if (!result && isMekanismFamily(id)) {
                    // Mekanism 系列容器类方块（能量立方 / 流体储罐 / 化学品储罐 / 箱柜等）：
                    // 部分无朝向属性（如基础流体储罐只有 active），部分无 FE 能量能力（如化学品储罐），
                    // 因此对 Mekanism 系放宽为：有方块实体 + （有朝向 或 运行状态 或 任意存储能力）。
                    result = hasFacing || hasRunningState || probesStorage(block, state);
                }
            } catch (Throwable ignored) {
                result = false;
            }
            OTHER_MOD_MACHINE_CACHE.put(block, result);
            return result;
        }

        /** 运行状态类属性：active / lit / powered / working / on。 */
        private static boolean hasRunningStateProperty(net.minecraft.world.level.block.state.BlockState state) {
            for (net.minecraft.world.level.block.state.properties.Property<?> prop : state.getProperties()) {
                String n = prop.getName();
                if ("active".equals(n) || "lit".equals(n) || "powered".equals(n)
                        || "working".equals(n) || "on".equals(n)) {
                    return true;
                }
            }
            return false;
        }

        /** 是否为 Mekanism 系列命名空间（mekanism / mekanism_extras / mekmm 等）。 */
        private static boolean isMekanismFamily(net.minecraft.resources.ResourceLocation id) {
            String ns = id.getNamespace();
            return ns.equals("mekanism") || ns.startsWith("mekanism") || ns.equals("mekmm");
        }

        /** 试探方块实体是否暴露任意存储能力（能量 / 流体 / 物品），用于 Mekanism 容器类方块判定。 */
        private static boolean probesStorage(net.minecraft.world.level.block.Block block,
                                             net.minecraft.world.level.block.state.BlockState state) {
            if (!(block instanceof net.minecraft.world.level.block.EntityBlock eb)) {
                return false;
            }
            net.minecraft.world.level.block.entity.BlockEntity be = null;
            try {
                be = eb.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
                if (be == null) {
                    return false;
                }
                return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).resolve().isPresent()
                        || be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER).resolve().isPresent()
                        || be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER).resolve().isPresent();
            } catch (Throwable ignored) {
                return false;
            } finally {
                if (be != null) {
                    try {
                        be.setRemoved();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        /** 试探方块实体是否暴露 FE 能量能力（创建一次并立即移除，全程 try/catch；按方块类型缓存）。 */
        private static boolean probesEnergy(net.minecraft.world.level.block.Block block,
                                            net.minecraft.world.level.block.state.BlockState state) {
            try {
                if (block instanceof net.minecraft.world.level.block.EntityBlock eb) {
                    net.minecraft.world.level.block.entity.BlockEntity be =
                            eb.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
                    if (be != null) {
                        try {
                            boolean has = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY)
                                    .resolve().isPresent();
                            return has;
                        } finally {
                            be.setRemoved();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }

        @SubscribeEvent
        public static void onRenderHighlight(net.minecraftforge.client.event.RenderHighlightEvent.Block event) {
            // 装了独立模组「机器放置预览」（machinepreview）时，预览由它接管，
            // 本模组自动关闭以免两层叠加（形状已在构造期登记给它）。
            if (cn.ism.mekck.util.MachinePreviewCompat.isLoaded()) {
                return;
            }
            // 与原版方块选择框同事件渲染（Mekanism 放置线框同款方案），必然可见
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || mc.gameMode == null || mc.screen != null) {
                return;
            }
            // 主手/副手任一持有方块物品即触发预览（记录持有手与物品，用于构造与真实放置一致的上下文）
            BlockItem blockItem = null;
            net.minecraft.world.InteractionHand heldHand = net.minecraft.world.InteractionHand.MAIN_HAND;
            net.minecraft.world.item.ItemStack heldStack = net.minecraft.world.item.ItemStack.EMPTY;
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                net.minecraft.world.item.ItemStack held = mc.player.getItemInHand(hand);
                if (!held.isEmpty() && held.getItem() instanceof BlockItem bi) {
                    blockItem = bi;
                    heldHand = hand;
                    heldStack = held;
                    break;
                }
            }
            if (blockItem == null) {
                return;
            }
            net.minecraft.world.level.block.Block block = blockItem.getBlock();
            // 必须命中方块表面（排除 MISS/实体命中），否则 getBlockPos 无意义
            net.minecraft.world.phys.BlockHitResult bhr = event.getTarget();
            if (bhr.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                return;
            }
            net.minecraft.core.BlockPos clicked = bhr.getBlockPos();
            net.minecraft.world.level.block.state.BlockState clickedState = mc.level.getBlockState(clicked);
            net.minecraft.core.BlockPos target = clickedState.canBeReplaced() ? clicked : clicked.relative(bhr.getDirection());
            if (!mc.level.getBlockState(target).canBeReplaced()) {
                return;
            }

            // 朝向：直接复用方块自身的真实放置规则（Block.getStateForPlacement），
            // 构造与真实放置一致的 BlockPlaceContext（同世界/玩家/手/物品/点击面/点击位置）。
            // 这样预览状态 = 玩家在当前位置实际放置后的状态，消除手写朝向算法（含此前
            // mekmm 6 向机器的 getOpposite 补偿）与真实放置不一致导致的水平 180° 反向；
            // 对特殊放置规则（如 mekmm 自研放置逻辑）也保留其真实行为。
            net.minecraft.world.level.block.state.BlockState placeState = block.defaultBlockState();
            try {
                net.minecraft.world.item.context.BlockPlaceContext ctx = new net.minecraft.world.item.context.BlockPlaceContext(
                        mc.level, mc.player, heldHand, heldStack, bhr);
                net.minecraft.world.level.block.state.BlockState placement = block.getStateForPlacement(ctx);
                if (placement != null) {
                    placeState = placement;
                }
            } catch (Throwable t) {
                // 回退：默认状态（通常朝北），不崩溃；不修改其他模组/原版放置行为
                placeState = block.defaultBlockState();
            }

            // 绑定方块位置：本模组机器用内置形状；Mekanism/mekmm 机器读 AttributeHasBounding；
            // 单方块机器（无绑定位置）也显示预览，与多方块分别由配置控制
            List<net.minecraft.core.BlockPos> boundingPositions;
            boolean multiblock = false;
            if (block instanceof PlantingCuttingStationBlock || block instanceof PlantingCuttingFactoryBlock) {
                boundingPositions = MekCkMultiblock.getBoundingPositions(target, placeState, MekCkMultiblock.SHAPE_2_TALL);
                multiblock = true;
            } else if (block instanceof BioreactorBlock) {
                boundingPositions = MekCkMultiblock.getBoundingPositions(target, placeState, MekCkMultiblock.SHAPE_3X3X3);
                multiblock = true;
            } else {
                AttributeHasBounding bounding = Attribute.get(placeState, AttributeHasBounding.class);
                if (bounding != null) {
                    boundingPositions = bounding.getPositions(target, placeState).toList();
                    if (!boundingPositions.isEmpty()) {
                        multiblock = true;
                    }
                } else {
                    boundingPositions = java.util.List.of();
                }
            }

            if (multiblock) {
                // 多方块预览开关
                if (!cn.ism.mekck.config.MekckConfig.isMultiblockPreviewEnabled()) {
                    return;
                }
                // 可放置校验：所有绑定方块位置必须可替换
                for (net.minecraft.core.BlockPos p : boundingPositions) {
                    if (p.getY() < mc.level.getMinBuildHeight() || p.getY() >= mc.level.getMaxBuildHeight()) {
                        return;
                    }
                    net.minecraft.world.level.block.state.BlockState s = mc.level.getBlockState(p);
                    if (!s.isAir() && !s.canBeReplaced()) {
                        return;
                    }
                }
            } else if (isOurSingleBlockMachine(block)) {
                // 单方块预览：仅本模组含工厂版本的系列，配置开关
                if (!cn.ism.mekck.config.MekckConfig.isSingleBlockPreviewEnabled()) {
                    return;
                }
            } else if (cn.ism.mekck.config.MekckConfig.isOtherModsMachinePreviewEnabled()
                    && isOtherModMachine(block, placeState)) {
                // 其他模组的机器（机器特征判定），配置开关
                // 通过
            } else {
                return;
            }

            // 轮廓体积 = 目标格 + 全部绑定格（画整体包围盒）
            java.util.List<net.minecraft.core.BlockPos> outlinePositions = new java.util.ArrayList<>(boundingPositions);
            if (!outlinePositions.contains(target)) {
                outlinePositions.add(target);
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (net.minecraft.core.BlockPos p : outlinePositions) {
                minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
            }
            // 真实模型线框描边（回退：整体包围盒 12 棱）——顶点必须 endVertex 才会写入缓冲
            Vec3 view = event.getCamera().getPosition();
            com.mojang.blaze3d.vertex.VertexConsumer lines = event.getMultiBufferSource()
                    .getBuffer(net.minecraft.client.renderer.RenderType.lines());
            // 多方块机器：额外画“占地面积”轮廓（最底层方块底面外沿，白色亮线，不画与地面不接触的棱）
            if (multiblock) {
                renderGroundOutline(event.getPoseStack(), lines, boundingPositions, view, target);
            }
            PoseStack poseStack = event.getPoseStack();
            poseStack.pushPose();
            poseStack.translate(target.getX() - view.x, target.getY() - view.y, target.getZ() - view.z);
            // 半透明机器模型预览（配置开关，可与线框同时启用）。
            // 大型风力发电机：塔身/叶片由 mekmm 的 BER(BlockEntityRenderer) 渲染，BakedModel 只是占位 →
            // 走原生 BER 预览（不透明但部件正确，半透明对 BER 引擎层不可行）。其余机器走纯 BakedModel 半透明。
            if (cn.ism.mekck.config.MekckConfig.isTranslucentModelPreviewEnabled()) {
                if (MekCkOutlineRenderer.isBerPreviewBlock(placeState)) {
                    // 风力发电机：BER 替代（BakedModel 仅占位）
                    MekCkOutlineRenderer.renderBerPreview(poseStack, event.getMultiBufferSource(),
                            mc.level, placeState, target,
                            net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                } else {
                    MekCkOutlineRenderer.renderTranslucentModel(poseStack, event.getMultiBufferSource(),
                            mc.level, placeState,
                            net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                    // 生物反应堆（多层结构）/ 能量立方（内部旋转体）：在模型之外叠加 BER 渲染
                    if (MekCkOutlineRenderer.needsBerOverlay(placeState)) {
                        MekCkOutlineRenderer.renderBerPreview(poseStack, event.getMultiBufferSource(),
                                mc.level, placeState, target,
                                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
                    }
                }
            }
            // 模型逐边线框（开关 wireframe_preview，默认关）
            boolean drewModel = false;
            if (cn.ism.mekck.config.MekckConfig.isWireframePreviewEnabled()) {
                com.mojang.blaze3d.vertex.VertexConsumer wireLines = event.getMultiBufferSource()
                        .getBuffer(net.minecraft.client.renderer.RenderType.lines());
                drewModel = MekCkOutlineRenderer.renderModelWireframe(poseStack, wireLines, mc.level, placeState);
            }
            // 包围盒线框（开关 box_preview，默认开）：机器模型顶点的彩虹包围盒，粗细 box_line_width
            if (cn.ism.mekck.config.MekckConfig.isBoxPreviewEnabled()) {
                float[] mb = MekCkOutlineRenderer.getModelBounds(mc.level, placeState);
                if (mb != null) {
                    com.mojang.blaze3d.vertex.VertexConsumer boxLines = event.getMultiBufferSource()
                            .getBuffer(net.minecraft.client.renderer.RenderType.debugQuads());
                    MekCkOutlineRenderer.renderBoxThick(poseStack, boxLines,
                            Math.min(mb[0], 0.0F), 0.0F, Math.min(mb[2], 0.0F),
                            Math.max(mb[3], 1.0F), Math.max(mb[4], 1.0F), Math.max(mb[5], 1.0F));
                }
            }
            poseStack.popPose();
            // 多方块机器（生物反应堆 2×2×3、mekmm 大型机等）模型通常只覆盖控制器/base 层，
            // 必须叠加整体结构包围盒，否则上层结构无任何轮廓；单方块机器仅当模型渲染失败
            // 且未启用包围盒线框时才画 1 格结构盒（避免与 box_preview 的模型盒重叠成“小盒”）。
            if ((!drewModel && !cn.ism.mekck.config.MekckConfig.isBoxPreviewEnabled()) || multiblock) {
                poseStack.pushPose();
                poseStack.translate(minX - view.x, minY - view.y, minZ - view.z);
                com.mojang.blaze3d.vertex.VertexConsumer boxLines = event.getMultiBufferSource()
                        .getBuffer(net.minecraft.client.renderer.RenderType.lines());
                MekCkOutlineRenderer.renderBox(poseStack, boxLines,
                        0, 0, 0,
                        maxX - minX + 1.0, maxY - minY + 1.0, maxZ - minZ + 1.0);
                poseStack.popPose();
            }
            // 替换原版方块选择框（本位置显示我们的模型/包围盒线框）
            event.setCanceled(true);
            // 首次预览提示：2 种预览功能可在配置文件 placement_preview 区分别开关
            if (!previewTipShown) {
                previewTipShown = true;
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§e[MekCK] 放置预览：半透明模型与模型线框可在配置文件 placement_preview 区分别开关"
                                            + "（translucent_model_preview / wireframe_preview，另见 line_width_multiplier）"),
                            false);
                }
            }
            if (OUTLINE_LOGGER.isDebugEnabled()) {
                OUTLINE_LOGGER.debug("[mekck-outline] 渲染轮廓 @{} block={} box={},{},{} ~ {},{},{}",
                        target, ForgeRegistries.BLOCKS.getKey(block),
                        minX, minY, minZ, maxX, maxY, maxZ);
            }
        }

        // [mekck-bb] 诊断：玩家右键点击绑定方块时，记录客户端是否已收到主块坐标（receivedCoords）。
        // 若日志显示 hit 的是 mekanism:bounding_block 但 mainPos=null，说明客户端同步仍未成功。
        private static final org.apache.logging.log4j.Logger BB_DIAG =
                org.apache.logging.log4j.LogManager.getLogger("mekck.BoundingDiag");

        @SubscribeEvent
        public static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
            if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof mekanism.common.block.BlockBounding)) {
                return;
            }
            net.minecraft.core.BlockPos hit = event.getPos();
            mekanism.common.tile.TileEntityBoundingBlock t =
                    mekanism.common.util.WorldUtils.getTileEntity(mekanism.common.tile.TileEntityBoundingBlock.class, event.getLevel(), hit);
            net.minecraft.core.BlockPos main = mekanism.common.block.BlockBounding.getMainBlockPos(event.getLevel(), hit);
            BB_DIAG.info("[mekck-bb] 右键绑定块 {} → tile存在={}, receivedCoords={}, mainPos={}",
                    hit, t != null, t != null && t.hasReceivedCoords(), main);
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                // GuideME 指南书注册已移到 mod 构造器（必须早于首次资源重载，见构造器注释）
                MenuScreens.register(MACHINE_MENU.get(), UniversalCuttingMachineScreen::new);
                MenuScreens.register(FACTORY_MENU.get(), CuttingMachineFactoryScreen::new);
                MenuScreens.register(GRINDING_MACHINE_MENU.get(), ElectricGrindingMachineScreen::new);
                MenuScreens.register(GRINDING_FACTORY_MENU.get(), GrindingFactoryScreen::new);
                MenuScreens.register(COOKING_POT_MENU.get(), SmartCookingPotScreen::new);
                MenuScreens.register(COOKING_FACTORY_CONTAINER.get(), CookingFactoryScreen::new);
                MenuScreens.register(SKEWERING_MACHINE_MENU.get(), SkeweringMachineScreen::new);
                MenuScreens.register(SKEWERING_FACTORY_CONTAINER.get(), SkeweringFactoryScreen::new);
                MenuScreens.register(GRILL_CONTAINER.get(), GrillScreen::new);
                MenuScreens.register(GRILL_FACTORY_CONTAINER.get(), GrillFactoryScreen::new);
                MenuScreens.register(PLANTING_CUTTING_CONTAINER.get(), PlantingCuttingFactoryScreen::new);
                MenuScreens.register(PLANTING_CUTTING_STATION_MENU.get(), PlantingCuttingStationScreen::new);
                MenuScreens.register(BIOREACTOR_MENU.get(), BioreactorScreen::new);
                MenuScreens.register(ICE_MAKER_MENU.get(), IceMakerScreen::new);
                MenuScreens.register(WINE_CELLAR_MENU.get(), WineCellarScreen::new);
                MenuScreens.register(CENTRAL_KITCHEN_MENU.get(), cn.ism.mekck.client.CentralKitchenScreen::new);
                MenuScreens.register(SANDWICH_ASSEMBLER_MENU.get(), cn.ism.mekck.client.SandwichAssemblerScreen::new);
                if (MekckConfig.isIceFactoryEnabled()) {
                    MenuScreens.register(ICE_FACTORY_MENU.get(), IceFactoryScreen::new);
                }
                MenuScreens.register(CHOCOLATE_CANNON_MENU.get(), ChocolateCannonScreen::new);
                MenuScreens.register(NUT_ROASTER_MENU.get(), NutRoasterScreen::new);
                MenuScreens.register(SIMPLE_MACHINE_MENU.get(), cn.ism.mekck.client.SimpleMachineScreen::new);
                net.minecraft.client.renderer.entity.EntityRenderers.register(ICE_CUBE_ENTITY.get(),
                        cn.ism.mekck.client.IceCubeRenderer::new);
                net.minecraft.client.renderer.entity.EntityRenderers.register(FERRERO_ENTITY.get(),
                        FerreroRenderer::new);
                net.minecraft.client.renderer.entity.EntityRenderers.register(ROASTED_HAZELNUT_ENTITY.get(),
                        net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
                // 生物反应堆多方块模型由 BER 叠加渲染：几何高 48px 超出 vanilla 模型元素
                // 坐标限制，改用外部 OBJ 网格（models/mesh/bioreactor.obj）。
                // blockstate 仍指向 bioreactor_layer0，供物品栏与掉落物外观使用。
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(BIOREACTOR_BLOCK_ENTITY.get(),
                        cn.ism.mekck.client.BioreactorRenderer::new);
                // 工厂进度条的 JEI 分类表必须在这里提前建好 —— 不能等开屏时懒加载。
                // 理由见 MekCkFactoryJei 的类注释：Mek 10.4.16 的 MekanismJEIRecipeType
                // 构造器在 findType() 把 allKnownTypes 置空之后必 NPE，而 findType()
                // 正是 JEI 的插件回调（催化剂注册）里调的。守卫不能省：本类引用 mezz.jei.*。
                if (net.minecraftforge.fml.ModList.get().isLoaded("jei")) {
                    cn.ism.mekck.client.MekCkFactoryJei.init();
                }
            });
        }

        /** 原子刀模型层（物品渲染用）。 */
        @SubscribeEvent
        public static void onRegisterLayerDefinitions(net.minecraftforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions event) {
            event.registerLayerDefinition(cn.ism.mekck.client.model.ModelAtomicKnife.KNIFE_LAYER,
                    cn.ism.mekck.client.model.ModelAtomicKnife::createLayerDefinition);
        }

        /** 原子刀渲染器资源重载监听（纹理/模型重载时重建）。 */
        @SubscribeEvent
        public static void onRegisterClientReloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
            mekanism.client.ClientRegistrationUtil.registerClientReloadListeners(event,
                    cn.ism.mekck.client.render.item.gear.RenderAtomicKnife.RENDERER);
        }
    }

    /**
     * FORGE 总线（服务端触发）订阅：网络厨师学徒进度的放置者记录与离线补授予。
     * 服务端权威；客户端加载本类无副作用（事件仅在服务端触发）。
     */
    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    /**
     * FORGE 总线（服务端触发）订阅：网络厨师学徒进度的公共事件。
     * 所有处理都在不依赖 AE2 类的 {@code cn.ism.mekck.advancement.NetworkChefProgress}（可选联动安全：
     * 未安装 AE2 时本类与主模组仍可正常加载；联网触发由 MekckAe2 经 AE2Compat 门面接入）。
     */
    public static final class NetworkAdvancementEvents {
        private NetworkAdvancementEvents() {
        }

        @SubscribeEvent
        public static void onBlockPlaced(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
            if (!(event.getLevel() instanceof net.minecraft.world.level.Level lv) || lv.isClientSide) return;
            if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer placer)) return;
            net.minecraft.world.level.block.entity.BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            if (be == null) return; // 防御：正常时序 BE 已创建（setBlock → attachCapabilities → setPlacedBy → 事件）
            if (!cn.ism.mekck.advancement.NetworkChefProgress.isAe2Machine(be)) return;
            cn.ism.mekck.advancement.NetworkChefProgress.recordPlacer(be, placer.getUUID());
        }

        @SubscribeEvent
        public static void onPlayerLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
                cn.ism.mekck.advancement.NetworkChefProgress.grantPendingOnLogin(sp);
            }
        }

        /** 服务端数据包同步完成（玩家登录 / 资源重载）——玩家数据就绪时补授予，覆盖“在线但资源后加载”。 */
        @SubscribeEvent
        public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
            if (event.getPlayer() != null) {
                cn.ism.mekck.advancement.NetworkChefProgress.onDatapackSync(event.getPlayer());
            }
        }

        /** 服务端 tick：处理有界重试队列（仅在队列非空时执行）。 */
        @SubscribeEvent
        public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
            if (event.phase == net.minecraftforge.event.TickEvent.Phase.END && event.getServer() != null) {
                cn.ism.mekck.advancement.NetworkChefProgress.onServerTick(event.getServer());
            }
        }
    }
}
