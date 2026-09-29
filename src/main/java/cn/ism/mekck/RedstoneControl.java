package cn.ism.mekck;

/**
 * 与 Mekanism 完全一致的红石控制模式。
 * DISABLED: 始终运行; HIGH: 有红石信号时运行; LOW: 无红石信号时运行; PULSE: 仅在红石脉冲(上升沿)触发。
 */
public enum RedstoneControl {
    DISABLED,
    HIGH,
    LOW,
    PULSE;

    private static final RedstoneControl[] VALUES = values();

    public static RedstoneControl byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : DISABLED;
    }

    public RedstoneControl next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    public RedstoneControl previous() {
        return VALUES[(ordinal() + VALUES.length - 1) % VALUES.length];
    }

    /**
     * 与 Mekanism's MekanismUtils.canFunction 相同的判定逻辑。
     */
    public boolean canFunction(boolean isPowered, boolean wasPowered) {
        return switch (this) {
            case DISABLED -> true;
            case HIGH -> isPowered;
            case LOW -> !isPowered;
            case PULSE -> isPowered && !wasPowered;
        };
    }
}