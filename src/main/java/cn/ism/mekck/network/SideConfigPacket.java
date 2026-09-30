package cn.ism.mekck.network;

import cn.ism.mekck.SideMode;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.blockentity.ElectricGrindingMachineBlockEntity;
import cn.ism.mekck.blockentity.NutRoasterBlockEntity;
import cn.ism.mekck.blockentity.SimpleMachineBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class SideConfigPacket {
    /** 配置类型：物品。 */
    public static final int TYPE_ITEM = 0;
    /** 配置类型：流体。 */
    public static final int TYPE_FLUID = 1;
    /** 配置类型：气体。 */
    public static final int TYPE_GAS = 2;

    private final BlockPos pos;
    private final int directionOrdinal;
    private final int sideModeOrdinal;
    private final int configType;

    public SideConfigPacket(BlockPos pos, Direction direction, SideMode mode) {
        this(pos, direction, mode, TYPE_ITEM);
    }

    public SideConfigPacket(BlockPos pos, Direction direction, SideMode mode, int configType) {
        this.pos = pos;
        this.directionOrdinal = direction.ordinal();
        this.sideModeOrdinal = mode.ordinal();
        this.configType = configType;
    }

    public SideConfigPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.directionOrdinal = buffer.readByte();
        this.sideModeOrdinal = buffer.readByte();
        this.configType = buffer.readByte();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeByte(directionOrdinal);
        buffer.writeByte(sideModeOrdinal);
        buffer.writeByte(configType);
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            BlockEntity be = PacketGuard.target(player, pos);
            if (be == null) return;
            if (directionOrdinal < 0 || directionOrdinal >= cn.ism.mekck.util.Directions.VALUES.length) return;
            if (sideModeOrdinal < 0 || sideModeOrdinal >= SideMode.values().length) return;
            Direction dir = cn.ism.mekck.util.Directions.VALUES[directionOrdinal];
            SideMode mode = SideMode.values()[sideModeOrdinal];
            // 流体侧面配置（独立于物品侧配）
            if (configType == TYPE_FLUID) {
                if (be instanceof SimpleMachineBlockEntity machine) {
                    machine.setFluidSideMode(dir, mode);
                } else if (be instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity machine) {
                    machine.setFluidSideMode(dir, mode);
                }
                return;
            }
            // 气体侧面配置
            if (configType == TYPE_GAS) {
                if (be instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity machine) {
                    machine.setGasSideMode(dir, mode);
                }
                return;
            }
            // 三明治组装机的物品侧配
            if (be instanceof cn.ism.mekck.blockentity.SandwichAssemblerBlockEntity assembler) {
                assembler.setItemSideMode(dir, mode);
                return;
            }
            // 中央厨房的物品侧配
            if (be instanceof cn.ism.mekck.blockentity.CentralKitchenBlockEntity kitchen) {
                kitchen.setItemSideMode(dir, mode);
                return;
            }
            // 切菜工厂分支在阶段 2 Task 4.6 删除：新的 CuttingFactoryTile 是
            // TileEntityConfigurableMachine，物品侧配由 Mek 自己的 configComponent 持有、
            // 由 Mek 自己的侧配界面写入，MekCK 这个包对它永远不生效。
            // 电力烧烤架分支在阶段 3 同理删除（GrillBlockEntity 也换成了
            // TileEntityConfigurableMachine）。
            // 切菜机分支在第四轮删除：新的 UniversalCuttingMachineTile 是
            // TileEntityConfigurableMachine，物品侧配由 Mek 自己的 configComponent 持有、
            // 由 Mek 自己的侧配界面写入，本包对它永远不生效（同 GrillBlockEntity / CuttingFactoryTile）。
            if (be instanceof SmartCookingPotBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof SkeweringMachineBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof PlantingCuttingStationBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof IceMakerBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof IceFactoryBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof SimpleMachineBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof ChocolateCannonBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof ElectricGrindingMachineBlockEntity machine) {
                machine.setSideMode(dir, mode);
            } else if (be instanceof NutRoasterBlockEntity machine) {
                machine.setSideMode(dir, mode);
            }
        });
        context.setPacketHandled(true);
    }
}