package cn.ism.mekck.client;

import cn.ism.mekck.client.BioreactorScreen;
import cn.ism.mekck.client.ChocolateCannonScreen;
import cn.ism.mekck.client.CookingFactoryScreen;
import cn.ism.mekck.client.CuttingMachineFactoryScreen;
import cn.ism.mekck.client.ElectricGrindingMachineScreen;
import cn.ism.mekck.client.FerreroRenderer;
import cn.ism.mekck.client.GrillFactoryScreen;
import cn.ism.mekck.client.GrillScreen;
import cn.ism.mekck.client.GrindingFactoryScreen;
import cn.ism.mekck.client.IceFactoryScreen;
import cn.ism.mekck.client.IceMakerScreen;
import cn.ism.mekck.client.NutRoasterScreen;
import cn.ism.mekck.client.PlantingCuttingFactoryScreen;
import cn.ism.mekck.client.PlantingCuttingStationScreen;
import cn.ism.mekck.client.SkeweringFactoryScreen;
import cn.ism.mekck.client.SkeweringMachineScreen;
import cn.ism.mekck.client.SmartCookingPotScreen;
import cn.ism.mekck.client.UniversalCuttingMachineScreen;
import cn.ism.mekck.client.WineCellarScreen;
import cn.ism.mekck.config.MekckConfig;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import static cn.ism.mekck.UniversalCuttingMachine.MOD_ID;
import static cn.ism.mekck.registry.MekCkFactories.FACTORY_MENU;
import static cn.ism.mekck.registry.MekCkFactories.GRINDING_FACTORY_MENU;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.BIOREACTOR_BLOCK_ENTITY;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.BIOREACTOR_MENU;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.CENTRAL_KITCHEN_MENU;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.CHOCOLATE_CANNON_MENU;
import static cn.ism.mekck.registry.MekCkFactories.COOKING_FACTORY_CONTAINER;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.COOKING_POT_MENU;
import static cn.ism.mekck.registry.MekCkEntities.FERRERO_ENTITY;
import static cn.ism.mekck.registry.MekCkFactories.GRILL_CONTAINER;
import static cn.ism.mekck.registry.MekCkFactories.GRILL_FACTORY_CONTAINER;
import static cn.ism.mekck.registry.MekCkFactories.GRINDING_MACHINE_CONTAINER;
import static cn.ism.mekck.registry.MekCkEntities.ICE_CUBE_ENTITY;
import static cn.ism.mekck.registry.MekCkFactories.ICE_FACTORY_MENU;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.ICE_MAKER_MENU;
import static cn.ism.mekck.registry.MekCkFactories.MACHINE_CONTAINER;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.NUT_ROASTER_MENU;
import static cn.ism.mekck.registry.MekCkFactories.PLANTING_CUTTING_CONTAINER;
import static cn.ism.mekck.registry.MekCkFactories.PLANTING_CUTTING_STATION_MENU;
import static cn.ism.mekck.registry.MekCkEntities.ROASTED_HAZELNUT_ENTITY;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.SANDWICH_ASSEMBLER_MENU;
import static cn.ism.mekck.registry.MekCkLegacyMachines.SIMPLE_MACHINE_MENU;
import static cn.ism.mekck.registry.MekCkFactories.SKEWERING_FACTORY_CONTAINER;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.SKEWERING_MACHINE_MENU;
import static cn.ism.mekck.registry.MekCkStandaloneMachines.WINE_CELLAR_CONTAINER;

/**
 * 客户端初始化事件：屏幕注册、模型层定义、客户端资源重载监听。
 *
 * <p>本类原为 {@code UniversalCuttingMachine} 的内嵌事件订阅类；注册中枢拆分时
 * 升格为顶层类（去掉 {@code static} 修饰，方法体逐字未改）。</p>
 */
@Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // GuideME 指南书注册已移到 mod 构造器（必须早于首次资源重载，见构造器注释）
            // 屏幕绑定用 Mek 容器对象（它与 MENUS 写同一个 minecraft:menu 注册表，
            // 因此绝不能再单独用 MENUS 注册一次同名项 —— 见 MekCkFactories 里那段警告）。
            MenuScreens.register(MACHINE_CONTAINER.get(), UniversalCuttingMachineScreen::new);
            MenuScreens.register(FACTORY_MENU.get(), CuttingMachineFactoryScreen::new);
            MenuScreens.register(GRINDING_MACHINE_CONTAINER.get(), ElectricGrindingMachineScreen::new);
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
            MenuScreens.register(WINE_CELLAR_CONTAINER.get(), WineCellarScreen::new);
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
        event.registerLayerDefinition(cn.ism.mekck.client.atomic_knife.ModelAtomicKnife.KNIFE_LAYER,
                cn.ism.mekck.client.atomic_knife.ModelAtomicKnife::createLayerDefinition);
    }

    /** 原子刀渲染器资源重载监听（纹理/模型重载时重建）。 */
    @SubscribeEvent
    public static void onRegisterClientReloadListeners(net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        mekanism.client.ClientRegistrationUtil.registerClientReloadListeners(event,
                cn.ism.mekck.client.atomic_knife.RenderAtomicKnife.RENDERER);
    }
}

/**
 * FORGE 总线（服务端触发）订阅：网络厨师学徒进度的放置者记录与离线补授予。
 * 服务端权威；客户端加载本类无副作用（事件仅在服务端触发）。
 */
