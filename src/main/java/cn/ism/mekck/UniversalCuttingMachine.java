package cn.ism.mekck;

import cn.ism.mekck.registry.MekCkRegistries;
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
import cn.ism.mekck.block.IceMakerBlock;
import cn.ism.mekck.block.IceFactoryBlock;
import cn.ism.mekck.block.WineCellarBlock;
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
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.menu.ChocolateCannonMenu;
import cn.ism.mekck.client.ChocolateCannonScreen;
import cn.ism.mekck.client.FerreroRenderer;
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
import cn.ism.mekck.registry.MekCkFactories;
import cn.ism.mekck.registry.MekCkStandaloneMachines;
import cn.ism.mekck.registry.MekCkLegacyMachines;
import cn.ism.mekck.registry.MekCkFluids;
import cn.ism.mekck.registry.MekCkItems;
import static cn.ism.mekck.registry.MekCkFactories.GRILL_CONTAINER;

@Mod(UniversalCuttingMachine.MOD_ID)
public final class UniversalCuttingMachine {
    public static final String MOD_ID = "mekck";

    private static boolean firstPlayerJoined = false;
    /** 创造升级的 49 种食物提示是否已发过（与种植调试信息各自独立）。 */
    private static boolean creativeUpgradeHintSent = false;

    @SuppressWarnings("removal") // ModLoadingContext.get()：见构造器内注释，1.20.1 上无替代入口
    public UniversalCuttingMachine(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        // 十个通用延迟注册器 + 触碰所有注册类（详见 MekCkRegistries#registerAll）
        MekCkRegistries.registerAll(bus);
        // 切菜工厂（阶段 2 Task 4）：注册名仍是 mekck:<tier>_cutting_factory，
        // 但注册器换成 Mek 的——方块因此带上 AttributeGui / AttributeEnergy /
        // AttributeStateFacing / AttributeUpgradeSupport，tile 也就顺理成章地
        // 变成 TileEntityMekanism 家族的一员。
        MekCkFactories.CUTTING_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.CUTTING_FACTORY_TILES_REG.register(bus);
        MekCkFactories.CUTTING_FACTORY_CONTAINERS_REG.register(bus);
        // 研磨工厂（阶段 3 Task 1）：与切菜同模，注册名不变。
        MekCkFactories.GRINDING_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.GRINDING_FACTORY_TILES_REG.register(bus);
        MekCkFactories.GRINDING_FACTORY_CONTAINERS_REG.register(bus);
        // 烧烤工厂（阶段 3 Task 3）：与切菜/研磨同模，注册名不变。
        MekCkFactories.GRILL_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.GRILL_FACTORY_TILES_REG.register(bus);
        MekCkFactories.GRILL_FACTORY_CONTAINERS_REG.register(bus);
        // 穿串工厂（阶段 3 Task 5）：与切菜/研磨/烧烤同模，注册名不变。
        MekCkFactories.SKEWERING_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.SKEWERING_FACTORY_TILES_REG.register(bus);
        MekCkFactories.SKEWERING_FACTORY_CONTAINERS_REG.register(bus);
        // 烹饪工厂（阶段 3 Task 7）：与切菜/研磨/烧烤/穿串同模，注册名不变。
        MekCkFactories.COOKING_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.COOKING_FACTORY_TILES_REG.register(bus);
        MekCkFactories.COOKING_FACTORY_CONTAINERS_REG.register(bus);
        // 种植切配工厂（阶段 3 Task 2）—— **这三行此前漏了**，是实机启动才暴露的缺陷。
        //
        // 症状（2026-09-30 实例 latest.log）：
        //   [FATAL] Mod 'mekck' encountered an error in a deferred task:
        //   java.lang.NullPointerException: Registry Object not present: mekck:planting_cutting_factory
        //       at ClientEvents.lambda$onClientSetup$0(ClientEvents.java:77)
        // 机制：静态块里 {@code PLANTING_CUTTING_FACTORY_*_REG.register(...)} 只是造出
        // {@code RegistryObject} 壳子（所以字段非 null、编译也不报错），
        // **真正把内容写进 Forge 注册表的是这里这个 register(bus)**。少了它，
        // 该族的方块 / tile / 容器一个都没注册，客户端 {@code MenuScreens.register(...CONTAINER.get()...)}
        // 一取就抛 {@code Registry Object not present}。
        //
        // 为什么长期没被发现：它被上一个缺陷（打包产物缺 refmap ⇒ MixinItemStack 应用失败 ⇒
        // 启动即崩）完全掩盖了——那时根本走不到客户端初始化。修好 refmap 之后它才露出来。
        // ⚠️ 以后新增工厂家族时，**三件套要成组出现**：静态块里 register(...) + 构造函数里 register(bus)。
        MekCkFactories.PLANTING_CUTTING_FACTORY_BLOCKS_REG.register(bus);
        MekCkFactories.PLANTING_CUTTING_FACTORY_TILES_REG.register(bus);
        MekCkFactories.PLANTING_CUTTING_FACTORY_CONTAINERS_REG.register(bus);
        // 电力烧烤架（阶段 3）：同上，三件套必须成组出现。
        // 少了这三行的症状与 planting 那次逐字相同：静态块里的 register(...) 只造出
        // RegistryObject 壳子（字段非 null、编译通过），真正写进 Forge 注册表的是这里；
        // 缺了就是「方块放下去变空气 + 客户端 MenuScreens.register(GRILL_CONTAINER.get(), ...)
        // 抛 Registry Object not present」。
        MekCkFactories.MACHINE_BLOCKS_REG.register(bus);
        MekCkFactories.MACHINE_TILES_REG.register(bus);
        MekCkFactories.MACHINE_CONTAINERS_REG.register(bus);
        MekCkFactories.GRILL_BLOCKS_REG.register(bus);
        MekCkFactories.GRILL_TILES_REG.register(bus);
        MekCkFactories.GRILL_CONTAINERS_REG.register(bus);
        // 陈化窖（2026-10-03 迁到 Mek 体系）：同上，三件套必须成组出现。
        MekCkStandaloneMachines.WINE_CELLAR_BLOCKS_REG.register(bus);
        MekCkStandaloneMachines.WINE_CELLAR_ITEMS_REG.register(bus);
        MekCkStandaloneMachines.WINE_CELLAR_TILES_REG.register(bus);
        MekCkStandaloneMachines.WINE_CELLAR_CONTAINERS_REG.register(bus);
        // 电力研磨机（2026-10-05 迁到 Mek 体系，阶段 3 样板）：同上，三件套必须成组出现。
        MekCkFactories.GRINDING_MACHINE_BLOCKS_REG.register(bus);
        MekCkFactories.GRINDING_MACHINE_ITEMS_REG.register(bus);
        MekCkFactories.GRINDING_MACHINE_TILES_REG.register(bus);
        MekCkFactories.GRINDING_MACHINE_CONTAINERS_REG.register(bus);
        // 坚果爆炒机（2026-10-06 迁到 Mek 体系）：同上，三件套必须成组出现。
        MekCkStandaloneMachines.NUT_ROASTER_BLOCKS_REG.register(bus);
        MekCkStandaloneMachines.NUT_ROASTER_TILES_REG.register(bus);
        MekCkStandaloneMachines.NUT_ROASTER_CONTAINERS_REG.register(bus);
        // 急冻制冰机（2026-10-06 迁到 Mek 体系）：同上，三件套必须成组出现。
        MekCkStandaloneMachines.ICE_MAKER_BLOCKS_REG.register(bus);
        MekCkStandaloneMachines.ICE_MAKER_TILES_REG.register(bus);
        MekCkStandaloneMachines.ICE_MAKER_CONTAINERS_REG.register(bus);
        bus.addListener(this::addCreativeTabContents);
        bus.addListener(this::onCommonSetup);
        // 配置文件生成到 config/mekck/mekck-common.toml（与 planting 等配置文件同目录）
        // ModLoadingContext.get() 自 Forge 1.21.1 起 forRemoval，但 47.4.16（本项目锁定的版本）
        // 里它仍是唯一入口：registerConfig 是实例方法，ModLoadingContext 没有静态等价物。
        // 升到新版 Forge 时这里要跟着换成新版 mod 构造器上的 config 注册入口。
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
            cn.ism.mekck.compat.GuideMECompat.registerGuidebook();
        }

        // 应用能源2（AE2）可选联动：为烹饪工厂/穿串工厂挂接 IInWorldGridNodeHost 能力
        // （AE2 未安装时 AE2Compat.isLoaded() 为 false，直接短路）
        MinecraftForge.EVENT_BUS.addGenericListener(BlockEntity.class, (AttachCapabilitiesEvent<BlockEntity> event) -> {
            if (cn.ism.mekck.compat.AE2Compat.isLoaded()) {
                cn.ism.mekck.compat.AE2Compat.attachCapabilities(event);
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

    /**
     * 是否已完成过本模组的 common setup。
     *
     * <h3>为什么必须自己上这道闸（2026-10-03 实机定位）</h3>
     * {@code FMLCommonSetupEvent} 在本环境下会被<b>投递两次</b>（两次运行实测均如此，
     * 与本次改动无关；同一次运行里 Mekanism 也打了两次 "Mod loaded."）。而下面两步
     * <b>都不是幂等的</b>：
     * <ul>
     *   <li>{@code CriteriaTriggers.register} —— 第二次抛
     *       {@code IllegalArgumentException: Duplicate criterion id mekck:network_connected}；</li>
     *   <li>{@code ModMessages.register} 里的 {@code SimpleChannel.registerMessage} —— 重复注册同一 id 也会抛。</li>
     * </ul>
     * 该异常让本模组进入 FML 的 <b>broken mod state</b>，后果是灾难性的连锁：
     * <pre>
     *   RegisterGeometryLoaders 被拒 → 模型加载器表为空（"Registered loaders:" 后面什么都没有）
     *   → 所有 forge:composite 模型解析失败（mekck 148 + mekanism 107 + extras 79 + create 30）
     *   → BuildCreativeModeTabContentsEvent 被拒 → 创造模式物品栏为空、物品不可见
     * </pre>
     * 崩溃日志里 mekck 的状态是 {@code ERROR}，而 {@code Suspected Mods: NONE} —— 归因看不到它。
     *
     * <p>所以「事件为什么投递两次」不是本模组能控制的事，但「重复投递不该把自己搞崩」是。
     * 用这个 volatile 标记只做一次，重复投递成为无害的 no-op。</p>
     */
    private static volatile boolean commonSetupDone;

    private void onCommonSetup(FMLCommonSetupEvent event) {
        if (commonSetupDone) {
            // 重复投递：全部注册都已在第一次完成，直接返回（详见 commonSetupDone 的注释）。
            return;
        }
        commonSetupDone = true;
        // 自定义进度触发器：mekck 机器接入 ME 网络（网络厨师学徒）
        net.minecraft.advancements.CriteriaTriggers.register(cn.ism.mekck.advancement.NetworkConnectedTrigger.get());
        event.enqueueWork(ModMessages::register);
        // §F33：machinepreview 多方块形状登记必须在注册事件之后——构造期 DeferredHolder 尚未解析，
        //   直接 .get() 会抛 "Registry Object not present: mekck:planting_cutting_station"、被 setup() 内 catch
        //   吞成一条 warn 并从该处中断，导致后续种植工厂/生物反应堆尺寸全部漏登记。
        //   FMLCommonSetupEvent 时点所有方块已注册，enqueueWork 里调安全；未装 machinepreview 时 setup() 内部直接返回。
        event.enqueueWork(cn.ism.mekck.compat.MachinePreviewCompat::setup);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            return;
        }
        MekCkFactories.addToCreativeTab(event);
        MekCkStandaloneMachines.addToCreativeTab(event);
        MekCkLegacyMachines.addToCreativeTab(event);
        MekCkFluids.addToCreativeTab(event);
        MekCkItems.addToCreativeTab(event);
    }
}
