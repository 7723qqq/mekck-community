package cn.ism.mekck.util;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootDataResolver;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 失温 / 永冻效果的事件处理（FORGE 总线）：
 * <ul>
 *   <li>永冻：受到任何伤害时改为受到 2147483647 点「冰块同属性」的冰冻伤害（damageSources().freeze()，
 *       与急冻制冰机冰块一致；创造模式玩家除外）；</li>
 *   <li>战利品最大化：失温或永冻状态的实体死亡时，战利品按 MaxLootRandom 重掷——随机概率必然命中、数量取上限。</li>
 * </ul>
 * <p>1.20.1 的 {@link LootContext} 构造器为包私有、raw 掷骰方法为私有，
 * 这里用「按参数签名反射」定位（字段/方法名会被 reobf 改名，参数类型不会），
 * 失败时回退到公开 API 的正常掷骰并打日志。</p>
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FreezeEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("MekCK");

    /** 永冻的冰冻伤害数值（int 上限），受击时替换为这个数值的冰块同属性冰冻伤害。 */
    public static final float ETERNAL_FREEZE_DAMAGE = 2147483647.0F;

    /**
     * 重入哨兵 —— <b>正在被本类施加永冻伤害的那只实体</b>。
     *
     * <h3>为什么不能用「比较伤害数值」来防递归</h3>
     * 原实现靠
     * <pre>{@code if (event.getSource().is(DamageTypes.FREEZE) && event.getAmount() >= ETERNAL_FREEZE_DAMAGE) return;}</pre>
     * 来识别「这一发是我自己刚打出去的」。这个判据<b>在有抗性的实体上恒假</b>：
     * {@code LivingEntity.hurt} 的字节码顺序是
     * {@code onLivingHurt} → {@code getDamageAfterArmorAbsorb} → {@code getDamageAfterMagicAbsorb}
     * → <b>然后</b>才是 {@code ForgeHooks.onLivingDamage}，
     * 所以 {@code LivingDamageEvent.getAmount()} 拿到的是<b>减免之后</b>的值。
     * 而 {@code minecraft:freeze} 只在 {@code bypasses_armor} 里（护甲不减），
     * <b>不在</b> {@code bypasses_resistance} 里 ⇒ 抗性照减：
     * 抗性 1 即得 1717986917.6 {@code < 2147483647} ⇒ 守卫失效
     * ⇒ {@code cancel + 重入 hurt()} 无限递归 ⇒ {@code StackOverflowError} ⇒ <b>服务器崩溃</b>。
     *
     * <p>恶魂杀手 / 循道者 / 恼鬼 / 幻术师默认带抗性，大型整合包里任何带
     * {@code ToughAttribute} 或平值减伤的模组生物都算 ⇒ 这不是理论边界。</p>
     *
     * <h3>为什么按实体而不是按线程做哨兵</h3>
     * 哨兵里存的是<b>实体本身</b>，不是 boolean：反射伤害（{@code thorns} 之类）可能把
     * 永冻伤害弹到<b>另一只</b>实体上，用 boolean 会把那只实体本该吃的永冻伤害也一并吞掉。
     * 存实体就只放行「我刚打过的那一只」，别的实体照常吃满 21 亿。
     *
     * <p>用 {@link ThreadLocal} 而非实例字段：{@code LivingDamageEvent} 在服务器主线程派发，
     * 但本类也可能在模组自建的线程里被调用；主线程的哨兵状态不该泄漏到那些线程上。</p>
     */
    private static final ThreadLocal<LivingEntity> REPLACING_DAMAGE_FOR = new ThreadLocal<>();

    private FreezeEvents() {
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        // 重入：这一发就是本类在上一次调用里施加给同一只实体的永冻伤害，放行，否则无限递归。
        if (entity == REPLACING_DAMAGE_FOR.get()) return;
        if (!entity.hasEffect(UniversalCuttingMachine.ETERNAL_FREEZE_EFFECT.get())) return;
        if (entity instanceof Player player && player.isCreative()) return;
        // 永冻：取消原伤害，改为 2147483647 点冰块同属性（freeze）冰冻伤害
        event.setCanceled(true);
        entity.invulnerableTime = 0;
        REPLACING_DAMAGE_FOR.set(entity);
        try {
            entity.hurt(entity.damageSources().freeze(), ETERNAL_FREEZE_DAMAGE);
        } finally {
            REPLACING_DAMAGE_FOR.remove();
        }
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide) return;
        // 战利品最大化：失温与永冻实体击杀时都生效（永冻通常由失温叠满附加）
        boolean maxLoot = entity.hasEffect(UniversalCuttingMachine.ETERNAL_FREEZE_EFFECT.get())
                || entity.hasEffect(UniversalCuttingMachine.HYPOTHERMIA_EFFECT.get());
        if (!maxLoot) return;
        if (!(entity.level() instanceof ServerLevel serverLevel)) return;

        ResourceLocation tableId = entity.getLootTable();
        LootTable table = serverLevel.getServer().getLootData().getLootTable(tableId);

        // ⚠️ 查不到战利品表时**原样保留原版与其它模组的掉落**，绝不清空。
        // 原实现是「先 clear() 再重掷」：而 getLootTable 对未注册的 id 返回 LootTable.EMPTY，
        // 其 getRandomItems 恒返回空列表 ⇒ 触发门槛极低（失温只要 1 级就满足，而每颗冰块
        // 命中都加 1 级）却让「这只生物什么都不掉」，且没有任何日志。
        // 任何没有注册战利品表的 LivingEntity（大量模组的 boss / 附件实体）都会踩中。
        if (table == LootTable.EMPTY) {
            return;
        }

        // 与原版 dropFromLootTable 相同的 LootParams 构建（killedByPlayer 时补充伤害上下文）
        LootParams.Builder builder = new LootParams.Builder(serverLevel);
        builder.withParameter(LootContextParams.THIS_ENTITY, entity);
        builder.withParameter(LootContextParams.ORIGIN, entity.position());
        if (event.isRecentlyHit()) {
            builder.withParameter(LootContextParams.DAMAGE_SOURCE, event.getSource());
            Player lastHurtByPlayer = getLastHurtByPlayer(entity);
            if (lastHurtByPlayer != null) {
                builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, lastHurtByPlayer);
            }
            if (entity.getKillCredit() != null) {
                builder.withParameter(LootContextParams.KILLER_ENTITY, entity.getKillCredit());
            }
        }
        LootParams params = builder.create(LootContextParamSets.ENTITY);

        List<ItemStack> stacks = new ArrayList<>();
        LootContext maxContext = buildMaxLootContext(params, serverLevel.getServer().getLootData(), tableId);
        if (maxContext != null) {
            rollRawWithMaxRandom(table, maxContext, params, stacks::add);
        } else {
            // 回退：公开 API 正常掷骰（无法保证 100%/上限）
            stacks.addAll(table.getRandomItems(params));
        }

        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            ItemEntity item = new ItemEntity(entity.level(),
                    entity.getX(), entity.getY() + entity.getBbHeight() / 2.0D, entity.getZ(), stack);
            item.setDefaultPickUpDelay();
            // **追加**而不是「先 clear 再填」：LivingDropsEvent 是可改事件，
            // 别的模组（优先级高于本类的 NORMAL）已经往里加的东西不该被本类无声丢弃。
            // 原版掉落也一并保留 —— 本功能是「最大化」而不是「替换」。
            event.getDrops().add(item);
        }
    }

    // ── 反射句柄缓存 ──
    // 原先每次死亡都要遍历 LootContext 的全部构造器 / LootTable 的全部方法 / LivingEntity 的全部字段；
    // 失温农场这类高击杀场景下是服务器线程上的固定开销。句柄只解析一次，之后直接复用。
    private static java.lang.reflect.Field lastHurtByPlayerField;
    private static boolean lastHurtByPlayerFieldResolved;
    private static Constructor<LootContext> maxLootContextCtor;
    private static boolean maxLootContextCtorResolved;
    private static Method rawRollMethod;
    private static boolean rawRollMethodResolved;

    /** LivingEntity.lastHurtByPlayer 为 protected 字段（无公开 getter），按字段类型反射读取；失败返回 null。 */
    @javax.annotation.Nullable
    private static Player getLastHurtByPlayer(LivingEntity entity) {
        if (!lastHurtByPlayerFieldResolved) {
            lastHurtByPlayerFieldResolved = true;
            try {
                for (java.lang.reflect.Field field : LivingEntity.class.getDeclaredFields()) {
                    if (field.getType() == Player.class) {
                        field.setAccessible(true);
                        lastHurtByPlayerField = field;
                        break;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[mekck] 定位 lastHurtByPlayer 字段失败：{}", t.toString());
            }
        }
        if (lastHurtByPlayerField == null) return null;
        try {
            Object value = lastHurtByPlayerField.get(entity);
            return value instanceof Player player ? player : null;
        } catch (Throwable t) {
            LOGGER.warn("[mekck] 读取 lastHurtByPlayer 失败：{}", t.toString());
            return null;
        }
    }

    /** 按签名反射构造携带 MaxLootRandom 的 LootContext；失败返回 null。 */
    @javax.annotation.Nullable
    @SuppressWarnings("unchecked")
    private static LootContext buildMaxLootContext(LootParams params, LootDataResolver resolver, ResourceLocation tableId) {
        if (!maxLootContextCtorResolved) {
            maxLootContextCtorResolved = true;
            try {
                for (Constructor<?> ctor : LootContext.class.getDeclaredConstructors()) {
                    Class<?>[] types = ctor.getParameterTypes();
                    if (types.length == 3 && types[0] == LootParams.class
                            && types[1] == net.minecraft.util.RandomSource.class
                            && types[2] == LootDataResolver.class) {
                        ctor.setAccessible(true);
                        maxLootContextCtor = (Constructor<LootContext>) ctor;
                        break;
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("[mekck] 永冻 LootContext 构造器定位失败，回退正常掷骰：{}", t.toString());
            }
        }
        if (maxLootContextCtor == null) return null;
        try {
            return maxLootContextCtor.newInstance(params, MaxLootRandom.INSTANCE, resolver);
        } catch (Throwable t) {
            LOGGER.warn("[mekck] 永冻 LootContext 构造失败，回退正常掷骰：{}", t.toString());
            return null;
        }
    }

    /** 用 MaxLootRandom 直滚战利品表；失败时回退公开 API 正常掷骰。 */
    private static void rollRawWithMaxRandom(LootTable table, LootContext context, LootParams fallbackParams, Consumer<ItemStack> consumer) {
        if (!rawRollMethodResolved) {
            rawRollMethodResolved = true;
            try {
                // ⚠️ 必须用**返回类型**把两个签名相同的方法区分开，不能只看参数。
                // 擦除之后 `getRandomItems(LootContext, Consumer)` 与
                // `processSelectedStacks(LootContext, Consumer)` 的参数类型完全一致，
                // 而 `getDeclaredMethods()` 的返回顺序**不由 JVM 规范保证**。
                // 原实现只比参数，于是 HotSpot 按声明序恰好命中前者才一直没事；
                // 一旦绑到 processSelectedStacks，「最大随机」就退化成「只跑 loot functions」，
                // 静默少掉整棵池子 —— 而且不报任何错。
                for (Method m : LootTable.class.getDeclaredMethods()) {
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 2 && types[0] == LootContext.class
                            && types[1] == Consumer.class && m.getReturnType() == void.class) {
                        m.setAccessible(true);
                        rawRollMethod = m;
                        break;
                    }
                }
                if (rawRollMethod != null) {
                    LOGGER.info("[mekck] 永冻战利品 raw 掷骰方法定位为 {}",
                            rawRollMethod.getName());
                } else {
                    LOGGER.warn("[mekck] 永冻战利品 raw 掷骰方法定位失败，回退正常掷骰");
                }
            } catch (Throwable t) {
                LOGGER.warn("[mekck] 永冻战利品 raw 掷骰方法定位失败，回退正常掷骰：{}", t.toString());
            }
        }
        if (rawRollMethod != null) {
            try {
                rawRollMethod.invoke(table, context, consumer);
                return;
            } catch (Throwable t) {
                LOGGER.warn("[mekck] 永冻战利品 raw 掷骰失败，回退正常掷骰：{}", t.toString());
                rawRollMethod = null;
            }
        }
        for (ItemStack s : table.getRandomItems(fallbackParams)) {
            consumer.accept(s);
        }
    }
}
