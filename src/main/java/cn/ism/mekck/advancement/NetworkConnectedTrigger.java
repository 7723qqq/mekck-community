package cn.ism.mekck.advancement;

import com.google.gson.JsonObject;
import net.minecraft.advancements.critereon.AbstractCriterionTriggerInstance;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.DeserializationContext;
import net.minecraft.advancements.critereon.SerializationContext;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;

/**
 * 自定义进度触发器：mekck 机器成功接入 ME 网络（方块旁有 ME 线缆且网格激活）。
 * 由 {@code MekckAe2} 在首次建连时调用 {@link #trigger(ServerPlayer)}。
 */
public class NetworkConnectedTrigger extends SimpleCriterionTrigger<NetworkConnectedTrigger.Instance> {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("mekck", "network_connected");

    private static final NetworkConnectedTrigger INSTANCE = new NetworkConnectedTrigger();

    public static NetworkConnectedTrigger get() {
        return INSTANCE;
    }

    private NetworkConnectedTrigger() {
    }

    @Override
    @NotNull
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    @NotNull
    protected Instance createInstance(JsonObject json, ContextAwarePredicate player, DeserializationContext context) {
        return new Instance(player);
    }

    /** 触发：机器接入网络时，对指定玩家授予进度。 */
    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }

    public static class Instance extends AbstractCriterionTriggerInstance {
        public Instance(ContextAwarePredicate player) {
            super(ID, player);
        }

        @Override
        @NotNull
        public JsonObject serializeToJson(SerializationContext context) {
            return super.serializeToJson(context);
        }
    }
}
