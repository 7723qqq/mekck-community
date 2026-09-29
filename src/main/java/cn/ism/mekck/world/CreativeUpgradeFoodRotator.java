package cn.ism.mekck.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 创造升级「49 种随机食物」——<b>整局固定</b>的随机化与落盘（施工单方案 A′，2026-09-14 用户裁定）。
 *
 * <h2>机制</h2>
 * <ul>
 *   <li>静态配方 {@code data/mekck/recipes/creative_upgrade_from_49_foods.json} 引用
 *       {@code mekck:cuf_01 … cuf_49} 共 49 个<b>互不相同</b>的单元素 tag；</li>
 *   <li>本类在<b>每次服务器启动</b>时重写这 49 个 tag 的内容 ⇒ 配方本体永不改动；</li>
 *   <li>tag 文件写在存档级数据包 {@code world/datapacks/mekck_creative_upgrade/} 下，
 *       ⇒ <b>所有玩家同一套</b>，且 {@code /reload}、退出重进存档都<b>不会重新随机</b>（只有重启服务器才变）。</li>
 * </ul>
 *
 * <h2>为什么必须是 49 个独立 tag</h2>
 * 已实证 {@code ShapelessTableCraftingRecipe#matches} 的语义是
 * "非空槽数 == ingredient 数，然后做<b>配对匹配</b>"，而配对匹配<b>不要求 49 个堆彼此不同</b>。
 * 所以若写成"同一个 tag × 49"，玩家用<b>一种</b>食物放满 49 格就能满足 ⇒ 玩法失效。
 * 必须让 49 个 ingredient 互不相同，才能强制玩家凑齐 <b>49 种不同食物</b>。
 *
 * <h2>N 的动态选择（施工单第十二节）</h2>
 * <pre>
 *   poolSize &gt; 147  → N = 3      （每格三选一）
 *   poolSize ≥  98  → N = 2      （每格二选一，默认）
 *   poolSize ≥  49  → N = 1      （每格唯一，等价原 A′）
 *   否则            → 记 error，<b>不写任何文件</b>，保留上次的 tag
 * </pre>
 * 容错只落在数据里（每个 tag 多放几个备选），<b>配方结构完全不变</b>；
 * 49 组内容两两不相交 ⇒ "必须 49 种不同"这一硬要求仍然成立。
 *
 * <h2>硬约束</h2>
 * <b>任何情况下都不得写入空 tag 或少项 tag</b> —— 空 tag 会让配方退化成"任意 49 个物品"。
 * 失败时一律保留旧文件。
 */
public final class CreativeUpgradeFoodRotator {

    public static final Logger LOGGER = LoggerFactory.getLogger(CreativeUpgradeFoodRotator.class);

    /** 存档级数据包目录名。 */
    private static final String DATAPACK_NAME = "mekck_creative_upgrade";
    /** 1.20.1 的 pack_format。写错 ⇒ 数据包被拒 ⇒ 配方永不匹配（且不会有明显报错）。 */
    private static final int PACK_FORMAT = 15;
    /** tag 文件名的统一前缀（用于清理历史残留）。 */
    private static final String TAG_PREFIX = "cuf_";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 本局选中的 49 组（每组 N 个），供登录提示等使用；未成功时为 null。 */
    private static volatile List<List<Item>> currentPicks = null;

    private CreativeUpgradeFoodRotator() {
    }

    public static List<List<Item>> currentPicks() {
        return currentPicks;
    }

    // ================== 模式 A/B 支持（2026-09-18 研究线实施单） ==================

    /** 当前模式对应的聊天提示键（模式 A / B 文案不同）。 */
    public static String chatHintKey() {
        return cn.ism.mekck.config.MekckConfig.isCreativeUpgradePerRestart()
                ? "message.mekck.creative_upgrade_foods_hint"
                : "message.mekck.creative_upgrade_foods_hint_per_world";
    }

    /**
     * 本存档是否**已有完整**的生成结果：目录存在 + {@code cuf_*.json} 恰好 49 个 + {@code pack.mcmeta} 存在。
     *
     * <p>刻意不只看"目录存在" —— 上次写入中途失败会留下残缺目录，那种情况应当**重新生成**而不是沿用。</p>
     */
    public static boolean hasCompleteResult(MinecraftServer server) {
        try {
            Path datapack = server.getWorldPath(LevelResource.ROOT).resolve("datapacks/" + DATAPACK_NAME);
            if (!Files.isDirectory(datapack)) return false;
            if (!Files.isRegularFile(datapack.resolve("pack.mcmeta"))) return false;
            Path tagDir = datapack.resolve("data/mekck/tags/items");
            if (!Files.isDirectory(tagDir)) return false;
            int count = 0;
            try (var stream = Files.list(tagDir)) {
                for (Path p : stream.toList()) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(TAG_PREFIX) && name.endsWith(".json")) count++;
                }
            }
            return count == CreativeUpgradeFoodPool.TAG_COUNT;
        } catch (Throwable t) {
            LOGGER.warn("[创造升级] 检查存档内生成结果时出错，按未生成处理", t);
            return false;
        }
    }

    /**
     * 旧配置迁移：{@code [creative_upgrade] rotate_foods_on_startup = true/false}
     * → {@code food_rotation_mode = per_restart/per_world}。
     *
     * <p>旧键已从配置 spec 中移除（不会再被写回），所以这里直接读 toml 文本；
     * 只有当新键仍是默认 {@code per_world} 时才迁移，避免覆盖玩家自己改过的新值。迁移是幂等的：
     * 迁移后旧键行会被删掉，下次启动不会再触发。</p>
     *
     * <h3>⚠️ 关键：删旧键必须在 {@code SPEC.save()} <b>之后重新读一遍</b>（本轮修掉）</h3>
     * {@code MekckConfig.setCreativeUpgradeRotationMode} 内部会调 {@code SPEC.save()}，
     * 而它<b>重写并规范化整个 TOML</b>。原实现在调用它<b>之前</b>就把文件读成了
     * {@code text}，随后用这个<b>陈旧快照</b>算出的 {@code cleaned} 覆盖回去 ——
     * 于是把 Forge 刚写进去的一切（新增的键、规范化后的排版）<b>全部回滚</b>。
     *
     * <p>顺带两个细节：</p>
     * <ul>
     *   <li>{@code Files.writeString} 是<b>先截断再写</b>。这里抛 {@code IOException}
     *       的话，用户的配置文件就变成<b>空文件</b>了。所以改成「写临时文件 + 原子 move」，
     *       失败时原文件完好。</li>
     *   <li>{@code replaceAll("\\n{3,}", "\\n\\n")} 在 <b>CRLF</b> 文件上是<b>空操作</b>
     *       （{@code \r\n\r\n} 里没有 3 个连续的 {@code \n}）。原来的「压缩空行」在
     *       Windows 上一直没生效。改用 {@code (?m)^\s*\R(\s*\R)+} 这类按行匹配的写法。</li>
     * </ul>
     */
    private static void migrateLegacyRotationMode(MinecraftServer server) {
        try {
            Path toml = server.getServerDirectory().toPath().resolve("config/mekck/mekck-common.toml");
            if (!Files.isRegularFile(toml)) return;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\s*rotate_foods_on_startup\\s*=\\s*(true|false)\\s*$",
                            java.util.regex.Pattern.MULTILINE)
                    .matcher(Files.readString(toml));
            if (!m.find()) return;
            String legacyLine = m.group(0);
            boolean legacyRotate = Boolean.parseBoolean(m.group(1));
            String want = legacyRotate ? "per_restart" : "per_world";
            if (!"per_world".equals(cn.ism.mekck.config.MekckConfig.getCreativeUpgradeRotationMode())) {
                LOGGER.info("[创造升级] 旧配置 rotate_foods_on_startup={} 已存在且新键已非默认值，跳过迁移。", legacyRotate);
                return;
            }
            // 这一步内部会 SPEC.save()，整个 toml 被重写并规范化。
            cn.ism.mekck.config.MekckConfig.setCreativeUpgradeRotationMode(want);

            // ⚠️ 必须**重新读**：上面那次 save 已经把文件换了内容，
            // 拿 save 之前的快照去改会把 Forge 的写入全部抹掉。
            String fresh = Files.readString(toml);
            if (!fresh.contains(legacyLine.trim())) {
                // 旧键已不在（可能被 spec 重写顺带清掉，或本次刚被规范化掉）——视为迁移完成。
                LOGGER.info("[创造升级] 旧配置迁移：rotate_foods_on_startup={} → food_rotation_mode={}", legacyRotate, want);
                return;
            }
            String cleaned = fresh
                    .replace(legacyLine, "")
                    // 删掉整行（含其行尾），而不是只删那一行的内容
                    .replaceAll("(?m)^[ \\t]*\\R", "")
                    .replaceAll("(?m)([ \\t]*\\R)([ \\t]*\\R)+", "$1");
            writeAtomically(toml, cleaned);
            LOGGER.info("[创造升级] 旧配置迁移：rotate_foods_on_startup={} → food_rotation_mode={}", legacyRotate, want);
        } catch (Throwable t) {
            LOGGER.warn("[创造升级] 旧配置迁移失败（不影响启动，按新配置默认值继续）", t);
        }
    }

    /**
     * 原子写：先写同目录下的临时文件，再 {@code ATOMIC_MOVE} 覆盖。
     *
     * <p>直接 {@code Files.writeString} 是<b>先截断再写</b>：一旦中途抛
     * {@code IOException}（磁盘满、文件被占用、杀毒软件锁），目标文件就只剩空内容 ——
     * 对玩家来说就是<b>整个配置文件没了</b>。临时文件 + 原子改名保证要么全写成功、
     * 要么原封不动。</p>
     */
    private static void writeAtomically(Path target, String content) throws java.io.IOException {
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, java.nio.charset.StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 跨卷 / 文件系统不支持原子改名：退化成普通替换（仍有临时文件做缓冲，
                // 比直接截断目标安全）。
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 执行一次随机化并落盘。
     *
     * @return true = 已写入新数据（调用方应执行 {@code /reload} 让其立即生效）；
     *         false = 未改动任何文件（池子不足或写盘失败）
     */
    public static boolean rotate(MinecraftServer server) {
        if (!CreativeUpgradeFoodPool.enabled()) {
            LOGGER.info("[创造升级] 食物随机化已在配置中关闭，跳过。");
            return false;
        }

        // ① 旧配置迁移：rotate_foods_on_startup(bool) → food_rotation_mode(per_world/per_restart)
        migrateLegacyRotationMode(server);

        // ② per_world 模式：本存档**已有完整结果**（49 个 tag + pack.mcmeta）⇒ 沿用，不重写也不提示
        if (!cn.ism.mekck.config.MekckConfig.isCreativeUpgradePerRestart() && hasCompleteResult(server)) {
            LOGGER.info("[创造升级] 本存档已有完整结果，food_rotation_mode=per_world ⇒ 沿用旧结果，不重新随机。");
            return false;
        }

        CreativeUpgradeFoodPool.Pool pool = CreativeUpgradeFoodPool.build(server);
        int poolSize = pool.size();

        // ── N 的选取（施工单第十二节：唯一需要实现的分支） ──
        final int n;
        if (poolSize > 147) {
            n = 3;
        } else if (poolSize >= 98) {
            n = 2;
        } else if (poolSize >= 49) {
            n = 1;
        } else {
            LOGGER.error("[创造升级] 候选池仅 {} 项（< 49），本次<b>不随机</b>，保留上次的 49 个 tag。", poolSize);
            return false;
        }

        int need = CreativeUpgradeFoodPool.TAG_COUNT * n;
        LOGGER.info("[创造升级] 候选池 {} 项 ⇒ N = {}（每格 {} 选 1），本局需抽取 {} 个食物。",
                poolSize, n, n, need);

        // ── 抽签：洗牌取前 need 个，再顺序切成 49 组 ──
        List<Item> shuffled = new ArrayList<>(pool.items());
        Collections.shuffle(shuffled, new Random());
        List<Item> picked = new ArrayList<>(shuffled.subList(0, need));

        // ── 断言（施工单第十二节 A1~A3） ──
        if (!assertPicks(picked, n)) {
            LOGGER.error("[创造升级] 抽签结果未通过断言，本次不写文件、保留旧 tag。");
            return false;
        }

        // ── 写盘前复核 A4：每个食物仍满足 isEdible() 且有配方产出 ──
        Set<ResourceLocation> craftable = CreativeUpgradeFoodPool.craftableFor(server);
        for (Item item : picked) {
            if (!CreativeUpgradeFoodPool.verify(item, craftable)) {
                LOGGER.error("[创造升级] 复核失败：{} 不满足「可食用 ∩ 有配方」，本次不写文件。",
                        CreativeUpgradeFoodPool.describe(item));
                return false;
            }
        }

        // ── 切组：49 组，每组 n 个，组间互不相交（picked 已整体去重，顺序切分即为不相交） ──
        List<List<Item>> groups = new ArrayList<>();
        for (int g = 0; g < CreativeUpgradeFoodPool.TAG_COUNT; g++) {
            groups.add(List.copyOf(picked.subList(g * n, (g + 1) * n)));
        }

        // ── 落盘 ──
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            Path datapack = root.resolve("datapacks/" + DATAPACK_NAME);
            Path tagDir = datapack.resolve("data/mekck/tags/items");
            Files.createDirectories(tagDir);

            writePackMeta(datapack);
            for (int i = 0; i < groups.size(); i++) {
                writeTag(tagDir, String.format("%s%02d", TAG_PREFIX, i + 1), groups.get(i));
            }
            int removed = cleanStaleTags(tagDir);
            if (removed > 0) {
                LOGGER.info("[创造升级] 清理历史残留 tag 文件 {} 个。", removed);
            }
            writeCandidates(root, pool);

            currentPicks = List.copyOf(groups);
            logPicks(groups, pool);
            return true;
        } catch (Exception e) {
            LOGGER.error("[创造升级] 写盘失败，保留旧 tag（本次不生效）。", e);
            return false;
        }
    }

    // ────────────────────────────── 断言 ──────────────────────────────

    /** A1: 每组恰 n 项；A2: 全部 need 个 id 两两不同；A3: 组间无交集。 */
    private static boolean assertPicks(List<Item> picked, int n) {
        int need = CreativeUpgradeFoodPool.TAG_COUNT * n;
        if (picked.size() != need) {
            LOGGER.error("A0 失败：抽取数 {} != {}", picked.size(), need);
            return false;
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (Item item : picked) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null || !ids.add(id)) {
                LOGGER.error("A2 失败：出现重复或无效 id：{}", id);
                return false;
            }
        }
        // A2 通过（全局互不相同）⇒ 顺序切分后 A1 与 A3 必然成立
        return true;
    }

    // ────────────────────────────── 写文件 ──────────────────────────────

    private static void writePackMeta(Path datapack) throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", PACK_FORMAT);
        pack.addProperty("description", "MekCK: creative upgrade food rotation");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        Files.writeString(datapack.resolve("pack.mcmeta"), GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /** 写单个 tag：{@code {"replace": true, "values": [id...]}}。 */
    private static void writeTag(Path tagDir, String name, List<Item> items) throws IOException {
        JsonArray values = new JsonArray();
        for (Item item : items) {
            values.add(CreativeUpgradeFoodPool.describe(item));
        }
        JsonObject tag = new JsonObject();
        // replace=true：本局内容完全由我们决定，不受其它数据包同名 tag 的合并影响
        tag.addProperty("replace", true);
        tag.add("values", values);
        Files.writeString(tagDir.resolve(name + ".json"), GSON.toJson(tag), StandardCharsets.UTF_8);
    }

    /** 清理 {@code cuf_*} 里不在 01..49 范围内的历史残留。 */
    private static int cleanStaleTags(Path tagDir) throws IOException {
        Set<String> keep = new HashSet<>();
        for (int i = 1; i <= CreativeUpgradeFoodPool.TAG_COUNT; i++) {
            keep.add(String.format("%s%02d.json", TAG_PREFIX, i));
        }
        int removed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tagDir, TAG_PREFIX + "*.json")) {
            for (Path p : ds) {
                if (!keep.contains(p.getFileName().toString())) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * 落盘全部候选（供人工复核）。<b>刻意放在数据包之外</b>，
     * 否则会被当成资源文件加载、也可能被误用为 tag。
     */
    private static void writeCandidates(Path worldRoot, CreativeUpgradeFoodPool.Pool pool) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("pool_size", pool.size());
        JsonObject byNs = new JsonObject();
        pool.byNamespace().forEach(byNs::addProperty);
        root.add("by_namespace", byNs);
        JsonArray items = new JsonArray();
        for (Item item : pool.items()) {
            items.add(CreativeUpgradeFoodPool.describe(item));
        }
        root.add("candidates", items);
        Files.writeString(worldRoot.resolve("mekck_creative_upgrade_candidates.json"),
                GSON.toJson(root), StandardCharsets.UTF_8);
    }

    // ────────────────────────────── 日志 ──────────────────────────────

    private static void logPicks(List<List<Item>> groups, CreativeUpgradeFoodPool.Pool pool) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            sb.append("  cuf_").append(String.format("%02d", i + 1)).append(" = ");
            List<String> ids = new ArrayList<>();
            for (Item item : groups.get(i)) {
                ids.add(CreativeUpgradeFoodPool.describe(item));
            }
            sb.append(String.join(", ", ids)).append('\n');
        }
        LOGGER.info("[创造升级] 本局 49 组食物已生成（候选池 {} 项，按模组分布 {}）：\n{}",
                pool.size(), pool.byNamespace(), sb);
    }
}
