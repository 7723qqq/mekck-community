package cn.ism.mekck.block;

import cn.ism.mekck.blockentity.BioreactorBlockEntity;
import cn.ism.mekck.util.MekCkMultiblock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 生物反应堆方块：2×2×3 多方块结构的主方块（其余 11 格为 Mekanism 绑定方块）。
 * 主方块自身不做单格模型渲染（{@link RenderShape#INVISIBLE}，与绑定方块一致），
 * 整个反应堆模型由 {@code BioreactorRenderer}（BER）叠加 3 个层模型绘制，
 * 并按方块朝向绕 2×2 足迹中心旋转；足迹角落锚定（+X/+Z），旋转后与自身重合。
 */
public final class BioreactorBlock extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Shapes.block();
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /**
     * 绑定方块形状：3×3×3（主方块位于底部正中，绑定方块占其余 26 格）。
     */
    private static final mekanism.api.functions.TriConsumer<BlockPos, BlockState, java.util.stream.Stream.Builder<BlockPos>> BOUNDING_SHAPE =
            MekCkMultiblock.SHAPE_3X3X3;

    public BioreactorBlock() {
        super(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        // 绑定方块位置被占用时禁止放置
        if (!MekCkMultiblock.canPlace(context.getLevel(), context.getClickedPos(), state, BOUNDING_SHAPE)) {
            return null;
        }
        return state;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // 隐藏主方块自身的单格渲染，只显示 BER 绘制的多方块整体模型（与绑定方块一致）
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BioreactorBlockEntity machine) {
            NetworkHooks.openScreen(serverPlayer, machine, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        // 放置绑定方块，组成 2×2×3 多方块整体
        if (!level.isClientSide) {
            org.apache.logging.log4j.LogManager.getLogger("mekck.Multiblock").info("[mekck-bb] BioreactorBlock.setPlacedBy @{}", pos);
        }
        MekCkMultiblock.placeBoundingBlocks(level, pos, state, BOUNDING_SHAPE, MekCkStandaloneMachines.BIOREACTOR_BOUNDING_BLOCK.get());
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // 清理绑定方块（整体一起破坏），并掉落方块本体。
            // 除现行 3×3×3 外还要清一遍旧的 2×2×3：改尺寸之前放下的机器仍带旧布局，
            // 只按新形状清会漏掉 3 个绑定块，在世界里留下拿不掉也点不开的残块。
            MekCkMultiblock.removeBoundingBlocks(level, pos, state, BOUNDING_SHAPE, MekCkStandaloneMachines.BIOREACTOR_BOUNDING_BLOCK.get());
            MekCkMultiblock.removeBoundingBlocks(level, pos, state, MekCkMultiblock.SHAPE_2X2X3_LEGACY, MekCkStandaloneMachines.BIOREACTOR_BOUNDING_BLOCK.get());
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof BioreactorBlockEntity machine) {
                // 必须把 BE 数据（16 个燃料输入槽、动力槽、流体容器、能量、已存流体）
                // 序列化进物品：getDrops 返回空，所以这一个物品是状态的唯一载体，
                // 掉裸方块等于把整台机器的内容物连同储能一起蒸发。
                // 用继承自 BlockEntity 的 saveToItem，它序列化完整的 saveAdditional 输出。
                ItemStack stack = new ItemStack(this);
                machine.saveToItem(stack);
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        // 防止默认掉落；物品由 onRemove 掉落
        return List.of();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BioreactorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, MekCkStandaloneMachines.BIOREACTOR_BLOCK_ENTITY.get(), BioreactorBlockEntity::serverTick);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(ACTIVE) ? 7 : 0;
    }
}
