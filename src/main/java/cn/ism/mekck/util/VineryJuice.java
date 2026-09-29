package cn.ism.mekck.util;

import net.minecraft.world.item.ItemStack;

/**
 * 葡园酒香（vinery）果汁体系的门面：类型编号表 + 反射复用其 JuiceUtil。
 * <p>
 * 原版发酵桶把果汁存成「类型字符串 + 液位」两项状态（既不是物品也不是流体），
 * 类型共 11 种，编号 0..10 与 vinery 自身的 getJuiceTypeValue 一致，用于 ContainerData 同步。
 * 未安装 vinery 时所有方法安全返回空值，调用方走回退逻辑。
 * </p>
 */
public final class VineryJuice {

    /** 类型表：下标即 vinery 内部编号（FermentationBarrelBlockEntity.getJuiceTypeValue）。 */
    public static final String[] TYPES = {
            "white_general", "red_general", "white_savanna", "red_savanna", "white_taiga",
            "red_taiga", "white_jungle", "red_jungle", "apple", "red_crimson", "white_warped"
    };

    private static final String JUICE_UTIL = "net.satisfy.vinery.core.util.JuiceUtil";
    private static java.lang.reflect.Method isJuiceMethod;
    private static java.lang.reflect.Method getTypeMethod;
    private static boolean reflectFailed = false;

    private VineryJuice() {
    }

    /** 类型字符串 → 编号（未知返回 -1）。 */
    public static int indexOf(String type) {
        if (type == null || type.isEmpty()) return -1;
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equals(type)) return i;
        }
        return -1;
    }

    /** 编号 → 类型字符串（越界返回空串）。 */
    public static String typeAt(int index) {
        return index >= 0 && index < TYPES.length ? TYPES[index] : "";
    }

    /** 该物品是否为果汁（反射 vinery JuiceUtil.isJuice，标签驱动；未装或失败返回 false）。 */
    public static boolean isJuice(ItemStack stack) {
        Object r = invoke(stack, true);
        return Boolean.TRUE.equals(r);
    }

    /** 该物品的果汁类型（反射 vinery JuiceUtil.getJuiceType；不可用返回 null）。 */
    public static String juiceTypeOf(ItemStack stack) {
        Object r = invoke(stack, false);
        return r instanceof String s && !s.isEmpty() ? s : null;
    }

    /** 反射调用 vinery 的静态判定方法；不可用时返回 null。 */
    private static Object invoke(ItemStack stack, boolean isJuiceCall) {
        if (stack == null || stack.isEmpty() || reflectFailed) return null;
        try {
            if (isJuiceMethod == null || getTypeMethod == null) {
                Class<?> c = Class.forName(JUICE_UTIL);
                isJuiceMethod = c.getMethod("isJuice", ItemStack.class);
                getTypeMethod = c.getMethod("getJuiceType", ItemStack.class);
            }
            if (isJuiceCall) {
                return Boolean.TRUE.equals(isJuiceMethod.invoke(null, stack)) ? Boolean.TRUE : null;
            }
            Object r = getTypeMethod.invoke(null, stack);
            return r instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            reflectFailed = true; // 未装 vinery 或签名变化：整体回退，不再重试
            return null;
        }
    }

    /** 配方类型串 → 对应的果汁物品注册名（仅作反射不可用时的回退）。 */
    public static String itemIdForType(String type) {
        return switch (type) {
            case "apple" -> "vinery:apple_juice";
            case "red_general" -> "vinery:red_grapejuice";
            case "white_general" -> "vinery:white_grapejuice";
            default -> "vinery:" + type + "_grapejuice";
        };
    }

    /**
     * 陈酿配方（{@code vinery:wine_fermentation}）所需的果汁类型。
     * <p>
     * vinery 1.4.x 的 {@code FermentationBarrelRecipe} 是**扁平字段**类：
     * {@code getJuiceType():String} / {@code getJuiceAmount():int} / {@code isWineBottleRequired():boolean}，
     * **不存在** {@code getJuiceData()} 这类嵌套结构（早期代码按嵌套 record 写，导致每条配方都读不到果汁要求
     * 而被整条跳过——陈酿永不启动）。这里优先走扁平访问器，再兜底嵌套结构与同名字段，最后回落字段直读。
     * </p>
     */
    public static String recipeJuiceType(Object recipe) {
        if (recipe == null) return null;
        Object flat = Reflect.call(recipe, "getJuiceType");
        if (flat instanceof String s && !s.isEmpty()) return s;
        Object data = Reflect.call(recipe, "getJuiceData");
        if (data != null && Reflect.call(data, "type") instanceof String s && !s.isEmpty()) return s;
        Object raw = readField(recipe, "juiceType");
        return raw instanceof String s && !s.isEmpty() ? s : null;
    }

    /** 陈酿配方所需液位（0 = 该配方不要求果汁，与原版 {@code matches()} 的 {@code juiceAmount > 0} 前置判断一致）。 */
    public static int recipeJuiceAmount(Object recipe) {
        if (recipe == null) return 0;
        int v = Reflect.intField(recipe, "juiceAmount", 0);
        if (v > 0) return v;
        Object amt = Reflect.call(recipe, "getJuiceAmount");
        if (amt instanceof Integer i && i > 0) return i;
        Object data = Reflect.call(recipe, "getJuiceData");
        if (data != null) {
            Object a = Reflect.call(data, "amount");
            if (a instanceof Integer i && i > 0) return i;
        }
        return 0;
    }

    /** 字段直读兜底（getter 缺失/改名时用）；读不到返回 null。 */
    private static Object readField(Object target, String name) {
        var f = Reflect.field(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
