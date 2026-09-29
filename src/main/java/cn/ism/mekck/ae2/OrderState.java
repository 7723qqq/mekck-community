package cn.ism.mekck.ae2;

/**
 * 机器订单状态的轻量快照，供 AE2 集成统一判定「机器是否还在加工」。
 *
 * @param hasRecipe 是否仍有订单配方
 * @param remaining 剩余数量
 */
public record OrderState(boolean hasRecipe, int remaining) {

    /** 订单是否已完成（无配方且无剩余数量）。 */
    public boolean done() {
        return !hasRecipe && remaining <= 0;
    }
}
