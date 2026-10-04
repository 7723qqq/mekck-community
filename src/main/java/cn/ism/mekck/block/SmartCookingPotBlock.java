package cn.ism.mekck.block;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.util.FluidContainerInteract;
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
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import cn.ism.mekck.registry.MekCkStandaloneMachines;

public final class SmartCookingPotBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public SmartCookingPotBlock() {
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
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection());
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
                && level.getBlockEntity(pos) instanceof SmartCookingPotBlockEntity machine) {
            ItemStack held = player.getItemInHand(hand);
            // 手持流体容器右键 → 注入流体
            if (!held.isEmpty() && !player.isShiftKeyDown()) {
                FluidContainerInteract.ContainerFluidInfo info = FluidContainerInteract.getFluidInfo(held);
                if (info != null && info.isValid()
                        && FluidContainerInteract.tryFillMachine(machine.getFluidTank(), held)) {
                    held.shrink(1);
                    if (!held.isEmpty()) {
                        player.setItemInHand(hand, held);
                    }
                    // 给玩家空容器
                    ItemStack emptyContainer = info.emptyContainer();
                    if (!player.getInventory().add(emptyContainer)) {
                        player.drop(emptyContainer, false);
                    }
                    machine.setChanged();
                    return InteractionResult.sidedSuccess(false);
                }
            }
            if (player.isShiftKeyDown()) {
                if (!held.isEmpty() && cn.ism.mekck.upgrade.UpgradeHelper.isUpgrade(held)) {
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
        if (stack.hasCustomHoverName() && level.getBlockEntity(pos) instanceof SmartCookingPotBlockEntity machine) {
            machine.setCustomName(stack.getHoverName());
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !TierInstallerHandler.isUpgrading() && level.getBlockEntity(pos) instanceof SmartCookingPotBlockEntity machine) {
            // Save block entity data (including inventory) to the item stack and drop it
            ItemStack stack = new ItemStack(this);
            machine.saveToItem(stack);
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            level.updateNeighbourForOutputSignal(pos, this);
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
        return new SmartCookingPotBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, MekCkStandaloneMachines.COOKING_POT_BLOCK_ENTITY.get(), SmartCookingPotBlockEntity::clientTick);
        }
        return createTickerHelper(type, MekCkStandaloneMachines.COOKING_POT_BLOCK_ENTITY.get(), SmartCookingPotBlockEntity::serverTick);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }
}