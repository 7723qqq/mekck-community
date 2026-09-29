package cn.ism.mekck.util;

import net.minecraft.core.Direction;

/**
 * 方向常量。
 * <p>
 * {@code Direction.values()} **每次调用都会克隆一份数组**（JDK 枚举语义），而它常被写在每 tick / 每帧的
 * 循环里（热量传导、液体自动 IO、预览渲染等）。这里提供一份只读副本，热路径统一使用。
 * </p>
 */
public final class Directions {

    /** 六个方向的只读副本（切勿修改）。 */
    public static final Direction[] VALUES = Direction.values();

    private Directions() {
    }
}
