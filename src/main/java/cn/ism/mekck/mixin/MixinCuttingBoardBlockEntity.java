package cn.ism.mekck.mixin;

import cn.ism.mekck.item.ItemAtomicKnife;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vectorwing.farmersdelight.common.block.CuttingBoardBlock;
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SyncedBlockEntity;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.registry.ModAdvancements;
import vectorwing.farmersdelight.common.utility.ItemUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 农夫乐事砧板兼容（由 part2 1.21.1 移植到 1.20.1）：
 * 仅接管原子刀的砧板处理——电量充足时执行砧板配方并消耗能量，电量不足时提示自定义消息；
 * 其它工具继续走农夫乐事原逻辑。
 * <p>兼容说明：运行时 FD 版本可能低于编译期 jar（已将依赖下限降到 1.2.7）；FD 1.3.x 新增的成员
 * （三参 rollResults、spawnCuttingParticles）在 1.2.7 不存在，直接 @Shadow/硬引用会导致 mixin 崩溃
 * ⇒ 一律改反射按运行时签名调用，找不到即静默回退（掷骰回退旧签名，切割粒子缺失无害）。</p>
 */
@Mixin(value = CuttingBoardBlockEntity.class, remap = false)
public abstract class MixinCuttingBoardBlockEntity extends SyncedBlockEntity implements Clearable {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK");

    @Shadow
    @Final
    private ItemStackHandler inventory;

    @Shadow
    private boolean isItemCarvingBoard;

    public MixinCuttingBoardBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState state) {
        super(blockEntityType, pos, state);
    }

    @Inject(method = "processStoredItemUsingTool", at = @At("HEAD"), cancellable = true)
    private void mekck$processAtomicKnife(ItemStack toolStack, Player player, CallbackInfoReturnable<Boolean> cir) {
        // 仅接管原子刀的砧板处理，其它工具继续走农夫乐事原逻辑。
        if (!(toolStack.getItem() instanceof ItemAtomicKnife atomicKnife)) {
            return;
        }
        if (level == null || isItemCarvingBoard) {
            cir.setReturnValue(false);
            return;
        }
        // 原子刀电量不足时优先提示自定义消息，避免触发农夫乐事的无效工具提示。
        if (!atomicKnife.canUseOnCuttingBoard(toolStack)) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.mekck.atomic_knife.not_enough_energy"), true);
            }
            cir.setReturnValue(false);
            return;
        }

        Optional<CuttingBoardRecipe> matchingRecipe = getMatchingRecipe(new RecipeWrapper(inventory), toolStack, player);
        if (matchingRecipe.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }
        // 实际消耗电量失败说明预检查和消耗状态不一致，需要记录错误并阻止切割。
        if (!atomicKnife.useOnCuttingBoard(toolStack)) {
            LOGGER.error("Atomic Knife passed cutting board energy precheck but failed to consume energy.");
            if (player != null) {
                player.displayClientMessage(Component.translatable("message.mekck.atomic_knife.not_enough_energy"), true);
            }
            cir.setReturnValue(false);
            return;
        }

        CuttingBoardRecipe recipe = matchingRecipe.get();
        // 有些模组让 mek 的工具可以附魔，添加时运判断是必要的
        int fortuneLevel = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, toolStack);
        List<ItemStack> results = rollRecipeResults(recipe, fortuneLevel);
        for (ItemStack resultStack : results) {
            Direction direction = getBlockState().getValue(CuttingBoardBlock.FACING).getCounterClockWise();
            ItemUtils.spawnItemEntity(level, resultStack.copy(),
                    worldPosition.getX() + 0.5 + direction.getStepX() * 0.2,
                    worldPosition.getY() + 0.2,
                    worldPosition.getZ() + 0.5 + direction.getStepZ() * 0.2,
                    direction.getStepX() * 0.2F, 0.0F, direction.getStepZ() * 0.2F);
        }

        // 存在玩家时统计本次原子刀使用次数。
        if (player != null) {
            player.awardStat(Stats.ITEM_USED.get(toolStack.getItem()));
        }
        // 服务端世界负责生成切割粒子，避免客户端重复执行（FD 1.3.x 运行时方法；1.2.7 无 ⇒ 反射 + 静默回退）。
        if (level instanceof ServerLevel serverLevel) {
            spawnCuttingParticlesReflect(serverLevel, getBlockPos(), getStoredItem());
        }
        playProcessingSound(recipe.getSoundEventID(), toolStack, getStoredItem());
        inventory.extractItem(0, 1, false);
        // 服务端玩家触发进度并刷新砧板剩余物品提示。
        if (player instanceof ServerPlayer serverPlayer) {
            ModAdvancements.CUTTING_BOARD.trigger(serverPlayer);
            if (!getStoredItem().isEmpty()) {
                player.displayClientMessage(Component.translatable("farmersdelight.block.cutting_board.remaining_items", getStoredItem().getCount()), true);
            } else {
                player.displayClientMessage(Component.empty(), true);
            }
        }

        cir.setReturnValue(true);
    }

    /**
     * 掷砧板配方产物：优先按 FD 1.3.2 的三参签名（RandomSource, int, RecipeWrapper）反射调用，
     * 旧版 FD 仅有两参签名时回退（无 RecipeWrapper 参数）。
     */
    @SuppressWarnings("unchecked")
    private List<ItemStack> rollRecipeResults(CuttingBoardRecipe recipe, int fortuneLevel) {
        RecipeWrapper wrapper = new RecipeWrapper(inventory);
        try {
            java.lang.reflect.Method method = recipe.getClass().getMethod(
                    "rollResults", net.minecraft.util.RandomSource.class, int.class, RecipeWrapper.class);
            return (List<ItemStack>) method.invoke(recipe, level.random, fortuneLevel, wrapper);
        } catch (Throwable ignored) {
            // 旧版 FD：无三参签名
        }
        try {
            java.lang.reflect.Method method = recipe.getClass().getMethod(
                    "rollResults", net.minecraft.util.RandomSource.class, int.class);
            return (List<ItemStack>) method.invoke(recipe, level.random, fortuneLevel);
        } catch (Throwable t) {
            LOGGER.error("Failed to roll cutting board recipe results: {}", t.toString());
            return new ArrayList<>();
        }
    }

    @Shadow
    protected abstract Optional<CuttingBoardRecipe> getMatchingRecipe(RecipeWrapper recipeWrapper, ItemStack toolStack, Player player);

    /**
     * FD 1.3.x 的 spawnCuttingParticles(ServerLevel, BlockPos, ItemStack) 在 1.2.7 不存在，
     * 直接 @Shadow 绑定会让 mixin 崩溃 ⇒ 改反射按运行时签名调用；找不到方法时静默跳过
     * （切割粒子是装饰，缺失不影响切割与产物，仅为向下兼容 1.2.7）。
     */
    private void spawnCuttingParticlesReflect(ServerLevel serverLevel, BlockPos pos, ItemStack stack) {
        try {
            java.lang.reflect.Method method = this.getClass().getMethod(
                    "spawnCuttingParticles", ServerLevel.class, BlockPos.class, ItemStack.class);
            method.invoke(this, serverLevel, pos, stack);
        } catch (Throwable ignored) {
            // 旧版 FD（1.2.7）无此方法：不出切割粒子，功能不受影响。
        }
    }

    @Shadow
    public abstract void playProcessingSound(String soundEventID, ItemStack tool, ItemStack boardItem);

    @Shadow
    public abstract ItemStack getStoredItem();
}
