package cn.ism.mekck.blockentity;

import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IMekanismHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 热能力跨模组识别 gametest。
 *
 * 验证目标：MekCK 的加热/制冷类机器在真实游戏环境下
 * 1）方块实体本身实现了 Mekanism 的 IMekanismHeatHandler（气动工艺 PNC:R 的
 *    MekanismIntegration.isMekHeatHandler 正是按 blockEntity instanceof IMekanismHeatHandler 判定，
 *    只有通过该判定 PNC 才会给方块挂上 mek2pnc 热适配器）；
 * 2）Capabilities.HEAT_HANDLER 能力可解析且返回的对象同样是 IMekanismHeatHandler；
 * 3）热量的读写、温度换算、热容量/逆传导/逆绝缘取值与电阻型加热器一致。
 */
@GameTestHolder("mekck")
@PrefixGameTestTemplate(false)
public class TestMekCkHeatIntegration {

    private static final String TEMPLATE = "empty";
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void iceMakerIsMekanismHeatHandler(GameTestHelper helper) {
        helper.setBlock(POS, cn.ism.mekck.registry.MekCkStandaloneMachines.ICE_MAKER_HANDLE.getBlock());
        BlockEntity be = helper.getBlockEntity(POS);
        helper.assertTrue(be instanceof cn.ism.mekck.machine.icemaker.IceMakerTile,
                "ice_maker 方块实体类型不符: " + be);

        // 1) PNC 的判定条件
        helper.assertTrue(be instanceof IMekanismHeatHandler,
                "IceMakerTile 未实现 IMekanismHeatHandler，PNC 不会挂载热适配器");

        // 2) 能力解析
        var resolved = be.getCapability(Capabilities.HEAT_HANDLER, null).resolve().orElse(null);
        helper.assertTrue(resolved != null, "HEAT_HANDLER 能力无法解析");
        helper.assertTrue(resolved instanceof IMekanismHeatHandler,
                "能力对象不是 IMekanismHeatHandler: " + resolved);

        // 3) 参数与热量读写（与电阻型加热器一致：热容量 100、逆传导 5、逆绝缘 100）
        helper.assertTrue(resolved.getHeatCapacitorCount() == 1, "热容量容器数量应为 1");
        helper.assertTrue(Math.abs(resolved.getHeatCapacity(0) - 100.0) < 1.0e-9, "热容量应为 100 J/K");
        helper.assertTrue(Math.abs(resolved.getInverseConduction(0) - 5.0) < 1.0e-9, "逆传导应为 5.0");
        // 初始温度 = 该位置的环境温度（生物群系相关，故从测试世界实取，不硬编码 300 K）
        double ambient = HeatAPI.getAmbientTemp(helper.getLevel(), helper.absolutePos(POS));
        double before = resolved.getTemperature(0);
        helper.assertTrue(Math.abs(before - ambient) < 1.0e-6,
                "初始温度应等于环境温度 " + ambient + "，实际: " + before);

        resolved.handleHeat(0, 1000.0);
        // Mek 的热模型是「本 tick 累积、tick 末尾统一落账」：BasicHeatCapacitor.handleHeat
        // 只把量累进 heatToHandle，真正改 storedHeat 的是 update()。
        // 旧的 MekCkHeatComponent.handleHeat 是自己立刻 update 的，迁到 TileEntityMekanism
        // 之后这一句就是替代品（生产路径上是基类 tickServer 末尾的 updateHeatCapacitors(null)）。
        updateHeatCapacitors(be);
        helper.assertTrue(Math.abs(resolved.getTemperature(0) - (before + 10.0)) < 1.0e-6,
                "注入 1000 J 后温度应升高 10 K（热容量 100 J/K），实际: " + resolved.getTemperature(0));
        helper.succeed();
    }

    /** 让累积的热量落账（见 {@code iceMakerIsMekanismHeatHandler} 里的说明）。 */
    private static void updateHeatCapacitors(BlockEntity be) {
        if (be instanceof mekanism.common.capabilities.heat.ITileHeatHandler heat) {
            heat.updateHeatCapacitors(null);
        }
    }

    /** 加热类机器（坚果爆炒机）走同一条路径。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void nutRoasterIsMekanismHeatHandler(GameTestHelper helper) {
        helper.setBlock(POS, cn.ism.mekck.registry.MekCkStandaloneMachines.NUT_ROASTER_HANDLE.getBlock());
        BlockEntity be = helper.getBlockEntity(POS);
        helper.assertTrue(be instanceof cn.ism.mekck.machine.roasting.NutRoasterTile,
                "nut_roaster 方块实体类型不符: " + be);
        helper.assertTrue(be instanceof IMekanismHeatHandler, "NutRoasterTile 未实现 IMekanismHeatHandler");
        var resolved = be.getCapability(Capabilities.HEAT_HANDLER, null).resolve().orElse(null);
        helper.assertTrue(resolved instanceof IMekanismHeatHandler, "坚果爆炒机热能力不可解析或类型不符");
        helper.succeed();
    }
}
