package cn.ism.mekck.blockentity;

import cn.ism.mekck.TestSourceText;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 生物反应堆的读档护栏。
 *
 * <p>为什么只能钉源码：{@code BioreactorBlockEntity} 的构造链会触发 Forge 注册期状态，
 * 裸 JUnit 里加载必然失败（同包 {@code TestCentralKitchenThreadPersistence} 的既有做法）。
 * 本类只保证「读档灌能量的形态正确」，端到端由实机确认。</p>
 */
public class TestBioreactorGuards {

    private static final String BE = "src/main/java/cn/ism/mekck/blockentity/BioreactorBlockEntity.java";

    private static String load() throws IOException {
        String body = TestSourceText.methodBody(TestSourceText.read(BE), "public void load(CompoundTag tag)");
        assertFalse("找不到 BioreactorBlockEntity.load（源码测试需在仓库根目录运行）", body.isEmpty());
        return body;
    }

    /**
     * 读档必须用循环灌能量。
     *
     * <p>Forge 的 {@code EnergyStorage.receiveEnergy} 是
     * {@code min(capacity-energy, min(this.maxReceive, maxReceive))} —— 单次调用受
     * {@code maxReceive}（本类 1,000 FE）夹断。容量 100,000 的电池若单次灌，
     * 每次区块卸载/重载（含重启）最多只恢复 1,000 FE，其余 99,000 静默丢失、无日志。
     * 同仓 {@code MekCkLegacyMachine.load} 早就用循环并写明了原因，本类是漏网的同一型路径。</p>
     */
    @Test
    public void energyLoadMustLoopAroundMaxReceive() throws IOException {
        String load = load();
        assertFalse("读档不得再用单次 receiveEnergy(tag.getInt(\"Energy\"), false)："
                        + "单次调用受 maxReceive（1,000 FE）夹断，容量 100,000 每次重载最多只恢复 1,000",
                load.contains("receiveEnergy(tag.getInt(\"Energy\"), false)"));
        assertTrue("读档必须用 while 循环灌能量（照 MekCkLegacyMachine.load）",
                load.contains("while (remaining > 0)"));
        assertTrue("循环必须累计实际接收量（remaining -= received）",
                load.contains("remaining -= received"));
    }
}
