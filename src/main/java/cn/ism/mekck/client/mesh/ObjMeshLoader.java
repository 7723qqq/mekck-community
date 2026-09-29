package cn.ism.mekck.client.mesh;

import cn.ism.mekck.UniversalCuttingMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * OBJ 网格加载器：{@link ResourceLocation} → {@link ObjMesh}，带缓存与失败冷却。
 *
 * <h2>为什么缓存</h2>
 * 移植自 mek_adapter 的同名能力。原版每次 {@code load()} 都重读文件并重编译 display list，
 * 相当于永不缓存；1.20.1 改为「缓存 + 资源重载时统一失效」，使 F3+T 之后 OBJ 能真正热重载，
 * 同时避免每帧重解析。
 *
 * <h2>失败不写缓存</h2>
 * 失败只写一条错误日志并置一个 5 秒冷却窗口，冷却期内重复请求直接返回空网格
 * （既不重试也不刷日志），冷却结束后允许重试——以支持「先启动游戏、后放入 obj」
 * 这类开发场景。失败结果**不**进缓存，以免把失败状态永久固化。
 *
 * <h2>线程</h2>
 * {@link #load} 读取资源，只应在渲染线程（主线程）调用；缓存与失效回调同在主线程，
 * 因此使用普通 {@link HashMap} 即可。
 */
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ObjMeshLoader {

    public static final Logger LOGGER = LoggerFactory.getLogger(ObjMeshLoader.class);

    /** 失败后的重试冷却时长。 */
    private static final long FAIL_COOLDOWN_MILLIS = 5000L;

    private static final Map<ResourceLocation, ObjMesh> CACHE = new HashMap<>();
    private static final Map<ResourceLocation, Long> FAILED_UNTIL = new HashMap<>();

    private ObjMeshLoader() {
    }

    /**
     * 加载 OBJ 网格。失败时返回一个 {@linkplain ObjMesh#isAvailable() 不可用} 的空网格，
     * 绝不抛异常——渲染器应当据此跳过绘制而不是让游戏崩溃。
     */
    public static ObjMesh load(ResourceLocation obj) {
        ObjMesh cached = CACHE.get(obj);
        if (cached != null) {
            return cached;
        }
        Long until = FAILED_UNTIL.get(obj);
        if (until != null && System.currentTimeMillis() < until) {
            return ObjMesh.empty();
        }

        // 客户端尚未就绪时 Minecraft.getInstance() 会返回 null（集成服务器 / 早于
        // 资源加载的启动阶段）。显式判空，别让它变成一个来源难辨的 NPE。
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return ObjMesh.empty();
        }

        Map<String, float[]> parsed;
        try {
            // ResourceManager 继承 ResourceProvider，getResource 返回 Optional<Resource>
            // （取资源包栈中优先级最高的那一份，尊重资源包覆盖顺序）。
            var resource = minecraft.getResourceManager().getResource(obj);
            if (resource.isEmpty()) {
                fail(obj, "资源不存在", null);
                return ObjMesh.empty();
            }
            try (BufferedReader reader = resource.get().openAsReader()) {
                parsed = ObjMesh.parse(reader);
            }
        } catch (IOException | UncheckedIOException e) {
            // 只兜 I/O 类异常：宽泛的 catch (Exception) 会把渲染路径里真实的空指针
            // 也记成「读取或解析失败」，把代码 bug 伪装成资产问题。
            fail(obj, "读取或解析失败", e);
            return ObjMesh.empty();
        }

        ObjMesh mesh = ObjMesh.of(parsed);
        if (!mesh.isAvailable()) {
            fail(obj, "无可用分组（全为空或解析失败）", null);
            return ObjMesh.empty();
        }
        CACHE.put(obj, mesh);
        FAILED_UNTIL.remove(obj);
        return mesh;
    }

    /** 清空缓存与冷却状态。资源重载后调用，使下一次 {@link #load} 重新读取。 */
    public static void invalidateAll() {
        int cached = CACHE.size();
        CACHE.clear();
        FAILED_UNTIL.clear();
        if (cached > 0) {
            LOGGER.info("[ObjMesh] 资源重载，已清空 {} 个缓存网格", cached);
        }
    }

    private static void fail(ResourceLocation obj, String reason, Throwable error) {
        FAILED_UNTIL.put(obj, System.currentTimeMillis() + FAIL_COOLDOWN_MILLIS);
        if (error != null) {
            LOGGER.error("[ObjMesh] OBJ {}: {}", reason, obj, error);
        } else {
            LOGGER.error("[ObjMesh] OBJ {}: {}", reason, obj);
        }
    }

    /**
     * 资源重载并完成模型烘焙后清缓存。
     *
     * <p>选用 {@code BakingCompleted} 而非 {@code RegisterClientReloadListenersEvent}：
     * 后者的 {@code registerReloadListener} 只接受 {@code PreparableReloadListener}，
     * 其唯一抽象方法带 6 个参数（本实现一个都用不上），写出来噪音很大。
     * 烘焙完成同样发生在资源重载之后、渲染之前，时机等价而写法干净。</p>
     */
    @SubscribeEvent
    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        invalidateAll();
    }
}
