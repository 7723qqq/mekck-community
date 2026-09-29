package cn.ism.mekck.blockentity;

import org.junit.Test;

import static org.junit.Assert.*;

/** TavernBarrelPlan 纯逻辑测试（普通 JVM）：批次数量计算。
 *  说明：of() 校验依赖 FluidStack/ItemStack（MC 注册表初始化），无法在普通 JVM 执行，保留为运行时待验收。 */
public class TestTavernBarrelPlan {

    @Test
    public void bottlesMinCount() {
        assertEquals(3, TavernBarrelPlan.computeBottlesFromCounts(new int[]{16, 3, 8}));
    }

    @Test
    public void bottlesCapAt16() {
        assertEquals(16, TavernBarrelPlan.computeBottlesFromCounts(new int[]{64}));
        assertEquals(16, TavernBarrelPlan.computeBottlesFromCounts(new int[]{999}));
    }

    @Test
    public void bottlesEmptyIngredientsDefault16() {
        assertEquals(16, TavernBarrelPlan.computeBottlesFromCounts(new int[]{0, 0}));
        assertEquals(16, TavernBarrelPlan.computeBottlesFromCounts(null));
    }

    @Test
    public void bottlesSingleOne() {
        assertEquals(1, TavernBarrelPlan.computeBottlesFromCounts(new int[]{1, 5}));
    }
}
