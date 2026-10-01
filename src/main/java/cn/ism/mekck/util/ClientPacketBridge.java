package cn.ism.mekck.util;

import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import cn.ism.mekck.compat.GuideMECompat;

/**
 * S2C 包的客户端落地门面 —— <b>本类不持有任何 {@code net.minecraft.client.* 符号</b>。
 *
 * <h3>为什么需要它</h3>
 * 本模组的网络包在 {@code FMLCommonSetupEvent}（<b>客户端与专用服务端都触发</b>）里统一注册。
 * 若某个包的 {@code handle} 方法体里直接写 {@code Minecraft.getInstance()} 或引用
 * {@code cn.ism.mekck.client.*}，那个类就会同时出现在<b>双端都要链接</b>的注册路径上 ——
 * 而专用服务端跑在 vanilla server jar 上，classpath 里根本没有 {@code net.minecraft.client.*}。
 *
 * <p>第三轮审查实测到两处漏网：{@code NetworkRecipeListPacket} 与
 * {@code NetworkMissingPacket}。二者都只是「拿到当前 Screen、往订单面板塞数据」，
 * 却在 {@code handle} 里直接摸了客户端类。全仓 {@code DistExecutor} 只出现 1 次，
 * 而且还是一条未使用的 import —— 护栏被规划过又丢了。</p>
 *
 * <h3>为什么用反射而不是 {@code DistExecutor.unsafeRunWhenOn}</h3>
 * {@code unsafeRunWhenOn(Dist, Supplier<Runnable>)} 的实参本身是一个合成 lambda，
 * 而<b>合成方法仍然长在双端都要链接的那个类里</b>；它引用客户端实现类，
 * 于是那条引用照样进了字节码。与其依赖「验证器会不会急切解析」这种实现细节，
 * 不如让 common 侧<b>完全不产生那条符号引用</b>。
 *
 * <p>这与本项目既有的约定一致（{@code util/GuideMECompat} + {@code client/GuideMECompatImpl}），
 * 也是 {@code item/ItemAtomicKnife.java:67} 那条注释明写的要求：
 * 「专用服务器安全：客户端渲染逻辑全部隔离在 {@code @OnlyIn(CLIENT)} 的实现类」。
 * 本仓库曾因为在公共类里引用客户端类而在专用服上崩过一次（见类注释的历史）。</p>
 *
 * <p>代价是一次 {@code Class.forName}；本门面只做「首次解析 + 缓存」，之后每次调用是一次
 * 已缓存的 {@code Method.invoke}，相对包本身的编解码开销可忽略。</p>
 */
public final class ClientPacketBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientPacketBridge.class);

    private static final String IMPL_CLASS = "cn.ism.mekck.client.ClientPacketBridgeImpl";

    /** 客户端实现类。**非客户端侧、以及加载失败后恒为 null**，所有调用因此退化成 no-op。 */
    private static Class<?> impl;
    /**
     * 加载是否已经失败过 —— <b>用来把错误日志真正压到「一次」</b>。
     *
     * <p>第三轮复核发现：只靠 {@code impl != null} 的短路是<b>无效</b>的 ——
     * 失败时 {@code impl} 保持 null，于是每次调用都会重新进入 {@code Class.forName}
     * 并再打一条带堆栈的 error。而这条路径正是 S2C 包的每包入口，
     * 在集成包里会变成日志海啸（而且每条都带完整堆栈）。</p>
     */
    private static boolean resolveFailed;

    private ClientPacketBridge() {
    }

    /**
     * 是否处在有客户端的物理侧。
     *
     * <p>用 {@code FMLEnvironment.dist} 而不是 {@code Level.isClientSide}：包到达时
     * 手上未必有 {@code Level}，而且这段代码本来就要在<b>没有世界</b>的 setup 阶段可调用。</p>
     */
    private static boolean isPhysicalClient() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist
                == net.minecraftforge.api.distmarker.Dist.CLIENT;
    }

    private static boolean resolve() {
        if (impl != null) {
            return true;
        }
        if (resolveFailed) {
            return false; // 已经失败并记过了 —— 不再重复 Class.forName 与堆栈日志
        }
        if (!isPhysicalClient()) {
            return false;
        }
        try {
            impl = Class.forName(IMPL_CLASS);
            return true;
        } catch (Throwable t) {
            // 真的只记一次：resolveFailed 置位后短路（原先只置 impl=null，
            // 而 impl=null 恰好是「未加载」的状态 ⇒ 每包重打一条带堆栈的 error）。
            resolveFailed = true;
            LOGGER.error("[mekck] 客户端包落地实现类 {} 不可用，下单面板将不更新：{}", IMPL_CLASS, t.toString());
            return false;
        }
    }

    /** 往当前 Screen 的订单面板灌「可下单配方列表」。非客户端侧为 no-op。 */
    public static void applyRecipeList(BlockPos pos, List<String> recipeIds, Map<String, Integer> maxCraftable) {
        if (!resolve()) {
            return;
        }
        invoke("applyRecipeList", new Class<?>[]{BlockPos.class, List.class, Map.class},
                pos, recipeIds, maxCraftable);
    }

    /** 往当前 Screen 的订单面板灌「缺料清单」。非客户端侧为 no-op。 */
    public static void applyMissing(BlockPos pos, String recipeId, int quantity, String text) {
        if (!resolve()) {
            return;
        }
        invoke("applyMissing", new Class<?>[]{BlockPos.class, String.class, int.class, String.class},
                pos, recipeId, quantity, text);
    }

    private static void invoke(String name, Class<?>[] types, Object... args) {
        try {
            impl.getMethod(name, types).invoke(null, args);
        } catch (Throwable t) {
            // 单个包的落地失败不该把整个网络线程带崩——包本身已经正确解码过了。
            LOGGER.warn("[mekck] 客户端包落地失败 {}：{}", name, t.toString());
        }
    }
}
