package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 环境温度助手：计算机器周围的热源温度（摄氏度）。
 *
 * 当前状态：**预留工具，暂无调用方**。
 *
 * <p>注意区分两套「温度」：本类计算的是**环境热源温度**（摄氏度，用于判断"附近有没有火/热源"，
 * 面向烘焙坊那类设温度条件的配方）；而 MekCK 机器实际接入的是
 * **通用机械的热容量模型**（见 {@link cn.ism.mekck.util.MekCkHeatComponent}，机身自带温度并与
 * 相邻热力设备传热）。烘焙坊配方在 MekCK 中执行时**不设温度条件**（不检查 min/max、不烧焦），
 * 因此本类目前没有调用方，仅作为后续「机器受环境热源影响」类功能的预留工具保留。
 * 若确认不再需要，可直接删除。</p>
 *
 * 温度来源（取周围最高值）：
 * 1. 气动工艺（PneumaticCraft）热属性：通过其公开 API
 *    BlockHeatProperties.getInstance().getCustomHeatEntry(level, state) 读取方块温度（开尔文 → 摄氏度），
 *    全程反射调用，未安装该模组时自动跳过；
 * 2. 农夫乐事 farmersdelight:heat_sources 标签方块（视为 200 ℃）；
 * 3. 原版岩浆块 / 岩浆 / 营火 / 火（视为 200 ℃）；
 * 4. 无热源时为环境温度 20 ℃。
 */
public final class TemperatureHelper {

    /** 环境（无热源）温度，摄氏度。 */
    public static final int AMBIENT_C = 20;
    /** 通用热源（FD 标签 / 原版火源）温度，摄氏度。 */
    public static final int HEAT_SOURCE_C = 200;

    private static final TagKey<Block> FD_HEAT_SOURCES =
            net.minecraft.tags.BlockTags.create(new ResourceLocation("farmersdelight", "heat_sources"));

    /** 气动工艺 BlockHeatProperties 类（惰性解析，未安装为 null）。 */
    private static Class<?> pneumaticHeatPropsClass;
    private static boolean pneumaticLookupTried = false;

    private TemperatureHelper() {
    }

    /** 计算机器位置周围（下方 2 格 + 6 邻域）的最高热源温度（摄氏度）。 */
    public static int getAmbientTemperatureC(Level level, BlockPos pos) {
        if (level == null || pos == null) return AMBIENT_C;
        int best = AMBIENT_C;
        for (BlockPos p : new BlockPos[]{
                pos.below(), pos.below(2),
                pos.north(), pos.south(), pos.east(), pos.west(), pos.above()}) {
            int t = blockTemperatureC(level, p);
            if (t > best) best = t;
        }
        return best;
    }

    /** 单个方块的热源温度（摄氏度）；非热源返回 AMBIENT_C。 */
    public static int blockTemperatureC(Level level, BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos)) return AMBIENT_C;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return AMBIENT_C;
        // 1. 气动工艺热属性（最高优先级：可给出精确温度）
        Integer pneumatic = pneumaticTemperatureC(level, state);
        if (pneumatic != null) return pneumatic;
        // 2. 农夫乐事热源标签
        if (state.is(FD_HEAT_SOURCES)) return HEAT_SOURCE_C;
        // 3. 原版火源
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.LAVA) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SOUL_FIRE)) {
            return HEAT_SOURCE_C;
        }
        return AMBIENT_C;
    }

    /**
     * 通过气动工艺公开 API 读取方块温度（开尔文 → 摄氏度）。
     * 未安装气动工艺、方块无热属性或调用失败时返回 null。
     */
    private static Integer pneumaticTemperatureC(Level level, BlockState state) {
        Class<?> clazz = pneumaticClass();
        if (clazz == null) return null;
        try {
            Object instance = clazz.getMethod("getInstance").invoke(null);
            if (instance == null) return null;
            Object recipe = clazz.getMethod("getCustomHeatEntry", Level.class, BlockState.class)
                    .invoke(instance, level, state);
            if (recipe == null) return null;
            Object temp = cn.ism.mekck.util.Reflect.call(recipe, "getTemperature");
            if (temp instanceof Integer kelvin) {
                return kelvin - 273; // 开尔文 → 摄氏度
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 惰性解析气动工艺 BlockHeatProperties 类（Class.forName，未安装返回 null 并缓存结果）。 */
    private static Class<?> pneumaticClass() {
        if (pneumaticLookupTried) return pneumaticHeatPropsClass;
        pneumaticLookupTried = true;
        try {
            pneumaticHeatPropsClass = Class.forName("me.desht.pneumaticcraft.common.heat.BlockHeatProperties");
        } catch (Throwable ignored) {
            pneumaticHeatPropsClass = null;
        }
        return pneumaticHeatPropsClass;
    }
}
