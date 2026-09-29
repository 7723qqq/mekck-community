package cn.ism.mekck.util;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;

/**
 * §F26：放置预览·Mekanism 能量立方专用小工具（全反射、零编译期 Mekanism 硬依赖）。
 * <p>
 * 背景：{@code MekCkOutlineRenderer.renderBerPreview} 为预览临时 new 一个
 * {@code mekanism.common.tile.TileEntityEnergyCube}（不入世界），其储能为 0 ⇒
 * Mekanism {@code RenderEnergyCube.shouldRender}（{@code getEnergyScale() > 0}）直接 false
 * ⇒ 内部旋转能量核一帧不画（本次回归根因）。这里在取/调 BER 之前给该<b>临时</b> BE 灌满储能，
 * 让 shouldRender 过关。只碰预览临时 BE，绝不触碰世界里真实方块实体。
 * </p>
 * <p>
 * 反射目标已按实机依赖 jar javap 坐实（Mekanism 1.20.1-10.4.16.80）：
 * {@code TileEntityEnergyCube.getEnergyContainer() -> EnergyCubeEnergyContainer}（public），
 * {@code BasicEnergyContainer.setEnergy(FloatingLong)/getMaxEnergy() -> FloatingLong}（public）。
 * {@code FloatingLong} 对象直接从 {@code getMaxEnergy()} 的返回值复用，无需自行构造。
 * 任何一步失败（未装 Mekanism/类改名）静默返回，维持现状不崩。
 * </p>
 */
public final class EnergyCubePreviewUtil {

    /** 目标 BE 的精确类名（能量立方各档共用同一个 tile 类，档位只是 tier 字段）。 */
    private static final String CUBE_TILE_CLASS = "mekanism.common.tile.TileEntityEnergyCube";

    private EnergyCubePreviewUtil() {
    }

    /** 若 be 是 Mekanism 能量立方的（临时预览）方块实体，则把其能量容器灌满。全程静默失败。 */
    public static void seedTempEnergyIfCube(BlockEntity be) {
        if (be == null || !CUBE_TILE_CLASS.equals(be.getClass().getName())) {
            return;
        }
        try {
            Method getContainer = be.getClass().getMethod("getEnergyContainer");
            Object container = getContainer.invoke(be);
            if (container == null) {
                return;
            }
            Method getMaxEnergy = container.getClass().getMethod("getMaxEnergy");
            Object max = getMaxEnergy.invoke(container); // FloatingLong（直接用返回值，免构造）
            if (max == null) {
                return;
            }
            Class<?> floatingLong = max.getClass();
            Method setEnergy = container.getClass().getMethod("setEnergy", floatingLong);
            setEnergy.invoke(container, max);
        } catch (Throwable ignored) {
            // 反射失败 = 维持现状（不灌能、预览回到旧观感），绝不影响其它 BER 预览
        }
    }
}
