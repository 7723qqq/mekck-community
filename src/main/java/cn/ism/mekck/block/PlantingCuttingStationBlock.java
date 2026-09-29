package cn.ism.mekck.block;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
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
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class PlantingCuttingStationBlock extends BaseEntityBlock {
    // 2-block tall structure: main block + bounding block above
    private static final VoxelShape SHAPE = Shapes.box(0, 0, 0, 1, 2, 1);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    /** 绑定方块形状：1×2×1（主方块 + 上方 1 个）。 */
    private static final mekanism.api.functions.TriConsumer<BlockPos, BlockState, java.util.stream.Stream.Builder<BlockPos>> BOUNDING_SHAPE = MekCkMultiblock.SHAPE_2_TALL;

    public PlantingCuttingStationBlock() {
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
        return RenderShape.MODEL;
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
                && level.getBlockEntity(pos) instanceof PlantingCuttingStationBlockEntity machine) {
            if (player.isShiftKeyDown()) {
                ItemStack held = player.getItemInHand(hand);
                if (!held.isEmpty() && cn.ism.mekck.util.UpgradeHelper.isUpgrade(held)) {
                    String upgradeName = held.getHoverName().getString();
                    int added = machine.addUpgradesFromHand(held);
                    if (added > 0) {
                        held.shrink(added);
                        player.setItemInHand(hand, held);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal("§a已安装升级：§f" + upgradeName), true);
                        return InteractionResult.sidedSuccess(false);
                    }
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal("§c无法安装升级：对应槽位已满或本机器不支持该升级"), true);
                    return InteractionResult.sidedSuccess(false);
                }
            }
            NetworkHooks.openScreen(serverPlayer, machine, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName() && level.getBlockEntity(pos) instanceof PlantingCuttingStationBlockEntity machine) {
            machine.setCustomName(stack.getHoverName());
        }
        // 放置绑定方块，组成 1×2×1 多方块整体
        MekCkMultiblock.placeBoundingBlocks(level, pos, state, BOUNDING_SHAPE);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // 清理绑定方块（整体一起破坏）
            MekCkMultiblock.removeBoundingBlocks(level, pos, state, BOUNDING_SHAPE);
            if (level.getBlockEntity(pos) instanceof PlantingCuttingStationBlockEntity machine) {
                // Save block entity data (including inventory) to the item stack and drop it
                ItemStack stack = new ItemStack(this);
                machine.saveToItem(stack);
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        // Prevent the default block drop; inventory is handled by onRemove
        return List.of();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlantingCuttingStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, UniversalCuttingMachine.PLANTING_CUTTING_STATION_BLOCK_ENTITY.get(), PlantingCuttingStationBlockEntity::clientTick);
        }
        return createTickerHelper(type, UniversalCuttingMachine.PLANTING_CUTTING_STATION_BLOCK_ENTITY.get(), PlantingCuttingStationBlockEntity::serverTick);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }
}