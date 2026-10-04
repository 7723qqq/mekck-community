package cn.ism.mekck.block;

import cn.ism.mekck.MachineKind;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import cn.ism.mekck.upgrade.UpgradeHelper;
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
import cn.ism.mekck.registry.MekCkLegacyMachines;

/** 四合一基础机器方块（寿司卷制机/平均切段机/饭团成型机/凝乳成型机）。 */
public final class SimpleMachineBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    private final MachineKind kind;

    public SimpleMachineBlock(MachineKind kind) {
        super(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).requiresCorrectToolForDrops());
        this.kind = kind;
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ACTIVE, false));
    }

    public MachineKind getKind() {
        return kind;
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
                && level.getBlockEntity(pos) instanceof SimpleMachineBlockEntity machine) {
            if (player.isShiftKeyDown()) {
                ItemStack held = player.getItemInHand(hand);
                if (!held.isEmpty() && UpgradeHelper.isUpgrade(held)) {
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
                // 陈酿机：潜行 + 空手/非升级物品右键 → 清空果汁液位池（复刻原版发酵桶潜行右键清桶）
                if (machine.getMachineKind() == cn.ism.mekck.MachineKind.WINERY && machine.getJuiceLevel() > 0) {
                    machine.clearJuicePool();
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.mekck.juice_cleared"), true);
                    return InteractionResult.sidedSuccess(false);
                }
            }
            // 陈酿机：手持流体桶/罐右键 → 灌入内部 inputTank（复刻 Mekanism「手持流体桶右键灌机器」），成功后不打开 GUI。
            if (machine.getMachineKind() == cn.ism.mekck.MachineKind.WINERY
                    && machine.tryInsertHeldFluid(player.getItemInHand(hand), player, hand)) {
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            // 布局描述随 OpenScreen 下发：客户端 BE 为 null（方块已破坏/区块卸载）时
            // 仍能按真实布局建槽，见 SimpleMachineMenu 的显式布局构造器。
            NetworkHooks.openScreen(serverPlayer, machine, buf -> {
                buf.writeBlockPos(pos);
                buf.writeBoolean(machine.usesExtendedInputSlots());
                buf.writeBoolean(machine.getMachineKind() == MachineKind.WINERY);
            });
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (stack.hasCustomHoverName() && level.getBlockEntity(pos) instanceof SimpleMachineBlockEntity machine) {
            machine.setCustomName(stack.getHoverName());
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !TierInstallerHandler.isUpgrading()
                && level.getBlockEntity(pos) instanceof SimpleMachineBlockEntity machine) {
            ItemStack stack = new ItemStack(this);
            machine.saveToItem(stack);
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SimpleMachineBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return createTickerHelper(type, MekCkLegacyMachines.SIMPLE_MACHINE_BLOCK_ENTITY.get(), SimpleMachineBlockEntity::clientTick);
        }
        return createTickerHelper(type, MekCkLegacyMachines.SIMPLE_MACHINE_BLOCK_ENTITY.get(), SimpleMachineBlockEntity::serverTick);
    }

    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }
}
