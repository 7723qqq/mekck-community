package cn.ism.mekck.block;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.WineCellarBlockEntity;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

/**
 * 陈化窖（时间悖论产生器，F20）方块：独立容器方块（非 SimpleMachine 加工机），
 * 右键开 GUI，无激活态计时逻辑；能量/催陈都在 {@link WineCellarBlockEntity} 的 serverTick 里跑。
 */
public final class WineCellarBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public WineCellarBlock() {
        super(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.WOOD).requiresCorrectToolForDrops());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
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
        return RenderShape.MODEL;
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof WineCellarBlockEntity cellar) {
            NetworkHooks.openScreen(serverPlayer, cellar, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !cn.ism.mekck.util.TierInstallerHandler.isUpgrading() && level.getBlockEntity(pos) instanceof WineCellarBlockEntity cellar) {
            ItemStack stack = new ItemStack(this);
            cellar.saveToItem(stack); // 把 Items/Energy/Speed 写进掉落物 BlockEntityTag
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 抑制战利品表掉落：本方块的全部状态（Items/Energy/Speed/Progress/AgedDays）**只能**经
     * {@link WineCellarBlockEntity#saveToItem} 写进掉落物，走战利品表只会掉一个裸方块。
     *
     * <p>当前 {@code data/mekck/loot_tables/blocks/wine_cellar.json} 并不存在（等于没有战利品表掉落），
     * 所以这条覆写今天是空操作。它与其余 14 个自研加工方块保持同一份契约，目的是堵住一个陷阱：
     * 将来若有人补上该战利品表（或把方块属性改成 {@code Properties.copy(...)} 继承别人的战利品表），
     * 就会与 {@code onRemove} 的掉落撞成「双掉落」或「内容蒸发」——显式返空后，
     * {@code onRemove} 永远是掉落的唯一出口。</p>
     */
    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WineCellarBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, MekCkStandaloneMachines.WINE_CELLAR_BLOCK_ENTITY.get(), WineCellarBlockEntity::clientTick);
        }
        return createTickerHelper(type, MekCkStandaloneMachines.WINE_CELLAR_BLOCK_ENTITY.get(), WineCellarBlockEntity::serverTick);
    }
}
