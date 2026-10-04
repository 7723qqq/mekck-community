package cn.ism.mekck.blockentity;

import net.minecraft.world.level.Level;

/**
 * 中央厨房的<b>存储浏览器增量同步</b>触发端：每 tick 给正开着本厨房界面的玩家补推一页快照。
 *
 * <h3>为什么要从 {@code CentralKitchenBlockEntity} 里独立出来</h3>
 * 这十来行关心的是「谁在看这个界面」，而不是「这台机器在做什么」——它是
 * {@code serverTick} 末尾对 UI 的一个推送动作。真正的节流与快照落地仍在
 * {@code CentralKitchenMenu#tickStorageSync}/{@code pushStorageSync}（版本号比较 + 时间窗），
 * 本类只负责「找出开界面的玩家并通知」这一环。拆出后，S2C 链路的两端各归其位：
 * 触发在服务端 BE，落地在菜单。
 *
 * <p>本类<b>不持有任何自己的状态</b>：只从 {@code be} 取 {@code getLevel()}，
 * 并以 {@code be} 本身作为「界面属于哪台机器」的身份判据（{@code menu.getMachine() == be}），
 * 与拆分前 {@code == this} 完全等价。</p>
 */
public final class CentralKitchenStorageSync {

    private final CentralKitchenBlockEntity be;

    public CentralKitchenStorageSync(CentralKitchenBlockEntity be) {
        this.be = be;
    }

    /**
     * 给所有正开着本厨房界面的玩家补推一页存储浏览器快照。
     *
     * <p>不缓存玩家列表：中央厨房不是高频方块，遍历 {@code level.players()} 的成本
     * 远低于维护一份「谁开着哪个界面」的注册表（后者要在菜单关闭时可靠注销，
     * 漏注销就是给已关界面的人发包）。</p>
     */
    void syncOpenStorageBrowsers() {
        Level level = be.getLevel();
        if (level == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return;
        }
        for (var player : serverLevel.players()) {
            if (player.containerMenu instanceof cn.ism.mekck.menu.CentralKitchenMenu menu
                    && menu.getMachine() == be) {
                menu.tickStorageSync();
            }
        }
    }
}
