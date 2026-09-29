package cn.ism.mekck.util;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * 永冻战利品专用随机源：所有随机概率必然命中（nextFloat=0 → chance 判定必过）、
 * 所有随机数量取上限（nextInt(bound) → bound-1）。同池多个条目仍按权重择一（战利品表结构限制）。
 */
public enum MaxLootRandom implements RandomSource {
    INSTANCE;

    @Override
    public RandomSource fork() {
        return this;
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        // 战利品掷骰不会使用定位随机；返回一个真实实现以防被调用
        return RandomSource.create(0).forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        // 恒定输出，无需播种
    }

    @Override
    public int nextInt() {
        return 1;
    }

    @Override
    public int nextInt(int bound) {
        return bound <= 0 ? 0 : bound - 1;
    }

    @Override
    public long nextLong() {
        return 1L;
    }

    @Override
    public boolean nextBoolean() {
        return true;
    }

    @Override
    public float nextFloat() {
        return 0.0F;
    }

    @Override
    public double nextDouble() {
        return 0.0D;
    }

    @Override
    public double nextGaussian() {
        return 0.0D;
    }
}
