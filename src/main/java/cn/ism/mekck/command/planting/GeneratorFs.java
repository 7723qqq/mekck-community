package cn.ism.mekck.command.planting;

import cn.ism.mekck.command.PlantingRecipeGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 种植配方生成器的<b>文件系统侧</b>：清旧目录、旧配置迁移、黑名单装载。
 *
 * <h3>为什么这条边界在「碰磁盘的杂务」与「写配方内容」之间</h3>
 * 这三件事都<b>与配方内容无关</b>：它们决定「哪些旧文件该删」「老存档的
 * JSON 设置怎么搬进 toml」「哪些种子根本不参与生成」，产出的要么是目录状态、
 * 要么是一个种子集合，从不写配方 JSON。把它们与 {@link RecipeJsonWriter}
 * （真正拼 JSON）分开，是为了让「删除逻辑」单独成文 —— 这里过去出过一次
 * 真实缺陷（递归删掉玩家可见的 {@code world/datapacks/mekck_planting}），
 * 删除策略值得有一处专门的落点，而不是埋在两千行的生成流程里。
 *
 * <h3>搬运规则</h3>
 * 本类<b>不持有任何自己的状态</b>：日志走 {@link PlantingRecipeGenerator#LOGGER}
 * （同一个 logger 实例，来源名与拆分前一致），序列化走
 * {@link PlantingRecipeGenerator#GSON}，所有状态都由参数（{@code server} / {@code dir}）
 * 传入或就地读配置。因此搬运不改变任何读写时序，也不会改变任何日志文本。
 */
public final class GeneratorFs {

   private GeneratorFs() {
   }

   /**
    * 递归删除一个目录（不存在即空操作）。<b>只允许对生成器自己独占、且只由生成器写入的
    * 配方子目录调用</b>，绝不可对 datapack 根调用 —— datapack 根在玩家存档里可见可编辑。
    */
   public static void deleteDirectoryRecursively(Path dir) throws IOException {
      if (Files.exists(dir)) {
         // try-with-resources：Files.walk 返回的 Stream 持有打开的目录句柄，
         // 未关闭时在 Windows 上会把整棵已删目录锁住，后续重建/覆盖静默失败。
         try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
               try {
                  Files.deleteIfExists(path);
               } catch (IOException var2) {
                  PlantingRecipeGenerator.LOGGER.error("Failed to delete: {}", path, var2);
               }
            });
         }
      }
   }

   /**
    * 清空一个由本生成器独占的配方目录（不存在即空操作，失败只记日志不中断生成）。
    *
    * <p>用途：可选依赖卸载后，上一轮写进存档 datapack 的配方文件仍然留在那里，
    * 而它们引用的 recipe type 已经不存在 ⇒ 每次启动都继续刷解析错误。
    * 仅对 {@link PlantingRecipeGenerator#generate} 顶部那 4 个「只由本方法创建并写入」的目录调用。</p>
    */
   public static void purgeDirectory(Path dir, String label) {
      if (!Files.exists(dir)) {
         return;
      }
      try {
         deleteDirectoryRecursively(dir);
         PlantingRecipeGenerator.LOGGER.info("Purged stale generated recipes: {}", label);
      } catch (IOException e) {
         PlantingRecipeGenerator.LOGGER.error("Failed to purge stale generated recipes at {}; will overwrite in place", dir, e);
      }
   }

   /**
    * 把两个旧的独立 JSON 配置迁移进 mekck-common.toml，随后删除旧文件。
    * <p>背景：2026-09-16 起配置合并为单文件 mekck-common.toml（此前是 1 个 TOML + 2 个 JSON）。
    * 本方法保证老存档/老整合包的既有设置不丢失：先把 JSON 内容写进配置项，确认写入后再删文件；
    * 任何一步失败都保留原文件并告警，绝不静默丢配置。</p>
    * <p>两个调用点都会调它；文件删掉后即为空操作，因此只会真正迁移一次。</p>
    */
   public static void migrateLegacyPlantingConfigs(MinecraftServer server) {
      Path configDir = server.getServerDirectory().toPath().resolve("config/mekck");
      Path blacklistFile = configDir.resolve("planting_blacklist.json");
      Path debugFile = configDir.resolve("planting_debug.json");
      boolean migratedAny = false;

      // ① 黑名单 -> [planting] blacklist
      if (Files.exists(blacklistFile)) {
         try {
            JsonObject json = (JsonObject)PlantingRecipeGenerator.GSON.fromJson(stripJsonComments(Files.readString(blacklistFile)), JsonObject.class);
            JsonArray arr = json != null && json.has("blacklist") ? json.getAsJsonArray("blacklist") : null;
            if (arr != null && arr.size() > 0) {
               List<String> values = new ArrayList<>();
               for (int i = 0; i < arr.size(); i++) {
                  String s = arr.get(i).getAsString();
                  if (s != null && !s.isEmpty()) {
                     values.add(s);
                  }
               }
               cn.ism.mekck.config.MekckConfig.setPlantingBlacklist(values);
               migratedAny = true;
               PlantingRecipeGenerator.LOGGER.info("Migrated {} entries from planting_blacklist.json into mekck-common.toml [planting] blacklist", values.size());
            }
            Files.delete(blacklistFile);
            PlantingRecipeGenerator.LOGGER.info("Removed legacy config file {} (now in mekck-common.toml)", blacklistFile);
         } catch (Exception e) {
            PlantingRecipeGenerator.LOGGER.warn("Could not migrate planting_blacklist.json; the file was KEPT as-is", e);
         }
      }

      // ② 调试开关 -> [planting] print_planting_debug_to_chat
      if (Files.exists(debugFile)) {
         try {
            JsonObject json = (JsonObject)PlantingRecipeGenerator.GSON.fromJson(stripJsonComments(Files.readString(debugFile)), JsonObject.class);
            if (json != null && json.has("enabled")) {
               boolean enabled = json.get("enabled").getAsBoolean();
               cn.ism.mekck.config.MekckConfig.setPlantingDebugToChat(enabled);
               migratedAny = true;
               PlantingRecipeGenerator.LOGGER.info("Migrated print_planting_debug_to_chat={} from planting_debug.json", enabled);
            }
            Files.delete(debugFile);
            PlantingRecipeGenerator.LOGGER.info("Removed legacy config file {} (now in mekck-common.toml)", debugFile);
         } catch (Exception e) {
            PlantingRecipeGenerator.LOGGER.warn("Could not migrate planting_debug.json; the file was KEPT as-is", e);
         }
      }

      // 显式落盘：确保迁移结果写进 mekck-common.toml，玩家重启后仍生效
      if (migratedAny) {
         cn.ism.mekck.config.MekckConfig.save();
      }
   }

   /**
    * 从 JSON 内容中移除 # 开头的注释行（配置文件允许使用 # 中文说明注释）。
    */
   private static String stripJsonComments(String json) {
      StringBuilder sb = new StringBuilder(json.length() + 16);
      for (String line : json.split("\n", -1)) {
         if (!line.trim().startsWith("#")) {
            sb.append(line).append('\n');
         }
      }
      return sb.toString();
   }

   public static Set<Item> loadBlacklist(MinecraftServer server) {
      Set<Item> blacklist = new HashSet<>();
      // 2026-09-16：黑名单已并入 mekck-common.toml 的 [planting] blacklist，不再读写独立 JSON。
      // 旧的 planting_blacklist.json 若仍存在，先迁移其内容进配置、再把文件删除。
      migrateLegacyPlantingConfigs(server);
      List<? extends String> configured = cn.ism.mekck.config.MekckConfig.getPlantingBlacklist();
      if (configured == null || configured.isEmpty()) {
         PlantingRecipeGenerator.LOGGER.info("Planting blacklist is empty; no items will be excluded.");
      }
      if (configured != null) {
         for (String itemId : configured) {
            if (itemId == null || itemId.isEmpty()) {
               continue;
            }
            try {
               ResourceLocation id = new ResourceLocation(itemId);
               Item item = (Item)ForgeRegistries.ITEMS.getValue(id);
               if (item != null) {
                  blacklist.add(item);
               } else {
                  PlantingRecipeGenerator.LOGGER.warn("Blacklist contains unknown item: {}", itemId);
               }
            } catch (Exception e) {
               PlantingRecipeGenerator.LOGGER.warn("Blacklist contains invalid item ID: {}", itemId);
            }
         }
      }
      PlantingRecipeGenerator.LOGGER.info("Loaded {} blacklisted plant items from mekck-common.toml", blacklist.size());
      return blacklist;
   }
}
