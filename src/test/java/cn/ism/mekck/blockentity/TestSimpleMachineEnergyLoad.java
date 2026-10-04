package cn.ism.mekck.blockentity;

import net.minecraftforge.energy.EnergyStorage;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 读档灌能量的护栏 —— 单次 {@code receiveEnergy} 受 {@code maxReceive} 夹断，必须循环。
 *
 * <h3>缺陷形态</h3>
 * {@code SimpleMachineBlockEntity.load} 原先是
 * {@code energy.receiveEnergy(tag.getInt("Energy"), false)} 单次灌入。
 * Forge 的 {@code EnergyStorage.receiveEnergy} 返回
 * {@code min(capacity - stored, min(maxReceive, maxReceiveArg))}，而本机
 * {@code ENERGY_CAPACITY = 100_000}、{@code MAX_RECEIVE = 1_000} ⇒ 每次区块重载
 * （含重启）最多只灌回 1,000 FE，静默丢掉 99,000，无日志。
 *
 * <p>同仓 {@code MekCkLegacyMachine.load} 早就写着「单次受 maxReceive 夹断」的 while 循环，
 * 本机是漏网的第 N 处。修复 = 照抄那个循环，并抽成静态纯函数
 * {@link SimpleMachineBlockEntity#loadEnergy}，让「容量 100k / maxReceive 1k 灌 100k
 * 必须得 100k」这条断言能在裸 JVM 里直接跑（BE 本身需要注册表与 level，构造不出来）。</p>
 */
public class TestSimpleMachineEnergyLoad {

    private static final Path BE =
            Path.of("src/main/java/cn/ism/mekck/blockentity/SimpleMachineBlockEntity.java");

    /** 与生产同参数：容量 100k、单次上限 1k、不可抽出。 */
    private static EnergyStorage machineShapedStorage() {
        return new EnergyStorage(SimpleMachineBlockEntity.ENERGY_CAPACITY,
                SimpleMachineBlockEntity.MAX_RECEIVE, 0);
    }

    @Test
    public void fullTankSurvivesTheLoadLoop() {
        EnergyStorage storage = machineShapedStorage();
        SimpleMachineBlockEntity.loadEnergy(storage, SimpleMachineBlockEntity.ENERGY_CAPACITY);
        assertEquals("容量 100k / maxReceive 1k 的容器灌 100k 必须得 100k；"
                        + "只灌进 1k 就是单次 receiveEnergy 被 maxReceive 夹断的旧缺陷",
                SimpleMachineBlockEntity.ENERGY_CAPACITY, storage.getEnergyStored());
    }

    /**
     * 反证：同一输入走单次 {@code receiveEnergy} 只能得 {@code MAX_RECEIVE}。
     *
     * <p>这条断言把「为什么必须循环」钉成可执行的事实 —— 若 {@link #fullTankSurvivesTheLoadLoop}
     * 被改成单次形态，两条断言不可能同时成立。</p>
     */
    @Test
    public void singleShotWouldOnlyFillMaxReceive() {
        EnergyStorage storage = machineShapedStorage();
        storage.receiveEnergy(SimpleMachineBlockEntity.ENERGY_CAPACITY, false);
        assertEquals("单次 receiveEnergy 的返回值就是 maxReceive 夹断后的量",
                SimpleMachineBlockEntity.MAX_RECEIVE, storage.getEnergyStored());
    }

    @Test
    public void smallAndEmptyValuesAreUnchanged() {
        EnergyStorage storage = machineShapedStorage();
        SimpleMachineBlockEntity.loadEnergy(storage, 0);
        assertEquals("空存档值不得凭空灌电", 0, storage.getEnergyStored());
        SimpleMachineBlockEntity.loadEnergy(storage, 500);
        assertEquals("小于单次上限的值原样灌入", 500, storage.getEnergyStored());
    }

    @Test
    public void overCapacityValueIsClampedWithoutHanging() {
        EnergyStorage storage = machineShapedStorage();
        SimpleMachineBlockEntity.loadEnergy(storage, SimpleMachineBlockEntity.ENERGY_CAPACITY + 50_000);
        assertEquals("超过容量的存档值夹到容量，且循环必须能退出",
                SimpleMachineBlockEntity.ENERGY_CAPACITY, storage.getEnergyStored());
    }

    /**
     * 容器拒收（{@code maxReceive == 0}）时循环必须靠 {@code received == 0} 退出。
     *
     * <p>少了这个出口，任何「灌不进」的容器都会让 {@code load} 死循环 —— 比丢能量更严重。</p>
     */
    @Test
    public void refusingStorageTerminatesTheLoop() {
        EnergyStorage storage = new EnergyStorage(SimpleMachineBlockEntity.ENERGY_CAPACITY, 0, 0);
        SimpleMachineBlockEntity.loadEnergy(storage, SimpleMachineBlockEntity.ENERGY_CAPACITY);
        assertEquals("拒收的容器灌不进任何能量，且循环必须已退出", 0, storage.getEnergyStored());
    }

    /**
     * 源码形态护栏：{@code load} 必须走循环，不得退回单次形态。
     *
     * <p>纯函数测试只证明 {@code loadEnergy} 是对的；若 {@code load} 绕过它自己写单次
     * {@code receiveEnergy(tag.getInt("Energy"))}，缺陷照样回来。这条断言堵住那个入口。</p>
     */
    @Test
    public void loadRoutesThroughTheLoop() throws IOException {
        String src = Files.readString(BE, StandardCharsets.UTF_8);
        assertTrue("load 必须走 loadEnergy 循环灌能量",
                src.contains("loadEnergy(energy, tag.getInt(\"Energy\"))"));
        assertFalse("load 不得退回单次 receiveEnergy(tag.getInt(\"Energy\")) —— "
                        + "受 maxReceive 夹断，每次重载最多丢 99,000 FE",
                src.contains("receiveEnergy(tag.getInt(\"Energy\")"));
    }
}
