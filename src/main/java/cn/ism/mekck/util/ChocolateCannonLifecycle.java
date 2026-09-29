package cn.ism.mekck.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 巧克力大炮预留的确定性生命周期清理。
 * <p>
 * 飞行中伤害预留注册表按“世界实例”隔离。当某世界卸载或服务器停止（切换存档）时，
 * 那些世界中飞行中的弹药已不再有意义，若只靠弱引用等待下次 {@link ChocolateCannonReservations#pruneExpired}
 * 才清理，会存在一段“预留仍被其它逻辑读到但已无意义”的窗口。此监听器在 Forge 事件中显式触发
 * {@link ChocolateCannonReservations#clearWorld}/{@link ChocolateCannonReservations#clearAll}，
 * 保证世界卸载/服务器停止即确定性清理对应世界的预留，且不影响其它仍有效世界实例。
 * </p>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChocolateCannonLifecycle {

    private ChocolateCannonLifecycle() {
    }

    /** 世界卸载：清理该世界实例的全部预留（不影响其它世界）。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        net.minecraft.world.level.LevelAccessor accessor = event.getLevel();
        if (accessor instanceof ServerLevel serverLevel) {
            ChocolateCannonReservations.clearWorld(serverLevel);
        }
    }

    /** 服务器停止 / 切换存档：清理全部预留。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ChocolateCannonReservations.clearAll();
    }
}
