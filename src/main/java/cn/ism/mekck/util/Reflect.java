package cn.ism.mekck.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射元数据缓存。
 * <p>
 * 模组大量使用反射读取其它模组的配方字段（因为它们不是编译期依赖）。
 * 但 {@code getClass().getMethod(...)} / {@code getDeclaredField(...)} 每次调用都要做签名匹配与副本构造，
 * 放在机器每 tick 的热路径上是明显的浪费。这里按「类 → 名称」缓存解析结果：
 * 首次解析后，后续调用只做一次 Map 查找 + invoke。
 * </p>
 * <p>
 * 约定：解析失败返回 null（调用方自行回退），失败结果同样被缓存（避免反复抛异常）。
 * </p>
 */
public final class Reflect {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** 已经提示过「解析失败」的目标，避免同一个缺失被刷屏。 */
    private static final Set<String> MISSING_LOGGED = ConcurrentHashMap.newKeySet();

    private static final Map<Class<?>, Map<String, Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Constructor<?>>> CTOR_CACHE = new ConcurrentHashMap<>();
    private static final Method MISSING_METHOD;
    private static final Field MISSING_FIELD;
    private static final Constructor<?> MISSING_CTOR;

    static {
        try {
            MISSING_METHOD = Reflect.class.getDeclaredMethod("missingMethod");
            MISSING_FIELD = Reflect.class.getDeclaredField("MISSING_FIELD");
            MISSING_CTOR = Reflect.class.getDeclaredConstructor();
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    /**
     * 记录一次「反射目标解析失败」，每个 {@code target} 只记一次。
     *
     * <p>本模组有大量 {@code catch (Throwable ignored) {}} —— 那是必要的（可选模组的类
     * 在缺席时本就拿不到），但完全静默会让「上游改了字段名 → 机器静默停产」这类问题
     * 无法排查。凡是<b>确实尝试过</b>解析的地方，都应当走这里留一条 INFO。</p>
     *
     * @param target 出问题的类名/字段标识，用于去重
     * @param cause  原始异常，可为 null
     */
    public static void logMissingOnce(String target, Throwable cause) {
        if (MISSING_LOGGED.add(target)) {
            LOGGER.info("[mekck] 反射目标不可用：{}（相关联动将静默降级）{}", target,
                    cause == null ? "无异常信息" : cause.toString());
        }
    }

    private static void missingMethod() {
    }

    private Reflect() {
    }

    /** 解析无参公共方法（含继承链）；失败返回 null（结果被缓存）。 */
    public static Method method(Class<?> owner, String name) {
        return method(owner, name, new Class<?>[0]);
    }

    /**
     * 解析带参公共方法（含继承链）；失败返回 null（结果被缓存）。
     * 缓存键 = 方法名 + 参数类型列表，因此同一个类上的不同重载互不干扰。
     */
    public static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null || name == null) return null;
        String key = name + java.util.Arrays.toString(params);
        Method m = METHOD_CACHE.computeIfAbsent(owner, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, n -> {
                    try {
                        Method found = owner.getMethod(name, params);
                        found.setAccessible(true);
                        return found;
                    } catch (Throwable t) {
                        return MISSING_METHOD;
                    }
                });
        return m == MISSING_METHOD ? null : m;
    }

    /** 反射调用某个类的静态方法（句柄带缓存）；不可用或抛异常返回 null。 */
    public static Object callStatic(Class<?> owner, String name, Class<?>[] params, Object... args) {
        Method m = method(owner, name, params);
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 调用目标对象的无参方法；不可用或抛异常返回 null。 */
    public static Object call(Object target, String name) {
        if (target == null) return null;
        Method m = method(target.getClass(), name);
        if (m == null) return null;
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析字段（含继承链，含私有）；失败返回 null（结果被缓存）。 */
    public static Field field(Class<?> owner, String name) {
        if (owner == null || name == null) return null;
        Field f = FIELD_CACHE.computeIfAbsent(owner, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, n -> {
                    Class<?> c = owner;
                    while (c != null && c != Object.class) {
                        try {
                            Field found = c.getDeclaredField(n);
                            found.setAccessible(true);
                            return found;
                        } catch (Throwable ignored) {
                            c = c.getSuperclass();
                        }
                    }
                    return MISSING_FIELD;
                });
        return f == MISSING_FIELD ? null : f;
    }

    /** 读取 int 字段（含继承链、私有）；读不到返回 fallback。 */
    public static int intField(Object target, String name, int fallback) {
        if (target == null) return fallback;
        Field f = field(target.getClass(), name);
        if (f == null) return fallback;
        try {
            Object v = f.get(target);
            return v instanceof Integer i ? i : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 解析构造器（按参数类型精确匹配）；失败返回 null（结果被缓存）。 */
    public static Constructor<?> ctor(Class<?> owner, Class<?>... params) {
        if (owner == null) return null;
        String key = java.util.Arrays.toString(params);
        Constructor<?> c = CTOR_CACHE.computeIfAbsent(owner, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> {
                    try {
                        Constructor<?> found = owner.getDeclaredConstructor(params);
                        found.setAccessible(true);
                        return found;
                    } catch (Throwable t) {
                        return MISSING_CTOR;
                    }
                });
        return c == MISSING_CTOR ? null : c;
    }
}
