package cn.ism.mekck.event;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 巧克力标签自动扫描：游戏启动 / 数据包重载（{@link TagsUpdatedEvent}）时，
 * 扫描全部注册名路径包含 "chocolate" 的物品，动态并入 {@code mekck:mekck_chocolate} 物品标签。
 * <p>
 * 实现方式：读取 {@link BuiltInRegistries#ITEM} 当前全部标签构建映射，把扫描到的物品 Holder
 * 追加进 mekck_chocolate 后调用 {@code bindTags} 全量重绑——全部为 vanilla 公开 API
 * （无反射，reobf 后生产环境可用）。费列罗配方 extra 的标签匹配随即生效，
 * 客户端同步标签更新时同样执行，保证 JEI 标签视图一致。
 * </p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChocolateTagScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK");

    /** 巧克力标签（费列罗配方的 extra 输入）。 */
    public static final TagKey<Item> CHOCOLATE_TAG =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(UniversalCuttingMachine.MOD_ID, "mekck_chocolate"));

    private ChocolateTagScanner() {
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        rebindChocolateTag();
    }

    /** 扫描注册名包含 chocolate 的物品并并入 mekck_chocolate 标签（bindTags 全量重绑）。 */
    private static void rebindChocolateTag() {
        try {
            Map<TagKey<Item>, List<Holder<Item>>> tagMap = new HashMap<>();
            BuiltInRegistries.ITEM.getTags()
                    .forEach(pair -> tagMap.put(pair.getFirst(), pair.getSecond().stream().toList()));
            List<Holder<Item>> chocolates = new ArrayList<>(tagMap.getOrDefault(CHOCOLATE_TAG, List.of()));
            int added = 0;
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null || !id.getPath().contains("chocolate")) {
                    continue;
                }
                Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
                if (!chocolates.contains(holder)) {
                    chocolates.add(holder);
                    added++;
                }
            }
            tagMap.put(CHOCOLATE_TAG, chocolates);
            BuiltInRegistries.ITEM.bindTags(tagMap);
            if (added > 0) {
                LOGGER.info("[mekck-chocolate] 已将 {} 个注册名包含 chocolate 的物品并入标签 mekck:mekck_chocolate", added);
            }
        } catch (Exception e) {
            LOGGER.error("[mekck-chocolate] 扫描 chocolate 物品并更新标签失败", e);
        }
    }
}
