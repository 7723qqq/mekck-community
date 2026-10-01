package cn.ism.mekck.util;

import cn.ism.mekck.CuttingMachineFactoryTier;
import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.block.CookingFactoryBlock;
import cn.ism.mekck.block.CuttingMachineFactoryBlock;
import cn.ism.mekck.block.ElectricGrindingMachineBlock;
import cn.ism.mekck.block.GrillBlock;
import cn.ism.mekck.block.GrillFactoryBlock;
import cn.ism.mekck.block.GrindingFactoryBlock;
import cn.ism.mekck.block.IceFactoryBlock;
import cn.ism.mekck.block.IceMakerBlock;
import cn.ism.mekck.block.PlantingCuttingFactoryBlock;
import cn.ism.mekck.block.PlantingCuttingStationBlock;
import cn.ism.mekck.block.SkeweringFactoryBlock;
import cn.ism.mekck.block.SkeweringMachineBlock;
import cn.ism.mekck.block.SmartCookingPotBlock;
import cn.ism.mekck.block.UniversalCuttingMachineBlock;
import cn.ism.mekck.machine.MekCkMachineTile;
import cn.ism.mekck.machine.MekCkSlotNbt;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.HashMap;
import java.util.Map;
import cn.ism.mekck.registry.MekCkFactories;

/**
 * 工厂安装器支持（对齐 Mekanism ItemTierInstaller 的升级语义）：
 * <ul>
 *   <li>mekanism：basic / advanced / elite / ultimate 安装器；</li>
 *   <li>mekanism_extras：absolute / supreme / cosmic / infinite 安装器；</li>
 *   <li>mekck（自有）：crystal_matrix / nebula / singularity 安装器（第 9/10/11 级）；</li>
 *   <li>avaritia:infinity_upgrade（无尽升级组件）：视为**奇点创世等级安装器**，可替代任意低等级安装器，
 *       且每次使用<b>扣 1 点耐久</b>而非消耗（对齐无尽贪婪重生原版行为）；与奇点创世工厂安装器定位不冲突，两者并存；</li>
 *   <li>avaritia_delight 的三把刀（crystal_knife / neutronium_knife / infinity_knife）：
 *       既是「对应等级工厂方块」的合成材料，也<b>可直接当工厂安装器使用</b>。</li>
 * </ul>
 * 规则：
 * <ul>
 *   <li>基础机器（通用切菜机/智能厨锅/穿串机/电力烧烤架/种植切配站/电力研磨机/急冻制冰机）
 *       可用<b>任意等级</b>安装器或无尽升级组件升级为<b>对应基础工厂</b>；</li>
 *   <li>工厂方块：安装器目标等级高于当前等级即可升一级（高等级可替代低等级）；</li>
 *   <li>无尽升级组件对工厂：奇点创世已满级不可升；<b>切菜/烹饪工厂仅晶钛矩阵及以上不可用</b>
 *       （悖论无限及以下可升），穿串/烧烤/种植切配/研磨工厂任意等级（低于奇点）可升。</li>
 *   <li><b>切菜工厂与烹饪工厂不接受任何工厂安装器升级</b>（含 Mekanism/mek_extras 的 8 种、
 *       MekCK 自有 3 种、以及无尽乐事的刀）——这两台机器的每一级都通过对应材料直接合成；
 *       无尽升级组件是唯一例外（按上一行规则）。</li>
 *   <li>升级保留方块实体数据（物品/能量/进度/侧边配置/红石/流体等），种植切配站/工厂同步迁移上方绑定块。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TierInstallerHandler {

    /** 各安装器对应的目标等级（升级判定：目标等级高于当前等级即可升一级，高等级可替代低等级）。 */
    private static final Map<ResourceLocation, CuttingMachineFactoryTier> INSTALLERS = new HashMap<>();

    static {
        INSTALLERS.put(new ResourceLocation("mekanism", "basic_tier_installer"), CuttingMachineFactoryTier.BASIC);
        INSTALLERS.put(new ResourceLocation("mekanism", "advanced_tier_installer"), CuttingMachineFactoryTier.ADVANCED);
        INSTALLERS.put(new ResourceLocation("mekanism", "elite_tier_installer"), CuttingMachineFactoryTier.ELITE);
        INSTALLERS.put(new ResourceLocation("mekanism", "ultimate_tier_installer"), CuttingMachineFactoryTier.ULTIMATE);
        INSTALLERS.put(new ResourceLocation("mekanism_extras", "absolute_tier_installer"), CuttingMachineFactoryTier.ABSOLUTE);
        INSTALLERS.put(new ResourceLocation("mekanism_extras", "supreme_tier_installer"), CuttingMachineFactoryTier.SUPREME);
        INSTALLERS.put(new ResourceLocation("mekanism_extras", "cosmic_tier_installer"), CuttingMachineFactoryTier.COSMIC);
        INSTALLERS.put(new ResourceLocation("mekanism_extras", "infinite_tier_installer"), CuttingMachineFactoryTier.INFINITE);
        // MekCK 自有的三个最终等级安装器
        INSTALLERS.put(new ResourceLocation("mekck", "crystal_matrix_tier_installer"), CuttingMachineFactoryTier.CRYSTAL_MATRIX);
        INSTALLERS.put(new ResourceLocation("mekck", "nebula_tier_installer"), CuttingMachineFactoryTier.NEBULA);
        INSTALLERS.put(new ResourceLocation("mekck", "singularity_tier_installer"), CuttingMachineFactoryTier.SINGULARITY);
        // 无尽乐事的刀：本身是「对应等级工厂方块的合成材料」，同时也可直接当安装器使用
        // （对切菜/烹饪工厂无效——那两台用刀当合成材料，见下方 cuttingCooking 限制）
        INSTALLERS.put(new ResourceLocation("mekck", "blaze_tier_installer"), CuttingMachineFactoryTier.BLAZE);
        INSTALLERS.put(new ResourceLocation("avaritia_delight", "blaze_knife"), CuttingMachineFactoryTier.BLAZE);
        INSTALLERS.put(new ResourceLocation("avaritia_delight", "crystal_knife"), CuttingMachineFactoryTier.CRYSTAL_MATRIX);
        INSTALLERS.put(new ResourceLocation("avaritia_delight", "neutronium_knife"), CuttingMachineFactoryTier.NEBULA);
        INSTALLERS.put(new ResourceLocation("avaritia_delight", "infinity_knife"), CuttingMachineFactoryTier.SINGULARITY);
    }

    /** 无尽升级组件（Re-Avaritia）：不消耗，每次使用扣 1 点耐久。 */
    private static final ResourceLocation INFINITY_UPGRADE = new ResourceLocation("avaritia", "infinity_upgrade");

    /** 升级进行中标记：方块 onRemove 据此跳过"破坏掉落"，避免升级时掉落机器本体。 */
    private static boolean upgrading = false;

    public static boolean isUpgrading() {
        return upgrading;
    }

    private TierInstallerHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getSide() != LogicalSide.SERVER || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            return;
        }
        BlockPos pos = event.getPos();
        Level level = event.getLevel();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        ResourceLocation heldId = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (heldId == null) {
            return;
        }

        CuttingMachineFactoryTier current = factoryTierOf(block);
        Block newBlock = null;
        boolean multiblock = block instanceof PlantingCuttingFactoryBlock || block instanceof PlantingCuttingStationBlock;

        if (heldId.equals(INFINITY_UPGRADE)) {
            // ── 无尽升级组件（扣耐久） ──
            Block baseFactory = baseFactoryOf(block);
            if (baseFactory != null) {
                // 基础机器 → 对应基础工厂
                newBlock = baseFactory;
            } else if (current != null) {
                // 切菜/烹饪工厂：仅晶钛矩阵及以上不可用（悖论无限及以下可升）；其余工厂低于奇点可升
                boolean highTierCuttingCooking = (block instanceof CuttingMachineFactoryBlock || block instanceof CookingFactoryBlock)
                        && current.ordinal() >= CuttingMachineFactoryTier.CRYSTAL_MATRIX.ordinal();
                if (!highTierCuttingCooking && current != CuttingMachineFactoryTier.SINGULARITY) {
                    newBlock = nextBlockOf(block, current.next());
                }
            }
            if (newBlock == null) {
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(false));
            upgradeMachine(level, pos, state, newBlock, multiblock, event.getEntity());
            // 无尽升级组件：扣 1 点耐久（不消耗，创造模式不扣）
            if (!event.getEntity().isCreative()) {
                held.hurtAndBreak(1, event.getEntity(), p -> {
                });
            }
        } else {
            // ── Mekanism / mek_extras 安装器（消耗） ──
            CuttingMachineFactoryTier target = INSTALLERS.get(heldId);
            if (target == null) {
                return;
            }
            if (current != null) {
                // 切菜工厂与烹饪工厂**不使用工厂安装器升级**：它们的每一级都靠对应材料直接合成
                // （切菜：精密锯木机 / 各等级刀；烹饪：各等级厨锅类材料）。
                // 无尽升级组件不受此限制（见上方分支）。
                if (block instanceof CuttingMachineFactoryBlock || block instanceof CookingFactoryBlock) {
                    return;
                }
                if (target.ordinal() > current.ordinal()) {
                    newBlock = nextBlockOf(block, current.next());
                }
            } else if (baseFactoryOf(block) != null) {
                // 基础机器：任意等级安装器均可升级（基础机器 → 对应基础工厂；高等级安装器同样有效）
                newBlock = baseFactoryOf(block);
            }
            if (newBlock == null) {
                return;
            }
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(false));
            upgradeMachine(level, pos, state, newBlock, multiblock, event.getEntity());
            if (!event.getEntity().isCreative()) {
                held.shrink(1);
            }
        }
    }

    /** 工厂方块 → 当前等级；非工厂返回 null。 */
    private static CuttingMachineFactoryTier factoryTierOf(Block block) {
        if (block instanceof CuttingMachineFactoryBlock b) return b.getTier();
        if (block instanceof CookingFactoryBlock b) return b.getTier();
        if (block instanceof SkeweringFactoryBlock b) return b.getTier();
        if (block instanceof GrillFactoryBlock b) return b.getTier();
        if (block instanceof PlantingCuttingFactoryBlock b) return b.getTier();
        if (block instanceof GrindingFactoryBlock b) return b.getTier();
        if (block instanceof IceFactoryBlock b) return b.getTier();
        return null;
    }

    /** 基础机器 → 对应基础工厂方块；非基础机器返回 null。 */
    private static Block baseFactoryOf(Block block) {
        if (block instanceof UniversalCuttingMachineBlock) {
            return basicOf(MekCkFactories.FACTORY_BLOCKS);
        }
        if (block instanceof SmartCookingPotBlock) {
            return basicOf(MekCkFactories.COOKING_FACTORY_BLOCKS);
        }
        if (block instanceof SkeweringMachineBlock) {
            return basicOf(MekCkFactories.SKEWERING_FACTORY_BLOCKS);
        }
        if (block instanceof GrillBlock) {
            return basicOf(MekCkFactories.GRILL_FACTORY_BLOCKS);
        }
        if (block instanceof PlantingCuttingStationBlock) {
            return basicOf(MekCkFactories.PLANTING_CUTTING_FACTORY_BLOCKS);
        }
        if (block instanceof ElectricGrindingMachineBlock) {
            return basicOf(MekCkFactories.GRINDING_FACTORY_BLOCKS);
        }
        if (block instanceof IceMakerBlock) {
            return basicOf(MekCkFactories.ICE_FACTORY_BLOCKS);
        }
        return null;
    }

    private static Block basicOf(Map<CuttingMachineFactoryTier, RegistryObject<Block>> map) {
        RegistryObject<Block> ro = map.get(CuttingMachineFactoryTier.BASIC);
        return ro == null ? null : ro.get();
    }

    private static Block nextBlockOf(Block oldBlock, CuttingMachineFactoryTier next) {
        Map<CuttingMachineFactoryTier, RegistryObject<Block>> map;
        if (oldBlock instanceof CuttingMachineFactoryBlock) {
            map = MekCkFactories.FACTORY_BLOCKS;
        } else if (oldBlock instanceof CookingFactoryBlock) {
            map = MekCkFactories.COOKING_FACTORY_BLOCKS;
        } else if (oldBlock instanceof SkeweringFactoryBlock) {
            map = MekCkFactories.SKEWERING_FACTORY_BLOCKS;
        } else if (oldBlock instanceof GrillFactoryBlock) {
            map = MekCkFactories.GRILL_FACTORY_BLOCKS;
        } else if (oldBlock instanceof PlantingCuttingFactoryBlock) {
            map = MekCkFactories.PLANTING_CUTTING_FACTORY_BLOCKS;
        } else if (oldBlock instanceof GrindingFactoryBlock) {
            map = MekCkFactories.GRINDING_FACTORY_BLOCKS;
        } else if (oldBlock instanceof IceFactoryBlock) {
            map = MekCkFactories.ICE_FACTORY_BLOCKS;
        } else {
            return null;
        }
        RegistryObject<Block> ro = map.get(next);
        return ro == null ? null : ro.get();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setValueUnchecked(BlockState state, Property prop, Comparable value) {
        return state.setValue(prop, value);
    }

    private static void upgradeMachine(Level level, BlockPos pos, BlockState oldState, Block newBlock,
                                       boolean multiblock, Player player) {
        if (newBlock == null) {
            return;
        }
        // 1. 保存旧方块实体数据（含物品/能量/进度/侧边配置/红石/流体等）
        BlockEntity oldTile = level.getBlockEntity(pos);
        CompoundTag data = oldTile == null ? new CompoundTag() : oldTile.saveWithoutMetadata();
        // 换档会移动并行方阵家族的槽位边界（输入/输出各 = 并行数），而 MekCkSlotNbt 是按
        // int 下标灌的 ⇒ 必须按角色重映射，否则物品静默错位（BASIC(3)→ADVANCED(5) 时
        // 旧「输出 0」会落进新「输入 3」）。这里先记下旧布局，第 4 步拿到新 tile 后再算。
        int oldInputCount = oldTile instanceof MekCkMachineTile machine ? machine.inputSlotCount() : -1;
        int oldOutputCount = oldTile instanceof MekCkMachineTile machine ? machine.outputSlotCount() : -1;
        // 2. 多方块（种植切配站/工厂 1×2×1）先移除上方绑定块
        if (multiblock) {
            MekCkMultiblock.removeBoundingBlocks(level, pos, oldState, MekCkMultiblock.SHAPE_2_TALL);
        }
        // 3. 替换为升级方块，保留同名方块属性（朝向/激活等）；升级期间 onRemove 跳过掉落
        BlockState newState;
        upgrading = true;
        try {
            newState = newBlock.defaultBlockState();
            for (Property<?> prop : oldState.getProperties()) {
                if (newState.hasProperty(prop)) {
                    newState = setValueUnchecked(newState, prop, oldState.getValue(prop));
                }
            }
            level.setBlock(pos, newState, Block.UPDATE_ALL);
        } finally {
            upgrading = false;
        }
        // 4. 恢复数据到新方块实体
        BlockEntity newTile = level.getBlockEntity(pos);
        if (newTile != null && !data.isEmpty()) {
            // 换档前先按角色重映射 MekCkSlots 的 int 下标（见第 1 步的说明）。
            if (oldInputCount >= 0 && newTile instanceof MekCkMachineTile machine) {
                MekCkSlotNbt.remapForLayoutChange(data, oldInputCount, oldOutputCount,
                        machine.inputSlotCount(), machine.outputSlotCount());
            }
            newTile.load(data);
            newTile.setChanged();
        }
        // 5. 多方块重新放置绑定块
        if (multiblock) {
            MekCkMultiblock.placeBoundingBlocks(level, pos, newState, MekCkMultiblock.SHAPE_2_TALL);
        }
    }
}
