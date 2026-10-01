package cn.ism.mekck.item;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.math.FloatingLong;
import mekanism.common.config.MekanismConfig;
import mekanism.common.item.ItemEnergized;
import mekanism.common.item.gear.ItemAtomicDisassembler.DisassemblerMode;
import mekanism.common.util.StorageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.ToolActions;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 原子刀（由 part2 1.21.1 NeoForge 移植到 1.20.1 Forge）：
 * 使用 Mekanism 能量容器供能，支持农夫乐事刀具行为（刀具挖掘 / 收获 / 砧板处理）。
 * <p>兼容说明：编译期农夫乐事 jar 为旧版（1.19.2-1.2.3），运行时为 1.3.2——
 * 刀具动作与可切块标签均按「名称/ID」定义，不直接引用 FD 的常量，两个版本下行为一致。</p>
 */
public class ItemAtomicKnife extends ItemEnergized {

    /** 农夫乐事运行时（1.3.2）的刀具动作名（旧编译 jar 无对应常量，按名称取单例，运行时与 FD 共用同一 ToolAction）。 */
    private static final ToolAction KNIFE_DIG = ToolAction.get("knife_dig");
    private static final ToolAction KNIFE_HARVEST = ToolAction.get("knife_harvest");

    public static final Set<ToolAction> KNIFE_ACTIONS = Set.of(
            ToolActions.SHEARS_CARVE,
            ToolActions.SWORD_DIG,
            KNIFE_DIG,
            KNIFE_HARVEST
    );

    /** 农夫乐事可切方块标签（按 ID 引用，兼容编译/运行版本差异）。 */
    private static final TagKey<Block> MINEABLE_WITH_KNIFE = TagKey.create(Registries.BLOCK,
            new ResourceLocation("farmersdelight", "mineable/knife"));

    public ItemAtomicKnife(Properties properties) {
        super(MekanismConfig.gear.disassemblerChargeRate, MekanismConfig.gear.disassemblerMaxEnergy,
                properties.rarity(Rarity.RARE).setNoRepair());
    }

    @Override
    public void initializeClient(@NotNull Consumer<IClientItemExtensions> consumer) {
        // 专用服务器安全：客户端渲染逻辑全部隔离在 @OnlyIn(CLIENT) 的 RenderPropertiesAtomicKnife，
        // 本类不再持有任何客户端类符号（RenderAtomicKnife / mekanism.client.RenderPropertiesProvider 均已移除）。
        cn.ism.mekck.client.atomic_knife.RenderPropertiesAtomicKnife.get().accept(consumer);
    }

    @Override
    public boolean isCorrectToolForDrops(@NotNull BlockState state) {
        return state.is(MINEABLE_WITH_KNIFE);
    }

    @Override
    public boolean canAttackBlock(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos, Player player) {
        return !player.isCreative();
    }

    @Override
    public boolean canPerformAction(@NotNull ItemStack stack, @NotNull ToolAction action) {
        if (KNIFE_ACTIONS.contains(action)) {
            IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
            if (energyContainer != null) {
                // 用硬度 0 的潜在最低破坏能耗作为最佳猜测
                FloatingLong energyRequired = getUseEnergy(stack, 0);
                FloatingLong energyAvailable = energyContainer.getEnergy();
                return energyRequired.smallerOrEqual(energyAvailable) || !energyAvailable.divide(energyRequired).isZero();
            }
        }
        return false;
    }

    @Override
    public boolean hurtEnemy(@NotNull ItemStack stack, @NotNull LivingEntity target, @NotNull LivingEntity attacker) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer != null && !energyContainer.isEmpty()) {
            energyContainer.extract(MekanismConfig.gear.disassemblerEnergyUsageWeapon.get(), Action.EXECUTE, AutomationType.MANUAL);
        }
        return true;
    }

    @Override
    public float getDestroySpeed(@NotNull ItemStack stack, @NotNull BlockState state) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer == null) {
            return 0;
        }
        // 本工程用官方映射（gradle.properties mapping_channel=official）：BlockState#destroySpeed 在
        // BlockStateBase 里是 private 字段（parchment 下才是 public，这也是原写法移植过来的原因），
        // 公开入口只有 getDestroySpeed(BlockGetter, BlockPos)，其实现字节码就是「return this.destroySpeed;」、
        // 两个入参根本不读，故传 null/ZERO 取到的值与原字段读完全一致。
        // TODO(mekck): 若将来有补丁给该实现真的启用入参，这里会 NPE，届时改成携带 level/pos 的调用。
        FloatingLong energyRequired = getUseEnergy(stack, state.getDestroySpeed(null, BlockPos.ZERO));
        FloatingLong energyAvailable = energyContainer.extract(energyRequired, Action.SIMULATE, AutomationType.MANUAL);
        if (energyAvailable.smallerThan(energyRequired)) {
            // 电量不足：按可用比例降低挖掘速度
            return DisassemblerMode.NORMAL.getEfficiency() * energyAvailable.divide(energyRequired).floatValue();
        }
        if (state.is(MINEABLE_WITH_KNIFE)) {
            return DisassemblerMode.FAST.getEfficiency();
        }
        return DisassemblerMode.SLOW.getEfficiency();
    }

    @Override
    public boolean mineBlock(@NotNull ItemStack stack, @NotNull Level level, @NotNull BlockState state, @NotNull BlockPos pos, @NotNull LivingEntity entity) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer != null) {
            energyContainer.extract(getUseEnergy(stack, state.getDestroySpeed(level, pos)), Action.EXECUTE, AutomationType.MANUAL);
        }
        return true;
    }

    public boolean canUseOnCuttingBoard(ItemStack stack) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer == null) {
            return false;
        }
        FloatingLong energyRequired = getCuttingBoardUseEnergy(stack);
        return energyContainer.extract(energyRequired, Action.SIMULATE, AutomationType.MANUAL).greaterOrEqual(energyRequired);
    }

    public boolean useOnCuttingBoard(ItemStack stack) {
        IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
        if (energyContainer == null) {
            return false;
        }
        FloatingLong energyRequired = getCuttingBoardUseEnergy(stack);
        return energyContainer.extract(energyRequired, Action.EXECUTE, AutomationType.MANUAL).greaterOrEqual(energyRequired);
    }

    private FloatingLong getCuttingBoardUseEnergy(ItemStack stack) {
        // 砧板切割按最低硬度消耗固定电量
        return getUseEnergy(stack, 0);
    }

    private FloatingLong getUseEnergy(ItemStack stack, float hardness) {
        FloatingLong baseEnergy = getUseEnergy(stack);
        return hardness == 0 ? baseEnergy.divide(2).max(FloatingLong.ONE) : baseEnergy;
    }

    private FloatingLong getUseEnergy(ItemStack stack) {
        return MekanismConfig.gear.disassemblerEnergyUsage.get().multiply(10);
    }

    @Override
    public boolean isEnchantable(@NotNull ItemStack stack) {
        return false;
    }

    @Override
    public boolean isBookEnchantable(@NotNull ItemStack stack, @NotNull ItemStack book) {
        return false;
    }

    @Override
    public boolean canApplyAtEnchantingTable(@NotNull ItemStack stack, net.minecraft.world.item.enchantment.Enchantment enchantment) {
        return false;
    }

    @NotNull
    @Override
    public Multimap<Attribute, AttributeModifier> getAttributeModifiers(@NotNull EquipmentSlot slot, @NotNull ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            IEnergyContainer energyContainer = StorageUtils.getEnergyContainer(stack, 0);
            FloatingLong energy = energyContainer == null ? FloatingLong.ZERO : energyContainer.getEnergy();
            FloatingLong energyCost = MekanismConfig.gear.disassemblerEnergyUsageWeapon.get();
            double damage;
            if (energy.greaterOrEqual(energyCost)) {
                damage = MekanismConfig.gear.disassemblerMaxDamage.get();
            } else {
                // 电量不足：按能量比例削减伤害
                int minDamage = MekanismConfig.gear.disassemblerMinDamage.get();
                int damageDifference = MekanismConfig.gear.disassemblerMaxDamage.get() - minDamage;
                damage = minDamage + damageDifference * energy.divideToLevel(energyCost);
            }
            ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
            builder.put(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_UUID, "Atomic knife modifier", damage, Operation.ADDITION));
            builder.put(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_UUID, "Atomic knife modifier", MekanismConfig.gear.disassemblerAttackSpeed.get(), Operation.ADDITION));
            return builder.build();
        }
        return super.getAttributeModifiers(slot, stack);
    }
}
