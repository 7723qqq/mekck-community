package cn.ism.mekck.upgrade;

import cn.ism.mekck.UniversalCuttingMachine;
import cn.ism.mekck.blockentity.ChocolateCannonBlockEntity;
import cn.ism.mekck.blockentity.IceFactoryBlockEntity;
import cn.ism.mekck.blockentity.IceMakerBlockEntity;
import cn.ism.mekck.blockentity.PlantingCuttingStationBlockEntity;
import cn.ism.mekck.blockentity.SkeweringMachineBlockEntity;
import cn.ism.mekck.blockentity.SmartCookingPotBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerLevel;
import cn.ism.mekck.UniversalCuttingMachine;

/**
 * 潜行右键机器安装升级的全局处理器。
 * <p>
 * 1.20.1 原版在玩家潜行时只有主/副手物品全部覆写 {@code doesSneakBypassUse} 才会调用
 * {@code Block.use}（{@code ServerPlayerGameMode.useItemOn} 字节码 172-216 实锤），升级物品
 * 不满足 → {@code use()} 内的潜行安装分支是死代码（文档 12.5 的机制此前实际无效）。
 * Forge 的 {@code RightClickBlock} 事件在原版跳过逻辑之前触发，此处统一拦截：
 * 潜行 + 手持升级模块 + 命中本模组机器 → 取消事件（不打开 GUI）并调用各机器自己的
 * {@code addUpgradesFromHand}（冷萃/费列罗等专属升级由各机器内部路由，不支持的升级返回 0 并提示）。
 * </p>
 * <p>
 * <b>已迁到 Mek 原生 tile 的工厂家族不在分发链里</b>（研磨 / 种植切配 / 烧烤 / 切菜 / 烹饪 /
 * 穿串工厂，阶段 2~3）：它们的升级槽由 {@code TileComponentUpgrade} 提供，Mek 升级 tab
 * 直接可用，而 Mek 的 {@code BlockTile} 走的是 {@code AttributeGui} 而非本类这条 Forge 事件捷径。
 * 单机切菜机（{@code UniversalCuttingMachineTile}）是唯一例外：它保留了一条潜行安装分支，
 * 但走的是类型感知路由（物品进组件升级槽，20 tick 正常安装），不是直接改组件计数。
 * </p>
 * <p>
 * <b>消耗口径（C1）</b>：安装成功（{@code added > 0}）时由本处理器 {@code held.shrink(added)}
 * 并写回手持槽 —— 这是潜行安装的<b>唯一活路径</b>；9 个方块类 {@code use()} 里的同款 shrink
 * 永不执行，若在此漏掉消耗，升级物品即可无限复制。
 * </p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class UpgradeInstallHandler {

    private UpgradeInstallHandler() {
    }

    /** 潜行 + 空手 右键：切换 ME 终端下单总开关（关闭后该机器的配方不再出现在 ME 终端）。 */
    private static void toggleMeOrder(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide) return;
        BlockEntity be = level.getBlockEntity(event.getPos());
        if (!(be instanceof cn.ism.mekck.ae2.INetworkPullable pullable)) return;
        boolean now = !pullable.isMeOrderEnabled();
        pullable.setMeOrderEnabled(now);
        if (event.getEntity() instanceof ServerPlayer player) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    now ? "ME 终端下单：已开启" : "ME 终端下单：已关闭（终端不再显示本机配方）"), true);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty()) {
            // 陈酿机优先：存有果汁时，潜行 + 空手右键先清空果汁液位池（复刻 vinery 发酵桶潜行清桶），
            // 不被「切换 ME 终端下单」顶掉；无果汁时才走 ME 下单开关切换。
            BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
            if (be instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm
                    && sm.getMachineKind() == cn.ism.mekck.MachineKind.WINERY
                    && sm.getJuiceLevel() > 0) {
                if (!event.getLevel().isClientSide) {
                    sm.clearJuicePool();
                    if (event.getEntity() instanceof ServerPlayer player) {
                        player.displayClientMessage(Component.translatable("message.mekck.juice_cleared"), true);
                    }
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                }
                return;
            }
            // 潜行 + 空手 右键 = 切换该机器的 ME 终端下单总开关
            toggleMeOrder(event);
            return;
        }
        // 通用候选判断：Mekanism/Extras 升级 + 冷萃 + 费列罗；机器自身决定是否接受
        boolean upgradeLike = UpgradeHelper.isUpgrade(held)
                || cn.ism.mekck.item.ColdBrewUpgradeItem.getTier(held) != null
                || cn.ism.mekck.item.FerreroUpgradeItem.getTier(held) != null;
        if (!upgradeLike) {
            return;
        }

        Level level = event.getLevel();
        BlockEntity be = level.getBlockEntity(event.getPos());
        if (be == null) {
            return;
        }

        int added;
        if (be instanceof IceMakerBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof IceFactoryBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof ChocolateCannonBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof cn.ism.mekck.machine.cutting.UniversalCuttingMachineTile m) {
            // 类型感知：路由进组件升级槽（20 tick 正常安装路径），类型不匹配/已满返回 0 → 不消耗。
            // 旧写法直接 addUpgrades(SPEED, 1)：签名里没有 ItemStack，任意 upgradeLike 物品
            // 都会被装成速度卡（C1 补 shrink 后 = 错物品被吃掉换速度卡）。
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof PlantingCuttingStationBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        // 电力研磨机分支在阶段 3 样板迁移中删除：新 tile 的升级走 Mek 的
        // TileComponentUpgrade（与切菜/烧烤/各工厂家族同一条路），不再经本分发器。
        // 坚果爆炒机分支在 2026-10-06 迁移中同理删除（NutRoasterTile 同样是 Mek 原生 tile）。
        } else if (be instanceof SmartCookingPotBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof SkeweringMachineBlockEntity m) {
            added = m.addUpgradesFromHand(held);
        } else if (be instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity m) {
            // §F25 修法 A：SimpleMachine 家族（陈酿/榨汁/发酵/凝乳…凡借祖本注册的基础机器）
            // 此前不在分发链里，落末尾 else 既不安装也不取消事件（「装了个寂寞、GUI 也不开」）。
            // 方法已存在（UpgradeHelper.install 只认速度/能量/创造槽，非本家族升级天然不误装）。
            added = m.addUpgradesFromHand(held);
        } else {
            return;
        }

        // 命中本模组机器：取消原版交互（不打开 GUI），两侧都取消避免客户端预测
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (level instanceof ServerLevel && event.getEntity() instanceof ServerPlayer serverPlayer) {
            if (added > 0) {
                // C1：安装成功必须消耗手持物品。本处理器是潜行安装的唯一活路径 ——
                // 9 个方块类 use() 里的同款 shrink 因原版潜行跳过而永不执行（见类注释），
                // 漏掉这里 = 升级物品（含创造/冷萃/费列罗）可无限复制。
                String upgradeName = held.getHoverName().getString();
                held.shrink(added);
                serverPlayer.setItemInHand(event.getHand(), held);
                serverPlayer.displayClientMessage(Component.literal("§a已安装升级：§f" + upgradeName), true);
            } else if (be instanceof cn.ism.mekck.blockentity.SimpleMachineBlockEntity sm
                    && sm.getMachineKind() == cn.ism.mekck.MachineKind.WINERY
                    && sm.getJuiceLevel() > 0) {
                // §F25 修法 B（陈酿机专属三级瀑布）：装升级未成 → 顺带清空液位池（用户拍板顺序：
                // 装升级最优先 → 未装/失败再清液位 → 液位已空才轮到 ME 下单切换（空手路径，不回归）。
                sm.clearJuicePool();
                serverPlayer.displayClientMessage(Component.translatable("message.mekck.juice_cleared"), true);
            } else {
                serverPlayer.displayClientMessage(Component.literal("§c无法安装升级：对应槽位已满或本机器不支持该升级"), true);
            }
        }
    }
}
