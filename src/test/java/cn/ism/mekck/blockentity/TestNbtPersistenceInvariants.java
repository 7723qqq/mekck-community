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
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * NBT 持久化「源码不变量」测试。
 *
 * <p><b>为什么用源码检查而不是行为测试</b>：{@code saveAdditional}/{@code load} 需要
 * {@code Level}/{@code BlockState}/物品注册表，普通 JVM 里起不来（与
 * {@code TestTavernBarrelPlan} 注释所述同一个限制）。但本项目真实发生过的缺陷恰好是
 * <b>纯结构性的</b>——17 台机器把 {@code MeOrderEnabled} 写在
 * {@code if (orderRecipeId != null)} 内、读取也嵌在同样的判断里，导致无订单时开关状态
 * 静默丢失。这类错误不需要运行游戏就能判出来，而且极易在复制粘贴新机器时复发。</p>
 *
 * <p>检查两件事，任一被破坏即失败：</p>
 * <ol>
 *   <li>{@code putBoolean("MeOrderEnabled", ...)} 不得位于 {@code orderRecipeId} 判空分支内部；</li>
 *   <li>{@code getBoolean("MeOrderEnabled")} 不得位于 {@code tag.contains("OrderRecipeId")} 分支内部。</li>
 * </ol>
 */
public class TestNbtPersistenceInvariants {

    private static final Path BLOCKENTITY_DIR =
            Path.of("src", "main", "java", "cn", "ism", "mekck", "blockentity");

    /** 写入侧：进入 if 体的判空条件。 */
    private static final Pattern SAVE_GUARD =
            Pattern.compile("if \\(orderRecipeId != null\\) \\{");

    /** 读取侧：进入 if 体的存在性条件。 */
    private static final Pattern LOAD_GUARD =
            Pattern.compile("if \\(tag\\.contains\\(\"OrderRecipeId\"\\)\\) \\{");

    private static final String SAVE_TARGET = "putBoolean(\"MeOrderEnabled\"";
    private static final String LOAD_TARGET = "getBoolean(\"MeOrderEnabled\")";

    @Test
    public void meOrderEnabledIsNotNestedInsideOrderGuard() throws IOException {
        assertNoNesting(BLOCKENTITY_DIR, SAVE_GUARD, SAVE_TARGET,
                "MeOrderEnabled 的写入被放进了 orderRecipeId 判空分支："
                        + "无订单时该键不会写出，重载后静默复位为默认 true");
        assertNoNesting(BLOCKENTITY_DIR, LOAD_GUARD, LOAD_TARGET,
                "MeOrderEnabled 的读取被放进了 OrderRecipeId 存在性分支："
                        + "无订单时不会读回该键，玩家关闭的开关会被重新打开");
    }

    /**
     * 扫描目录下所有 .java，确认 {@code target} 不出现在 {@code guard} 打开的代码块内部。
     *
     * <p>用花括号深度追踪而非正则：目标行所在的 {@code depth} 若 ≥ 进入 guard 体后的深度，
     * 即说明它被包在该分支里。</p>
     */
    private static void assertNoNesting(Path dir, Pattern guard, String target, String why)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            fail("找不到源码目录（测试需在项目根目录运行）：" + dir.toAbsolutePath());
        }

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<Path> single = List.of(file);
                offenders.addAll(scan(single, guard, target));
            }
        }

        if (!offenders.isEmpty()) {
            fail(why + "\n违规位置：\n  " + String.join("\n  ", offenders));
        }
    }

    private static List<String> scan(List<Path> files, Pattern guard, String target)
            throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = source.split("\n", -1);

            int depth = 0;
            int guardDepth = -1;
            for (int i = 0; i < lines.length; i++) {
                String trimmed = lines[i].trim();
                if (guardDepth < 0 && guard.matcher(trimmed).find()) {
                    guardDepth = depth + 1;
                }
                if (trimmed.contains(target) && guardDepth >= 0 && depth >= guardDepth) {
                    offenders.add(file.getFileName() + ":" + (i + 1));
                }
                depth += count(lines[i], '{') - count(lines[i], '}');
                if (guardDepth >= 0 && depth < guardDepth) {
                    guardDepth = -1;
                }
            }
        }
        return offenders;
    }

    private static int count(String haystack, char needle) {
        int n = 0;
        for (int i = 0; i < haystack.length(); i++) {
            if (haystack.charAt(i) == needle) n++;
        }
        return n;
    }

    @Test
    public void everyMachineThatSavesMeOrderEnabledAlsoReadsIt() throws IOException {
        List<String> savesWithoutLoads = new ArrayList<>();
        try (Stream<Path> files = Files.walk(BLOCKENTITY_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                boolean writes = source.contains("putBoolean(\"MeOrderEnabled\"");
                boolean reads = source.contains("getBoolean(\"MeOrderEnabled\")");
                if (writes != reads) {
                    savesWithoutLoads.add(file.getFileName() + " (写=" + writes + " 读=" + reads + ")");
                }
            }
        }
        assertTrue("MeOrderEnabled 的读写必须成对，否则状态在存档往返中丢失：\n  "
                        + String.join("\n  ", savesWithoutLoads),
                savesWithoutLoads.isEmpty());
    }

    @Test
    public void noDuplicateKeysWithinASinglePersistenceMethod() throws IOException {
        // 同一方法里对同一 NBT 键写两次 ⇒ 后者覆盖前者，通常是复制粘贴时漏改。
        // 注意必须**按方法分组**：saveAdditional（存世界）与 saveToItem（存物品）
        // 各自写同一批键是正常的，跨方法看会得到大量假阳性。
        List<String> duplicates = new ArrayList<>();
        Pattern putKey = Pattern.compile("tag\\.put[A-Za-z]*\\(\"([A-Za-z_]+)\"");
        Pattern methodSig = Pattern.compile(
                "(?:public|protected|private)\\s+[\\w<>\\[\\],\\s]*\\s+(\\w+)\\s*\\(");

        try (Stream<Path> files = Files.walk(BLOCKENTITY_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
                String current = "<init>";
                int depth = 0;
                java.util.Map<String, List<String>> byMethod = new java.util.LinkedHashMap<>();

                for (String line : lines) {
                    String s = line.trim();
                    Matcher sig = methodSig.matcher(s);
                    if (sig.find() && !s.endsWith(";")) {
                        current = sig.group(1);
                    }
                    Matcher k = putKey.matcher(line);
                    while (k.find()) {
                        byMethod.computeIfAbsent(current, x -> new ArrayList<>()).add(k.group(1));
                    }
                    depth += count(line, '{') - count(line, '}');
                }

                for (var e : byMethod.entrySet()) {
                    List<String> seen = new ArrayList<>();
                    for (String key : e.getValue()) {
                        if (seen.contains(key)) {
                            duplicates.add(file.getFileName() + " " + e.getKey() + "() 键=" + key);
                        } else {
                            seen.add(key);
                        }
                    }
                }
            }
        }
        assertTrue("同一方法内对同一 NBT 键重复写入（后者覆盖前者，通常是漏改复制来源）：\n  "
                        + String.join("\n  ", duplicates),
                duplicates.isEmpty());
    }

    @Test
    public void saveAdditionalAndSaveToItemWriteTheSameReadbackKeys() throws IOException {
        // saveAdditional 决定「存档在世界里」，saveToItem 决定「存进方块物品的 BlockEntityTag」。
        // 两者若对**会被读回**的键不一致 ⇒ 挖下来的机器与留在世界的机器状态不同
        //   （例如物品里少了流体罐内容、少了 ME 开关）。
        //
        // 只比较「load 会读的键」：像 MachineKind 那种写而不读的死键不参与，
        // 否则会把无害的 write-only 字段误报成缺陷。
        List<String> mismatched = new ArrayList<>();
        Pattern putKey = Pattern.compile("tag\\.put[A-Za-z]*\\(\"([A-Za-z_]+)\"");
        Pattern getKey = Pattern.compile("tag\\.(?:get[A-Za-z]*|contains)\\(\"([A-Za-z_]+)\"");
        Pattern methodSig = Pattern.compile(
                "(?:public|protected|private)\\s+[\\w<>\\[\\],\\s]*\\s+(\\w+)\\s*\\(");

        try (Stream<Path> files = Files.walk(BLOCKENTITY_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("saveToItem")) continue;

                java.util.Map<String, List<String>> puts = new java.util.LinkedHashMap<>();
                java.util.Set<String> reads = new java.util.HashSet<>();
                String current = "<init>";
                for (String line : text.split("\n", -1)) {
                    String s = line.trim();
                    Matcher sig = methodSig.matcher(s);
                    if (sig.find() && !s.endsWith(";")) current = sig.group(1);
                    Matcher pk = putKey.matcher(line);
                    while (pk.find()) puts.computeIfAbsent(current, x -> new ArrayList<>()).add(pk.group(1));
                    Matcher gk = getKey.matcher(line);
                    while (gk.find()) reads.add(gk.group(1));
                }

                List<String> save = puts.get("saveAdditional");
                List<String> item = puts.get("saveToItem");
                if (save == null || item == null) continue;

                List<String> onlyWorld = new ArrayList<>(save);
                onlyWorld.removeAll(item);
                onlyWorld.retainAll(reads);          // 只关心会被读回的键
                List<String> onlyItem = new ArrayList<>(item);
                onlyItem.removeAll(save);
                onlyItem.retainAll(reads);

                if (!onlyWorld.isEmpty() || !onlyItem.isEmpty()) {
                    mismatched.add(file.getFileName()
                            + "  仅saveAdditional=" + onlyWorld + "  仅saveToItem=" + onlyItem);
                }
            }
        }
        assertTrue("saveAdditional 与 saveToItem 对「会被读回的键」不一致"
                        + "（挖下后状态会与留在世界时不同）：\n  "
                        + String.join("\n  ", mismatched),
                mismatched.isEmpty());
    }
}
