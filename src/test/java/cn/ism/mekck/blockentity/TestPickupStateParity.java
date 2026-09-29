package cn.ism.mekck.blockentity;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * 钉住「挖下来的机器与留在世界里的机器状态必须相同」。
 *
 * <p>{@code SimpleMachineBlockEntity.saveToItem} 手写了一份 NBT（不走
 * {@code saveAdditional}），因此任何<b>辅助持久化助手</b>只要没被同步调用，
 * 玩家挖机再放下就会静默丢失——而 {@code getDrops} 为空意味着方块物品是状态的唯一载体。
 *
 * <p>I5 就是这样漏的：{@code saveAdditional} 调了 {@code AE2Compat.saveAdditional}
 * 与 {@code PlacerPersist.save}，{@code saveToItem} 两个都没调，而 {@code load}
 * 两个都读。结果是 ME 自动补料清单与放置器 UUID 必丢。
 *
 * <p>既有的 {@code TestNbtPersistenceInvariants} 只比对
 * {@code tag.put("Xxx")} 形式的<b>字面键</b>；两个助手写的是嵌套结构，
 * 没有任何 {@code put("字面键")} 出现在 saveToItem 里，所以那条测试抓不到它。
 * 本测试改为比对<b>助手调用</b>，把这条漏洞补上。
 */
public class TestPickupStateParity {

    private static final Path SRC = Path.of("src/main/java/cn/ism/mekck/blockentity/SimpleMachineBlockEntity.java");

    private static String methodBody(String signature) throws IOException {
        String s = Files.readString(SRC, StandardCharsets.UTF_8);
        int i = s.indexOf(signature);
        assertTrue("未找到方法：" + signature, i >= 0);
        int from = s.indexOf('{', i);
        int depth = 0;
        for (int k = from; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(from, k);
        }
        return s.substring(from);
    }

    private static List<String> helperCalls(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(AE2Compat|PlacerPersist)\\.(\\w+)").matcher(body);
        while (m.find()) out.add(m.group(1) + "." + m.group(2));
        return out;
    }

    /**
     * 只取<b>写</b>侧的调用。读侧方法名与写侧不同（{@code AE2Compat.load} ↔
     * {@code AE2Compat.saveAdditional}），不能按名字直接比对。
     */
    private static List<String> saveSideCalls(String body) {
        List<String> out = new ArrayList<>();
        for (String c : helperCalls(body)) {
            if (c.endsWith(".load")) continue;
            out.add(c);
        }
        return out;
    }

    /**
     * saveToItem 调用的持久化助手，必须与 saveAdditional 调用的完全一致。
     * 少一个，就意味着那部分状态在「挖起来再放下」后消失。
     */
    @Test
    public void saveToItemCallsEveryPersistenceHelperThatSaveAdditionalCalls() throws IOException {
        List<String> world = helperCalls(methodBody("void saveAdditional(CompoundTag tag)"));
        List<String> pickup = helperCalls(methodBody("public void saveToItem(ItemStack stack)"));

        List<String> missing = new ArrayList<>(world);
        missing.removeAll(pickup);
        assertTrue("saveToItem 漏调持久化助手（挖机再放下必丢对应状态）：\n  " + String.join("\n  ", missing),
                missing.isEmpty());
    }

    /** 反向：saveToItem 不得写 saveAdditional 不写的东西，否则会写出 load 从不读的键。 */
    @Test
    public void saveToItemDoesNotCallHelpersThatSaveAdditionalDoesNot() throws IOException {
        List<String> world = helperCalls(methodBody("void saveAdditional(CompoundTag tag)"));
        List<String> pickup = helperCalls(methodBody("public void saveToItem(ItemStack stack)"));

        List<String> extra = new ArrayList<>(pickup);
        extra.removeAll(world);
        assertTrue("saveToItem 写了 saveAdditional 不写的状态（load 不会读回，属死数据）：\n  "
                        + String.join("\n  ", extra), extra.isEmpty());
    }

    /**
     * 守门：load 会读的助手，saveToItem 也必须写。
     * 这条比前两条更强——即使将来 saveAdditional 也漏了某个助手，这里仍会报警。
     */
    @Test
    public void everyHelperTheLoaderReadsIsAlsoWrittenByBothSavers() throws IOException {
        // 读侧有调用的助手类别（AE2Compat / PlacerPersist）都必须有对应的写侧调用
        List<String> readKinds = new ArrayList<>();
        for (String r : helperCalls(methodBody("public void load(CompoundTag tag)"))) {
            String kind = r.split("\\.")[0];
            if (!readKinds.contains(kind)) readKinds.add(kind);
        }
        List<String> world = saveSideCalls(methodBody("void saveAdditional(CompoundTag tag)"));
        List<String> pickup = saveSideCalls(methodBody("public void saveToItem(ItemStack stack)"));

        List<String> missing = new ArrayList<>();
        for (String kind : readKinds) {
            boolean worldHas = world.stream().anyMatch(c -> c.startsWith(kind + "."));
            boolean pickupHas = pickup.stream().anyMatch(c -> c.startsWith(kind + "."));
            if (!worldHas) missing.add("saveAdditional 缺 " + kind + " 的写侧调用");
            if (!pickupHas) missing.add("saveToItem 缺 " + kind + " 的写侧调用");
        }
        assertTrue("load 会读但存的时候没写，状态在存档往返中蒸发：\n  " + String.join("\n  ", missing),
                missing.isEmpty());
    }
}
