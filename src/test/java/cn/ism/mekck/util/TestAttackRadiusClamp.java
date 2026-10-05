package cn.ism.mekck.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 攻击半径上下限的护栏 —— 回归第三轮审查抓到的「一个包把机器变成全服实体扫描器」。
 *
 * <h3>缺陷形态</h3>
 * 四个 BE（急冻制冰机 / 制冰工厂 / 坚果爆炒机 / 巧克力大炮）的
 * {@code setRadius} / {@code adjustRadius} 原本<b>只有下限没有上限</b>，
 * 而半径来自网络包 {@code IceAttackConfigPacket} 的裸 {@code readInt}。
 * 配合 {@code IceTargetSearch} 的大半径分支（遍历 {@code getEntities().getAll()}）
 * 与「创造升级让 attackTimer = 1」，就是<b>一台机器 + 一个包 = 永久的每 tick 全服实体遍历</b>。
 *
 * <p>本测试只钉「数值不越界」这一条 —— 它是能在裸 JVM 里断言的部分。
 * 成本模型（{@code AABB_SCAN_MAX_RADIUS}）在源码里，由下面的源码断言守住。</p>
 */
public class TestAttackRadiusClamp {

    @Test
    public void radiusIsClampedToBothEnds() {
        assertEquals(4, IceTargetSearch.clampAttackRadius(-100));
        assertEquals(4, IceTargetSearch.clampAttackRadius(0));
        assertEquals(4, IceTargetSearch.clampAttackRadius(4));
        assertEquals(4, IceTargetSearch.clampAttackRadius(3));
        assertEquals(10, IceTargetSearch.clampAttackRadius(10));
        assertEquals(256, IceTargetSearch.clampAttackRadius(256));
    }

    @Test
    public void absurdValuesArePulledBackToTheCeiling() {
        // 这三个正是「任意玩家一个包」的可达输入：PacketGuard 只校验交互距离（8 格），
        // 不校验 value 本身。
        assertEquals(256, IceTargetSearch.clampAttackRadius(1000));
        assertEquals(256, IceTargetSearch.clampAttackRadius(1_000_000));
        assertEquals(256, IceTargetSearch.clampAttackRadius(Integer.MAX_VALUE));
    }

    @Test
    public void adjustRadiusCannotOverflowTheIntermediateSum() {
        // 旧实现的 adjustRadius 写的是 (int) Math.max(4L, Math.min((long) Integer.MAX_VALUE,
        // (long) this.radius + delta)) —— 注意它先在 long 上加，再 Math.min 到 MAX_VALUE，
        // 那条路本身是安全的；真正缺的是「上限是多少」。这里钉住新口径：
        // 先把加法夹到 MAX_ATTACK_RADIUS，再过 clampAttackRadius。
        int radius = 256;
        int delta = Integer.MAX_VALUE;
        int safe = IceTargetSearch.clampAttackRadius(
                (int) Math.min((long) IceTargetSearch.MAX_ATTACK_RADIUS, (long) radius + delta));
        assertEquals(256, safe);

        int negative = IceTargetSearch.clampAttackRadius(
                (int) Math.min((long) IceTargetSearch.MAX_ATTACK_RADIUS, (long) 100 + -50));
        assertEquals(50, negative);
    }

    /**
     * 源码层断言：AABB 快速路径的阈值必须远小于 512。
     *
     * <p>{@code Level.getEntitiesOfClass} 遍历与 AABB 相交的<b>每一个</b>实体 section，
     * 半径 R ⇒ section 范围 ±(R/16+1)，半径 512 就是 {@code 65³ ≈ 27 万}次 section 查找
     * <b>每次攻击一次</b> —— 比它自己那条「遍历全实体」的兜底还贵。本轮把它降到 64
     * （{@code 9³ = 729} 次）。这个数字没有测试能跑出来，只能钉源码。</p>
     */
    @Test
    public void aabbScanThresholdStaysCheap() throws java.io.IOException {
        String src = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/cn/ism/mekck/util/IceTargetSearch.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                // 源码里可能写成 64.0 或 64.0D，两种都要认（第一版只认 D，于是自己把自己测挂了）
                .compile("AABB_SCAN_MAX_RADIUS\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)[dD]?\\s*;")
                .matcher(src);
        assertTrue("没找到 AABB_SCAN_MAX_RADIUS 的定义（改名了就同步更新本测试）", m.find());
        double threshold = Double.parseDouble(m.group(1));
        assertTrue("AABB 快速路径阈值 " + threshold
                        + " 过大：成本是 (R/16+1)³ 次 section 查找，每次攻击一次。"
                        + "64 对应 9³=729 次，是合理的上限",
                threshold <= 128.0);
    }

    /**
     * 四个 BE 的两个半径 setter 必须<b>全部</b>经过同一道闸。
     *
     * <p>逐个文件检查，而不是相信「我改过」—— 这正是该缺陷的成因：
     * 四份各写一遍「只有下限」的钳制，改的时候很容易只改到其中几个。</p>
     *
     * <p>2026-10-06：坚果爆炒机迁到 Mek 原生体系，类名与路径都变了
     * （{@code blockentity/NutRoasterBlockEntity} → {@code machine/roasting/NutRoasterTile}）。
     * 判据的<b>落点</b>跟着改，断言的东西一个字没动。</p>
     */
    @Test
    public void everyAttackMachineRoutesBothSettersThroughTheSharedClamp() throws java.io.IOException {
        for (String[] machine : attackMachines()) {
            String name = machine[0];
            String src = java.nio.file.Files.readString(
                    java.nio.file.Path.of(machine[1]), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(name + ".setRadius 必须走 IceTargetSearch.clampAttackRadius"
                            + "（无上限的半径来自网络包，配合大半径分支 = 每 tick 全服实体遍历）",
                    methodBody(src, "public void setRadius(int r) {")
                            .contains("IceTargetSearch.clampAttackRadius"));
            assertTrue(name + ".adjustRadius 必须走 IceTargetSearch.clampAttackRadius",
                    methodBody(src, "public void adjustRadius(int delta) {")
                            .contains("IceTargetSearch.clampAttackRadius"));
            // 反向断言：旧的「上限不限」写法必须已经消失。
            assertTrue(name + " 仍残留「上限不限」的旧钳制写法",
                    !src.contains("Math.min((long) Integer.MAX_VALUE, (long) this.radius + delta)"));
        }
    }

    /**
     * 读档路径（{@code readAdditionalSaveData} 里的 {@code tag.getInt("Radius")}）也必须过同一道闸。
     *
     * <p>四台机器都把 radius 落盘，裸读回来的旧档值同样能绕过上限（存档可被外部编辑、或来自无上限的旧版本）。
     * 这与 setter 的闸门是同一缺陷的另一入口 —— 只堵 setter 会留下「旧档即全服扫描器」的后门。</p>
     */
    @Test
    public void radiusReadFromNbtRoutesThroughTheSharedClamp() throws java.io.IOException {
        for (String[] machine : attackMachines()) {
            String name = machine[0];
            String src = java.nio.file.Files.readString(
                    java.nio.file.Path.of(machine[1]), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(name + " 读档 radius 必须走 IceTargetSearch.clampAttackRadius",
                    src.contains("radius = IceTargetSearch.clampAttackRadius(tag.getInt(\"Radius\"))"));
            assertFalse(name + " 仍有绕过闸门的裸读 radius = tag.getInt(\"Radius\")",
                    src.contains("radius = tag.getInt(\"Radius\")"));
        }
        // 顺带钉死本轮删掉的死语句（读取结果被丢弃的 machine.data.get(DATA_ENERGY)）不得回潮。
        // 四台同型：IceMaker（第 6 轮删）+ ChocolateCannon / NutRoaster（M13 删）
        // + SimpleMachineBlockEntity（M17 删）。
        // 2026-10-06：坚果爆炒机的落点随迁移改为 machine/roasting/NutRoasterTile.java ——
        // 新 tile 没有 data 字段，但这条断言（同一条被丢弃结果的死语句不得回潮）照旧适用。
        String[][] deadStatementMachines = {
                {"IceMakerBlockEntity", "src/main/java/cn/ism/mekck/blockentity/IceMakerBlockEntity.java"},
                {"ChocolateCannonBlockEntity", "src/main/java/cn/ism/mekck/blockentity/ChocolateCannonBlockEntity.java"},
                {"NutRoasterTile", "src/main/java/cn/ism/mekck/machine/roasting/NutRoasterTile.java"},
                {"SimpleMachineBlockEntity", "src/main/java/cn/ism/mekck/blockentity/SimpleMachineBlockEntity.java"},
        };
        for (String[] m : deadStatementMachines) {
            String dead = java.nio.file.Files.readString(
                    java.nio.file.Path.of(m[1]), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(m[0] + " 又出现了被丢弃结果的 machine.data.get(DATA_ENERGY) 死语句",
                    dead.contains("machine.data.get(DATA_ENERGY);"));
        }
    }

    /**
     * 四台攻击型机器的「类简名 → 源文件路径」。
     *
     * <p>2026-10-06：坚果爆炒机迁到 Mek 原生体系后落在 {@code machine/roasting/}，
     * 不再位于 {@code blockentity/}。判据的落点因此改为显式路径表 —— 断言的内容不变。</p>
     */
    private static String[][] attackMachines() {
        return new String[][]{
                {"IceMakerBlockEntity", "src/main/java/cn/ism/mekck/blockentity/IceMakerBlockEntity.java"},
                {"IceFactoryBlockEntity", "src/main/java/cn/ism/mekck/blockentity/IceFactoryBlockEntity.java"},
                {"ChocolateCannonBlockEntity", "src/main/java/cn/ism/mekck/blockentity/ChocolateCannonBlockEntity.java"},
                {"NutRoasterTile", "src/main/java/cn/ism/mekck/machine/roasting/NutRoasterTile.java"},
        };
    }

    private static String methodBody(String src, String signature) {
        int i = src.indexOf(signature);
        if (i < 0) {
            return "";
        }
        int depth = 0;
        int start = src.indexOf('{', i);
        for (int j = start; j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(i, j + 1);
                }
            }
        }
        return src.substring(i);
    }
}
