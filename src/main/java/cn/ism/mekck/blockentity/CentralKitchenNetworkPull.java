package cn.ism.mekck.blockentity;

/**
 * 中央厨房的 <b>AE2 网络拉料规格</b>子系统：算出「当前订单任务链所需的叶子材料」，
 * 交给 ME 网络按规格补料。
 *
 * <h3>为什么要从 {@code CentralKitchenBlockEntity} 里独立出来</h3>
 * 它与 BE 的其余部分关注点不同：BE 管「这台机器现在有什么状态、该做什么」，
 * 这里管「ME 端要按什么规格往这台机器里补料」。{@code getNetworkPullInputs()} 是
 * {@code INetworkPullable} 的 {@code @Override}，必须留在 BE 上（覆写不搬），
 * 由它把整个方法体委托给本类。
 *
 * <p>本类<b>不持有任何自己的状态</b>：订单列表从 {@code be.getOrders()} 取
 * （返回的就是 BE 里那份 {@code orders} 的同一个引用），因此搬运不改变任何读写时序。</p>
 */
public final class CentralKitchenNetworkPull {

    private final CentralKitchenBlockEntity be;

    public CentralKitchenNetworkPull(CentralKitchenBlockEntity be) {
        this.be = be;
    }

    /**
     * 网络拉料目标：当前**订单任务链**所需的叶子材料。
     * 没有订单时返回空（中央厨房默认为下单驱动，不主动从网络补料）。
     */
    java.util.List<cn.ism.mekck.ae2.AE2InputSpec> networkPullInputs() {
        java.util.List<cn.ism.mekck.ae2.AE2InputSpec> specs = new java.util.ArrayList<>();
        for (var order : be.getOrders()) {
            var step = order.currentStep();
            if (step == null) continue;
            for (var in : step.inputs) {
                if (in.isEmpty()) continue;
                specs.add(new cn.ism.mekck.ae2.AE2InputSpec(
                        net.minecraft.world.item.crafting.Ingredient.of(in), in.getCount()));
            }
        }
        return specs;
    }
}
