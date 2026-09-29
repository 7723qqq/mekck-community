package cn.ism.mekck.block;

import mekanism.common.block.BlockBounding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;

/**
 * 生物反应堆专用绑定块：继承 Mekanism 的 BlockBounding（保留 use / 能力 / 掉落 对主块的代理），
 * 但 getShape / getCollisionShape 直接返回整块立方体，不再代理到主块后再用 move(-offset) 偏移。
 *
 * <p>原因：Mekanism 主机器（如数字型采矿机）的碰撞外形是“覆盖整个结构”的世界坐标形状，
 * 经 move(-offset) 后能正确铺满每个绑定块；而本模组生物反应堆主块是 1×1×1 普通立方体，
 * 若沿用代理，绑定块的碰撞箱会被算到主块那一格 → 表现为“像空气一样可穿模”。
 * 这里直接让每个绑定块自身就是一整块立方体，碰撞/拾取外形即落在该绑定块自己的格子里。
 */
public final class BioreactorBoundingBlock extends BlockBounding {

    private static final VoxelShape CUBE = Shapes.block();

    public BioreactorBoundingBlock() {
        super();
    }

    @NotNull
    @Override
    @Deprecated
    public VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return CUBE;
    }

    @NotNull
    @Override
    @Deprecated
    public VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return CUBE;
    }
}
