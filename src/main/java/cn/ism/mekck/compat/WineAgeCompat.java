package cn.ism.mekck.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;

/**
 * 酒的「年份/age」读写门面（陈化窖 F20 的地基），对施工线隐藏「数据到底存哪」。
 * <p>
 * vinery 的年龄是<b>现算</b>：{@code age = 世界当前年 − 瓶身 Year}，其中
 * {@code 世界当前年 = (gameTime/24000)/DAYS_PER_YEAR}（DAYS_PER_YEAR=24）。
 * 让一瓶酒变更老的<b>唯一杠杆</b>就是把它的 {@code Year} 往过去推 K 年——amplifier/duration
 * 全从 Year 派生、由 vinery 每 200t 自行刷新，本机不维护。
 * </p>
 * <p>
 * 实机取证（letsdo-vinery-forge-1.4.41 / MC 1.20.1）：年份<b>平铺</b>在 {@code ItemStack} 的 NBT
 * 上，三个 int 键见下（分支 A）。故默认走 NBT；另保留 <b>DataComponent 反射兜底</b>（分支 B），
 * 仅防将来升到 1.20.5+ 的 vinery——1.20.1 上该 API 不存在 ⇒ 分支 B 恒不可用。
 * 两分支都读不到 ⇒ {@link #hasWineAge} 返回 false、该格 fail-safe（不催陈、不计费）；未装 vinery 同样安全。
 * </p>
 */
public final class WineAgeCompat {

    /** vinery {@code WineYears} 的三个 NBT 键（分支 A）。 */
    private static final String TAG_YEAR = "Year";
    private static final String TAG_EFFECT_LEVEL = "EffectAmplifier";
    private static final String TAG_EFFECT_DURATION = "EffectDuration";

    /** vinery：1 游戏年 = 24 游戏日（§8.1），仅用于 {@link #readAgeYears} 的诊断式换算。 */
    private static final int DAYS_PER_YEAR = 24;

    /**
     * §F44：vinery 效果封顶 = {@code MAX_LEVEL(5) × YEARS_PER_EFFECT_LEVEL(6) = 30} 游戏年，
     * 再陈 amplifier 也不涨。陈化窖改以「效果封顶」为停格判据（旧判据 Year≤0 在年轻世界
     * 永远命中：开局世界年还是 0，任何酒打标 Year=0 ⇒ 入格即被判「推无可推」永不工作）。
     */
    public static final int YEARS_TO_MAX_EFFECT = 30;

    private WineAgeCompat() {
    }

    /** 该物品是否为「带 age 标签的酒」（能读到 Year）。 */
    public static boolean hasWineAge(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        return readYear(s) != null;
    }
    
    /** 读瓶身年份：分支 A（NBT）优先，分支 B 兜底（1.20.1 恒 null）；读不到返回 null。 */
    private static Integer readYear(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        if (s.hasTag() && s.getTag().contains(TAG_YEAR)) return s.getTag().getInt(TAG_YEAR);
        return readComponentYear(s);
    }

    /**
     * 返回把 {@code Year} 往过去推 {@code yearsBack} 年的<b>副本</b>（不改动传入 stack）。
     * §F44：去掉旧的 ≥0 钳制——允许负 Year = 把酒送到「世界诞生之前」，正是时间悖论产生器
     * 的题中之义；vinery 的 age/ageDays 换算全带 {@code max(0, …)} 保护（研究简报 §四公式），
     * 负年只会让年龄更大、不会异常。
     */
    public static ItemStack accelerate(ItemStack s, int yearsBack) {
        ItemStack copy = s.copy();
        // 分支 A：NBT（实机走这条）
        if (copy.hasTag() && copy.getTag().contains(TAG_YEAR)) {
            CompoundTag t = copy.getOrCreateTag();
            t.putInt(TAG_YEAR, t.getInt(TAG_YEAR) - yearsBack);
            return copy;
        }
        // 分支 B：DataComponent 兜底（仅未来版本；当前多为 no-op）
        Integer compYear = readComponentYear(copy);
        if (compYear != null) {
            writeComponentYear(copy, Math.max(0, compYear - yearsBack));
        }
        return copy;
    }

    /**
     * 只读诊断：该瓶酒当前约等于多少「陈化年」。机器主逻辑不依赖此值（只推 Year）。
     * 未装 vinery / 读不到 ⇒ 0。
     */
    public static int readAgeYears(ItemStack s, Level level) {
        if (s == null || s.isEmpty() || level == null) return 0;
        Integer year = null;
        if (s.hasTag() && s.getTag().contains(TAG_YEAR)) {
            year = s.getTag().getInt(TAG_YEAR);
        } else {
            year = readComponentYear(s);
        }
        if (year == null) return 0;
        long worldYear = level.getGameTime() / 24000L / DAYS_PER_YEAR;
        return (int) Math.max(0L, worldYear - year);
    }

    /**
     * 是否已「陈到头」（§F44 改版）：效果封顶——年龄（世界年 − Year）≥ {@link #YEARS_TO_MAX_EFFECT}
     * 时 amplifier 已打满，再推无收益 ⇒ 陈化窖停该格、停该格耗能，避免白烧。
     * <p>旧判据 Year≤0 在年轻世界永远命中（开局世界年=0，兜底打标的酒 Year 也=0 ⇒
     * 任何酒入格即被判封顶 ⇒ 永不工作），已废。无 level（如客户端预览）时保守返回 false
     * 不拦陈化；读不到年份的瓶本就过不了 {@link #hasWineAge} 闸门，这里返回 true。</p>
     */
    public static boolean isAgedOut(ItemStack s, Level level) {
        if (s == null || s.isEmpty()) return true;
        Integer year = readYear(s);
        if (year == null) return true;
        if (level == null) return false;
        long worldYear = level.getGameTime() / 24000L / DAYS_PER_YEAR;
        return worldYear - year >= YEARS_TO_MAX_EFFECT;
    }

    // ======================================================================
    // 分支 B：DataComponent 反射兜底
    // 1.20.1 的 ItemStack 没有 1.20.5 的 get/set(DataComponentType) API，也没有 vinery 的
    // WINE_YEAR 组件类型 ⇒ 以下探测在当前环境恒失败、返回 null/no-op，仅为将来升级预留骨架。
    // 全部经 Reflect 缓存、任何异常都吞掉，绝不抛出、绝不影响分支 A。
    // ======================================================================

    private static volatile boolean dcProbed;
    private static boolean dcUsable;

    /** 尝试反射读出分支 B 的 Year；不可用/读不到返回 null。 */
    private static Integer readComponentYear(ItemStack s) {
        probeDataComponent();
        if (!dcUsable) return null;
        // 未来实现：stack.get(WINE_YEAR_TYPE) → record.year()
        return null;
    }

    /** 尝试反射写回分支 B 的 Year；不可用则 no-op。 */
    private static void writeComponentYear(ItemStack s, int year) {
        // 分支 B 在当前环境不可用；预留：newRecord = record.withYear(year); stack.set(type, newRecord)
    }

    /** 惰性探测 DataComponent 分支是否可用（1.20.1 ⇒ 否）。 */
    private static synchronized void probeDataComponent() {
        if (dcProbed) return;
        dcProbed = true;
        // 仅当 ItemStack 暴露 1.20.5+ 的组件读写方法、且能解析 vinery WINE_YEAR 类型时才置 true。
        // 当前版本直接判定为不可用；升级 vinery/MC 后在此补反射定位即可，主逻辑无需改动。
        dcUsable = false;
    }
}
