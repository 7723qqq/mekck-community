package cn.ism.mekck.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.ClickEvent.Action;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;

public final class PlantingRecipeGenerator {
   public static final Logger LOGGER = LoggerFactory.getLogger(PlantingRecipeGenerator.class);
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

   /** BotanyPots 作物配方类型 ID 与生成时使用的默认生长时间（tick）。 */
   private static final ResourceLocation BOTANY_CROP_TYPE_ID = new ResourceLocation("botanypots", "crop");
   private static final int BOTANY_GROWTH_TICKS = 1200;

   /** BotanyPots 作物配方的一次掉落条目（运行时反射解析，无编译依赖）。 */
   private record BotanyDrop(Item item, float chance, int minRolls, int maxRolls) { }

   /** BotanyPots 作物配方（种子输入 + 掉落条目列表 + 该种子要求的土壤 categories）。 */
   private record BotanyCropData(Item seed, List<BotanyDrop> drops, java.util.Set<String> soilCategories) { }

   /** 种子 → 要求的土壤 categories（生成期扫 botanypots:crop 得到）。 */
   private static final Map<Item, java.util.Set<String>> seedSoilCategories = new LinkedHashMap<>();

   /** 土壤物品 → 提供的 categories（生成期扫 botanypots:soil 得到；高级土壤会累加低级 category）。 */
   private static final Map<Item, java.util.Set<String>> soilCategoriesByItem = new LinkedHashMap<>();

   /** 生长方块格只对神秘农业种子生效（用户 2026-09-17 决定：其余模组的种植配方无视该格）。 */
   private static final String REQUIRES_SOIL_NAMESPACE = "mysticalagriculture";

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
   private static final List<ResourceLocation> debugGeneratedSeeds = new ArrayList<>();
   private static final List<ResourceLocation> debugFailedLootTable = new ArrayList<>();
   private static final List<ResourceLocation> debugNoProducts = new ArrayList<>();

   public static boolean generate(MinecraftServer server) {
      debugGeneratedSeeds.clear();
      debugFailedLootTable.clear();
      debugNoProducts.clear();
      Path datapackDir = server.getWorldPath(LevelResource.ROOT).resolve("datapacks/mekck_planting");
      Path recipeDir = datapackDir.resolve("data/mekmm/recipes/planting");
      Path clocheRecipeDir = datapackDir.resolve("data/mekck/recipes/plant_ie");
      Path plantcutRecipeDir = datapackDir.resolve("data/mekck/recipes/plantcut");
      Path botanyPotsRecipeDir = datapackDir.resolve("data/botanypots/recipes");
      LOGGER.info("PlantingRecipeGenerator.generate() called. Server: {}", server);

      // 注：此处原先有一段调试插桩，每次服务器启动都往**世界存档根目录**写
      // planting_generator_test.txt 并打两条 INFO 日志。它既污染玩家存档（与本功能
      // 生成的 datapack 混在一起），又是同步磁盘写 + 无条件日志。已删除。

      // 清旧内容：只清本方法自己写的那 4 个配方目录，不动 datapack 根。
      //
      // 为什么不是「递归删掉整个 datapacks/mekck_planting」（原实现）：那个目录在玩家的
      // 世界存档里是**可见且可编辑的**（就在 saves/<世界>/datapacks/ 下），玩家完全可能
      // 往里丢自己的补充配方或说明文件。整棵删掉 = 每次启动静默清空他们的东西，没有任何提示。
      //
      // 为什么这 4 个目录可以整棵删：它们全部由本方法在下面 createDirectories 创建、
      // 且只由本方法写入（逐文件 Files.writeString），不存在外部内容。
      // pack.mcmeta 本来每次也会重写，删不删无差别，所以不删。
      for (Path owned : new Path[]{recipeDir, clocheRecipeDir, plantcutRecipeDir, botanyPotsRecipeDir}) {
         if (Files.exists(owned)) {
            try {
               deleteDirectoryRecursively(owned);
            } catch (IOException e) {
               LOGGER.error("Failed to delete stale generated recipes at {}; will overwrite in place", owned, e);
            }
         }
      }

      Map<Item, Block> seedCropMap = new LinkedHashMap<>();

      for (Item item : ForgeRegistries.ITEMS.getValues()) {
         if (item instanceof BlockItem) {
            BlockItem blockItem = (BlockItem)item;
            Block block = blockItem.getBlock();
            if (block instanceof CropBlock) {
               seedCropMap.putIfAbsent(item, block);
            }
         }
      }

      List<Block> nonCropGrowableBlocks = new ArrayList<>();

      for (Block block : ForgeRegistries.BLOCKS.getValues()) {
         if (!(block instanceof CropBlock)) {
            for (Property<?> prop : block.getStateDefinition().getProperties()) {
               if (prop.getName().equals("age") && prop instanceof IntegerProperty) {
                  nonCropGrowableBlocks.add(block);
                  break;
               }
            }
         }
      }

      LOGGER.info("Found {} CropBlock seeds and {} non-CropBlock growable blocks for recipe generation.", seedCropMap.size(), nonCropGrowableBlocks.size());
      if (seedCropMap.isEmpty() && nonCropGrowableBlocks.isEmpty()) {
         LOGGER.info("No growable blocks found, skipping.");
         return false;
      } else {
         Set<Item> existingRecipeSeeds = collectExistingRecipeSeeds(server);
         LOGGER.info("Found {} seeds with existing recipes from other mods, will skip them.", existingRecipeSeeds.size());
         Set<Item> blacklist = loadBlacklist(server);
         LOGGER.info("Loaded {} blacklisted seeds from config, will skip them.", blacklist.size());
         existingRecipeSeeds.addAll(blacklist);

         try {
            Files.createDirectories(recipeDir);
            Files.createDirectories(clocheRecipeDir);
            Files.createDirectories(plantcutRecipeDir);
            Files.createDirectories(botanyPotsRecipeDir);
         } catch (IOException var21) {
            LOGGER.error("Failed to create recipe directories", var21);
            return false;
         }

         JsonObject packMeta = new JsonObject();
         JsonObject pack = new JsonObject();
         pack.addProperty("pack_format", 15);
         pack.addProperty("description", "Auto-generated planting recipes for Mekanism: Central Kitchen");
         packMeta.add("pack", pack);

         try {
            Files.writeString(datapackDir.resolve("pack.mcmeta"), GSON.toJson(packMeta));
         } catch (IOException var20) {
            LOGGER.error("Failed to create pack.mcmeta", var20);
            return false;
         }

         // BotanyPots 作物配方收集：作为「无种植配方 → 有 BP 配方 → 转换」优先级中的第二级
         boolean botanyPotsInstalled = isBotanyPotsInstalled();
         Map<Item, BotanyCropData> botanyCrops = collectBotanyPotsCropRecipes(server);
         // 土壤 categories（生长方块格判定）：必须在写任何 plantcut 配方之前收集好
         collectBotanyPotsSoilRecipes(server);
         int existingCount = generatePlantCutFromExisting(plantcutRecipeDir, server);
         LOGGER.info("Generated {} mekck:plantcut recipes from existing mekmm:planting recipes.", existingCount);
         int count = 0;
         int skipped = 0;

         for (Entry<Item, Block> entry : seedCropMap.entrySet()) {
            if (existingRecipeSeeds.contains(entry.getKey())) {
               LOGGER.debug("Seed {} already has a recipe from another mod, skipping.", ForgeRegistries.ITEMS.getKey(entry.getKey()));
               skipped++;
            } else {
               try {
                  BotanyCropData botanyData = botanyCrops.get(entry.getKey());
                  boolean generated = false;
                  if (botanyData != null) {
                     // 优先级 2：有 BotanyPots 配方 → 转换（输入=BP 输入，输出×2=我们的输出）
                     generated = generateFromBotanyPots(entry.getKey(), botanyData, recipeDir, clocheRecipeDir,
                             plantcutRecipeDir, server, ForgeRegistries.BLOCKS.getKey(entry.getValue()));
                  }
                  if (!generated) {
                     // 优先级 3：无种植配方且无 BP 配方 → 依据战利品表生成（同时为 BotanyPots 生成植物盆配方）
                     generated = generateRecipe(entry.getKey(), entry.getValue(), recipeDir, clocheRecipeDir,
                             plantcutRecipeDir, botanyPotsRecipeDir, botanyPotsInstalled, server);
                  }
                  if (generated) {
                     count++;
                  } else {
                     skipped++;
                  }
               } catch (Exception var19) {
                  LOGGER.error("Failed to generate recipe for seed: {}", ForgeRegistries.ITEMS.getKey(entry.getKey()), var19);
                  skipped++;
               }
            }
         }

         Set<Item> processedSeeds = new HashSet<>(seedCropMap.keySet());
         processedSeeds.addAll(existingRecipeSeeds);

         for (Block blockx : nonCropGrowableBlocks) {
            try {
               if (generateRecipeForNonCrop(blockx, recipeDir, clocheRecipeDir, plantcutRecipeDir, botanyPotsRecipeDir,
                       botanyPotsInstalled, botanyCrops, server, processedSeeds)) {
                  count++;
               } else {
                  skipped++;
               }
            } catch (Exception var18) {
               LOGGER.error("Failed to generate recipe for non-crop block: {}", ForgeRegistries.BLOCKS.getKey(blockx), var18);
               skipped++;
            }
         }

         LOGGER.info("Generation complete: generated {} recipes, skipped {} recipes", count, skipped);
         return count > 0 || existingCount > 0;
      }
   }

   private static void deleteDirectoryRecursively(Path dir) throws IOException {
      if (Files.exists(dir)) {
         Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
               Files.deleteIfExists(path);
            } catch (IOException var2) {
               LOGGER.error("Failed to delete: {}", path, var2);
            }
         });
      }
   }

   private static Set<Item> collectExistingRecipeSeeds(MinecraftServer server) {
      Set<Item> seeds = new HashSet<>();
      Set<String> targetTypeNames = Set.of("mekmm:planting", "immersiveengineering:cloche");
      LOGGER.info("Searching all recipes for target recipe types using getRecipes()...");
      int totalRecipesChecked = 0;
      int totalRecipesFound = 0;

      for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
         totalRecipesChecked++;
         ResourceLocation typeId = ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType());
         String typeName = typeId != null ? typeId.toString() : recipe.getType().toString();
         if (targetTypeNames.contains(typeName)) {
            totalRecipesFound++;
            addSeedFromRecipe(recipe, seeds);
         }
      }

      LOGGER.info(
         "Checked {} total recipes, found {} target recipes, collected {} seeds: {}",
         new Object[]{totalRecipesChecked, totalRecipesFound, seeds.size(), seeds.stream().map(s -> ForgeRegistries.ITEMS.getKey(s)).toList()}
      );
      return seeds;
   }

   private static void addSeedFromRecipe(Recipe<?> recipe, Set<Item> seeds) {
      List<Ingredient> ingredients = recipe.getIngredients();
      if (!ingredients.isEmpty()) {
         ItemStack[] matchingStacks = ingredients.get(0).getItems();

         for (ItemStack stack : matchingStacks) {
            if (!stack.isEmpty()) {
               seeds.add(stack.getItem());
               break;
            }
         }
      }
   }

   /**
    * 把两个旧的独立 JSON 配置迁移进 mekck-common.toml，随后删除旧文件。
    * <p>背景：2026-09-16 起配置合并为单文件 mekck-common.toml（此前是 1 个 TOML + 2 个 JSON）。
    * 本方法保证老存档/老整合包的既有设置不丢失：先把 JSON 内容写进配置项，确认写入后再删文件；
    * 任何一步失败都保留原文件并告警，绝不静默丢配置。</p>
    * <p>两个调用点都会调它；文件删掉后即为空操作，因此只会真正迁移一次。</p>
    */
   private static void migrateLegacyPlantingConfigs(MinecraftServer server) {
      Path configDir = server.getServerDirectory().toPath().resolve("config/mekck");
      Path blacklistFile = configDir.resolve("planting_blacklist.json");
      Path debugFile = configDir.resolve("planting_debug.json");
      boolean migratedAny = false;

      // ① 黑名单 -> [planting] blacklist
      if (Files.exists(blacklistFile)) {
         try {
            JsonObject json = (JsonObject)GSON.fromJson(stripJsonComments(Files.readString(blacklistFile)), JsonObject.class);
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
               LOGGER.info("Migrated {} entries from planting_blacklist.json into mekck-common.toml [planting] blacklist", values.size());
            }
            Files.delete(blacklistFile);
            LOGGER.info("Removed legacy config file {} (now in mekck-common.toml)", blacklistFile);
         } catch (Exception e) {
            LOGGER.warn("Could not migrate planting_blacklist.json; the file was KEPT as-is", e);
         }
      }

      // ② 调试开关 -> [planting] print_planting_debug_to_chat
      if (Files.exists(debugFile)) {
         try {
            JsonObject json = (JsonObject)GSON.fromJson(stripJsonComments(Files.readString(debugFile)), JsonObject.class);
            if (json != null && json.has("enabled")) {
               boolean enabled = json.get("enabled").getAsBoolean();
               cn.ism.mekck.config.MekckConfig.setPlantingDebugToChat(enabled);
               migratedAny = true;
               LOGGER.info("Migrated print_planting_debug_to_chat={} from planting_debug.json", enabled);
            }
            Files.delete(debugFile);
            LOGGER.info("Removed legacy config file {} (now in mekck-common.toml)", debugFile);
         } catch (Exception e) {
            LOGGER.warn("Could not migrate planting_debug.json; the file was KEPT as-is", e);
         }
      }

      // 显式落盘：确保迁移结果写进 mekck-common.toml，玩家重启后仍生效
      if (migratedAny) {
         cn.ism.mekck.config.MekckConfig.save();
      }
   }

   private static Set<Item> loadBlacklist(MinecraftServer server) {
      Set<Item> blacklist = new HashSet<>();
      // 2026-09-16：黑名单已并入 mekck-common.toml 的 [planting] blacklist，不再读写独立 JSON。
      // 旧的 planting_blacklist.json 若仍存在，先迁移其内容进配置、再把文件删除。
      migrateLegacyPlantingConfigs(server);
      List<? extends String> configured = cn.ism.mekck.config.MekckConfig.getPlantingBlacklist();
      if (configured == null || configured.isEmpty()) {
         LOGGER.info("Planting blacklist is empty; no items will be excluded.");
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
                  LOGGER.warn("Blacklist contains unknown item: {}", itemId);
               }
            } catch (Exception e) {
               LOGGER.warn("Blacklist contains invalid item ID: {}", itemId);
            }
         }
      }
      LOGGER.info("Loaded {} blacklisted plant items from mekck-common.toml", blacklist.size());
      return blacklist;
   }

   private static Map<Item, Integer> filterOutSeedItems(Map<Item, Integer> items) {
      Map<Item, Integer> result = new LinkedHashMap<>();

      for (Entry<Item, Integer> entry : items.entrySet()) {
         ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.getKey());
         if (id != null && !id.getPath().toLowerCase().contains("seed")) {
            result.put(entry.getKey(), entry.getValue());
         }
      }

      return result;
   }

   private static boolean isDebugEnabled(MinecraftServer server) {
      // 2026-09-16：原为「TOML 开关 + 独立 planting_debug.json 开关」两道门，
      // 且两者默认值互相矛盾（TOML 默认 true、JSON 默认 false ⇒ 实际等于关）。
      // 现已合并为 mekck-common.toml 里唯一的一项 [planting] print_planting_debug_to_chat。
      migrateLegacyPlantingConfigs(server);
      return cn.ism.mekck.config.MekckConfig.getPlantingDebugToChat();
   }

   private static String getDisplayName(ResourceLocation rl) {
      Item item = (Item)ForgeRegistries.ITEMS.getValue(rl);
      if (item != null && item != Items.AIR) {
         return Component.translatable(item.getDescriptionId()).getString();
      } else {
         Block block = (Block)ForgeRegistries.BLOCKS.getValue(rl);
         return block != null && block != Blocks.AIR ? Component.translatable(block.getDescriptionId()).getString() : rl.toString();
      }
   }

   public static void sendDebugInfoToPlayer(ServerPlayer player) {
      if (isDebugEnabled(player.server)) {
         Path logDir = player.server.getServerDirectory().toPath().resolve("config/mekck");
         Path logFile = logDir.resolve("planting_generator_debug.log");

         try {
            Files.createDirectories(logDir);
            StringBuilder sb = new StringBuilder();
            sb.append("=== 种植配方生成调试信息 ===\n");
            sb.append("生成时间: ").append(LocalDateTime.now()).append("\n\n");
            sb.append("--- 已生成配方的种子 (").append(debugGeneratedSeeds.size()).append("个) ---\n");
            if (!debugGeneratedSeeds.isEmpty()) {
               for (ResourceLocation rl : debugGeneratedSeeds) {
                  sb.append("  - ").append(getDisplayName(rl)).append(" (").append(rl).append(")\n");
               }
            } else {
               sb.append("  (无)\n");
            }

            sb.append("\n");
            sb.append("--- 无法获取战利品表的种子 (").append(debugFailedLootTable.size()).append("个) ---\n");
            if (!debugFailedLootTable.isEmpty()) {
               for (ResourceLocation rl : debugFailedLootTable) {
                  sb.append("  - ").append(getDisplayName(rl)).append(" (").append(rl).append(")\n");
               }
            } else {
               sb.append("  (无)\n");
            }

            sb.append("\n");
            sb.append("--- 剔除种子后无产物的种子/方块 (").append(debugNoProducts.size()).append("个) ---\n");
            if (!debugNoProducts.isEmpty()) {
               for (ResourceLocation rl : debugNoProducts) {
                  sb.append("  - ").append(getDisplayName(rl)).append(" (").append(rl).append(")\n");
               }
            } else {
               sb.append("  (无)\n");
            }

            sb.append("\n");
            sb.append("=== 调试信息结束 ===\n");
            Files.writeString(logFile, sb.toString());
         } catch (IOException var6) {
            LOGGER.error("Failed to write planting debug log", var6);
         }

         String logPath = logFile.toAbsolutePath().toString();
         player.sendSystemMessage(Component.literal("§6=== 种植配方生成调试信息 ==="));
         player.sendSystemMessage(
            Component.literal("§a点击此处打开调试日志文件")
               .withStyle(
                  style -> style.withColor(ChatFormatting.GREEN)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(Action.OPEN_FILE, logPath))
                        .withHoverEvent(new HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("打开: " + logPath)))
               )
         );
         player.sendSystemMessage(Component.literal("§7文件路径: " + logPath));
         player.sendSystemMessage(
            Component.literal(
               "§6已生成配方: §a" + debugGeneratedSeeds.size() + " §6| 无法获取战利品表: §e" + debugFailedLootTable.size() + " §6| 无产物: §e" + debugNoProducts.size()
            )
         );
         player.sendSystemMessage(Component.literal("§6=== 调试信息结束 ==="));
         // 配置提示：告诉玩家这两项都可以在配置文件里关闭
         player.sendSystemMessage(
            Component.literal("§7以上信息可在配置文件 §fconfig/mekck/mekck-common.toml §7的 §f[planting] §7段关闭：")
               .append(Component.literal("\n§7  · §fauto_generate_planting_recipes §7= §ffalse §7→ 不再自动生成种植配方")
                  .withStyle(style -> style.withColor(ChatFormatting.GRAY)))
               .append(Component.literal("\n§7  · §fprint_planting_debug_to_chat §7= §ffalse §7→ 不再在聊天栏输出本条调试信息")
                  .withStyle(style -> style.withColor(ChatFormatting.GRAY)))
               .append(Component.literal("\n§7（修改后重启服务器生效；已有种植配方不受影响）")
                  .withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)))
         );
      }
   }

   private static boolean generateRecipe(Item seed, Block cropBlock, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir,
         Path botanyPotsRecipeDir, boolean botanyPotsInstalled, MinecraftServer server) throws IOException {
      ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(seed);
      if (seedId == null) {
         return false;
      } else {
         Map<Item, Integer> allItems = determineAllOutputItems(cropBlock, seed, server);
         if (allItems.isEmpty()) {
            LOGGER.warn("Could not determine output items from loot table for seed: {}, skipping.", seedId);
            debugFailedLootTable.add(seedId);
            return false;
         } else {
            Map<Item, Integer> filteredItems = filterOutSeedItems(allItems);
            if (filteredItems.isEmpty()) {
               LOGGER.warn("All items from loot table for seed {} were seeds, no products remain. Skipping.", seedId);
               debugNoProducts.add(seedId);
               return false;
            } else {
               Item mainOutputItem = null;
               int mainOutputCount = 0;

               for (Entry<Item, Integer> entry : filteredItems.entrySet()) {
                  if (!entry.getKey().equals(seed)) {
                     mainOutputItem = entry.getKey();
                     mainOutputCount = entry.getValue();
                     break;
                  }
               }

               if (mainOutputItem == null) {
                  Entry<Item, Integer> first = filteredItems.entrySet().iterator().next();
                  mainOutputItem = first.getKey();
                  mainOutputCount = first.getValue();
               }

               ResourceLocation mainOutputId = ForgeRegistries.ITEMS.getKey(mainOutputItem);
               if (mainOutputId == null) {
                  return false;
               } else {
                  int recipeOutputCount = Math.max(1, mainOutputCount * 2);
                  JsonObject jsonMekmm = new JsonObject();
                  jsonMekmm.addProperty("type", "mekmm:planting");
                  JsonObject itemInput = new JsonObject();
                  JsonObject ingredient = new JsonObject();
                  ingredient.addProperty("item", seedId.toString());
                  itemInput.add("ingredient", ingredient);
                  jsonMekmm.add("itemInput", itemInput);
                  JsonObject gasInput = new JsonObject();
                  gasInput.addProperty("amount", 1);
                  gasInput.addProperty("gas", "mekmm:nutrient_solution");
                  jsonMekmm.add("gasInput", gasInput);
                  JsonObject mainOutputJson = new JsonObject();
                  mainOutputJson.addProperty("count", recipeOutputCount);
                  mainOutputJson.addProperty("item", mainOutputId.toString());
                  jsonMekmm.add("mainOutput", mainOutputJson);
                  boolean hasSecondaryOutput = !seed.equals(mainOutputItem) && !seedId.getPath().toLowerCase().contains("seed");
                  if (hasSecondaryOutput) {
                     JsonObject secondaryOutput = new JsonObject();
                     secondaryOutput.addProperty("count", 1);
                     secondaryOutput.addProperty("item", seedId.toString());
                     jsonMekmm.add("secondaryOutput", secondaryOutput);
                     jsonMekmm.addProperty("secondaryChance", 0.8);
                  }

                  Path pathMekmm = recipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
                  Files.writeString(pathMekmm, GSON.toJson(jsonMekmm));
                  JsonObject jsonCloche = new JsonObject();
                  jsonCloche.addProperty("type", "immersiveengineering:cloche");
                  JsonObject inputCloche = new JsonObject();
                  inputCloche.addProperty("item", seedId.toString());
                  jsonCloche.add("input", inputCloche);
                  JsonObject render = new JsonObject();
                  render.addProperty("type", "crop");
                  ResourceLocation cropId = ForgeRegistries.BLOCKS.getKey(cropBlock);
                  if (cropId != null) {
                     render.addProperty("block", cropId.toString());
                  } else {
                     render.addProperty("block", "minecraft:potatoes");
                  }

                  jsonCloche.add("render", render);
                  JsonArray results = new JsonArray();
                  JsonObject mainResult = new JsonObject();
                  mainResult.addProperty("count", recipeOutputCount);
                  mainResult.addProperty("item", mainOutputId.toString());
                  results.add(mainResult);
                  if (hasSecondaryOutput) {
                     JsonObject seedResult = new JsonObject();
                     seedResult.addProperty("item", seedId.toString());
                     results.add(seedResult);
                  }

                  jsonCloche.add("results", results);
                  JsonObject soil = new JsonObject();
                  soil.addProperty("item", "minecraft:dirt");
                  jsonCloche.add("soil", soil);
                  jsonCloche.addProperty("time", 800);
                  Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
                  Files.writeString(pathCloche, GSON.toJson(jsonCloche));
                  generatePlantCutJson(plantcutRecipeDir, seedId, mainOutputId, recipeOutputCount, hasSecondaryOutput, seedId, 0.8F, server);
                  // 依据战利品表为 BotanyPots 生成植物盆 crop 配方（任务 3）
                  if (botanyPotsInstalled) {
                     writeBotanyPotsCropJson(botanyPotsRecipeDir, seedId, ForgeRegistries.BLOCKS.getKey(cropBlock),
                             mainOutputId, recipeOutputCount, hasSecondaryOutput, seedId, 0.8F);
                  }
                  LOGGER.debug("Generated both planting recipes: {} -> {} x{}", new Object[]{seedId, mainOutputId, recipeOutputCount});
                  debugGeneratedSeeds.add(seedId);
                  return true;
               }
            }
         }
      }
   }

   private static boolean generateRecipeForNonCrop(
      Block block, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir, Path botanyPotsRecipeDir,
      boolean botanyPotsInstalled, Map<Item, BotanyCropData> botanyCrops, MinecraftServer server, Set<Item> processedSeeds
   ) throws IOException {
      // 优先级 2（非 CropBlock 可生长方块）：方块物品本身命中有 BotanyPots 作物配方 → 转换
      Item blockItem = block.asItem();
      BotanyCropData botanyData = botanyCrops.get(blockItem);
      if (botanyData != null && !processedSeeds.contains(blockItem)) {
         if (generateFromBotanyPots(blockItem, botanyData, recipeDir, clocheRecipeDir, plantcutRecipeDir, server,
                 ForgeRegistries.BLOCKS.getKey(block))) {
            processedSeeds.add(blockItem);
            return true;
         }
      }
      Map<Item, Integer> allItems = determineAllOutputItems(block, null, server);
      if (allItems.isEmpty()) {
         LOGGER.debug("No output items from loot table for non-crop block: {}, skipping.", ForgeRegistries.BLOCKS.getKey(block));
         return false;
      } else {
         Map<Item, Integer> filteredItems = filterOutSeedItems(allItems);
         if (filteredItems.isEmpty()) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            LOGGER.warn("All items from loot table for non-crop block {} were seeds, no products remain. Skipping.", blockId);
            debugNoProducts.add(blockId);
            return false;
         } else {
            Entry<Item, Integer> firstEntry = filteredItems.entrySet().iterator().next();
            Item seed = firstEntry.getKey();
            int seedCount = firstEntry.getValue();
            ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(seed);
            if (seedId == null) {
               return false;
            } else if (processedSeeds.contains(seed)) {
               LOGGER.debug("Seed {} already processed from CropBlock, skipping non-CropBlock duplicate.", seedId);
               return false;
            } else {
               processedSeeds.add(seed);
               int recipeOutputCount = Math.max(1, seedCount * 2);
               JsonObject jsonMekmm = new JsonObject();
               jsonMekmm.addProperty("type", "mekmm:planting");
               JsonObject itemInput = new JsonObject();
               JsonObject ingredient = new JsonObject();
               ingredient.addProperty("item", seedId.toString());
               itemInput.add("ingredient", ingredient);
               jsonMekmm.add("itemInput", itemInput);
               JsonObject gasInput = new JsonObject();
               gasInput.addProperty("amount", 1);
               gasInput.addProperty("gas", "mekmm:nutrient_solution");
               jsonMekmm.add("gasInput", gasInput);
               JsonObject mainOutputJson = new JsonObject();
               mainOutputJson.addProperty("count", recipeOutputCount);
               mainOutputJson.addProperty("item", seedId.toString());
               jsonMekmm.add("mainOutput", mainOutputJson);
               Path pathMekmm = recipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
               Files.writeString(pathMekmm, GSON.toJson(jsonMekmm));
               ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
               JsonObject jsonCloche = new JsonObject();
               jsonCloche.addProperty("type", "immersiveengineering:cloche");
               JsonObject inputCloche = new JsonObject();
               inputCloche.addProperty("item", seedId.toString());
               jsonCloche.add("input", inputCloche);
               JsonObject render = new JsonObject();
               render.addProperty("type", "crop");
               render.addProperty("block", blockId != null ? blockId.toString() : "minecraft:potatoes");
               jsonCloche.add("render", render);
               JsonArray results = new JsonArray();
               JsonObject mainResult = new JsonObject();
               mainResult.addProperty("count", recipeOutputCount);
               mainResult.addProperty("item", seedId.toString());
               results.add(mainResult);
               jsonCloche.add("results", results);
               JsonObject soil = new JsonObject();
               soil.addProperty("item", "minecraft:dirt");
               jsonCloche.add("soil", soil);
               jsonCloche.addProperty("time", 800);
               Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
               Files.writeString(pathCloche, GSON.toJson(jsonCloche));
               generatePlantCutJson(plantcutRecipeDir, seedId, seedId, recipeOutputCount, false, null, 0.0F, server);
               if (botanyPotsInstalled) {
                  writeBotanyPotsCropJson(botanyPotsRecipeDir, seedId, blockId, seedId, recipeOutputCount, false, null, 0.0F);
               }
               LOGGER.info("Generated non-crop planting recipes: block={}, seed={} x{}", new Object[]{blockId, seedId, recipeOutputCount});
               return true;
            }
         }
      }
   }

   private static void generatePlantCutJson(
      Path plantcutRecipeDir,
      ResourceLocation seedId,
      ResourceLocation mainOutputId,
      int mainOutputCount,
      boolean hasSecondaryOutput,
      ResourceLocation secondaryItemId,
      float secondaryChance,
      MinecraftServer server
   ) throws IOException {
      JsonObject json = new JsonObject();
      json.addProperty("type", "mekck:plantcut");
      JsonObject seed = new JsonObject();
      seed.addProperty("item", seedId.toString());
      json.add("seed", seed);
      json.addProperty("gasAmount", 1);
      List<ItemStack> cuttingResults = getCuttingResults(server, mainOutputId, mainOutputCount);
      JsonArray resultsArray = new JsonArray();
      if (!cuttingResults.isEmpty()) {
         for (ItemStack cutStack : cuttingResults) {
            JsonObject result = new JsonObject();
            result.addProperty("item", ForgeRegistries.ITEMS.getKey(cutStack.getItem()).toString());
            result.addProperty("count", cutStack.getCount());
            resultsArray.add(result);
         }
      } else {
         JsonObject result = new JsonObject();
         result.addProperty("item", mainOutputId.toString());
         result.addProperty("count", mainOutputCount);
         resultsArray.add(result);
      }

      json.add("results", resultsArray);
      if (hasSecondaryOutput && secondaryItemId != null) {
         JsonArray secondaryArray = new JsonArray();
         JsonObject secondary = new JsonObject();
         secondary.addProperty("item", secondaryItemId.toString());
         secondary.addProperty("count", 1);
         secondaryArray.add(secondary);
         json.add("secondaryResults", secondaryArray);
         json.addProperty("secondaryChance", secondaryChance);
      }

      // 生长方块格要求（只对神秘农业种子写；见 appendGrowthSoilRequirement）
      appendGrowthSoilRequirement(json, seedId);

      String fileName = seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json";
      Path path = plantcutRecipeDir.resolve(fileName);
      Files.writeString(path, GSON.toJson(json));
      LOGGER.debug("Generated mekck:plantcut recipe: {} -> {} (cutting: {})", new Object[]{seedId, mainOutputId, !cuttingResults.isEmpty()});
   }

   /**
    * 给 plantcut 配方写入「生长方块格」要求。
    * <p>口径（用户 2026-09-17 拍板）：**只有神秘农业种子**受生长方块格约束 ——
    * 从 BotanyPots 的 {@code botanypots:crop} 读出该种子要求的 categories（如 {@code ["inferium"]}），
    * 再把 {@code botanypots:soil} 里 categories **⊇** 该集合的土壤物品列成 {@code soils} 白名单。</p>
    * <p>⇒ 运行时（{@code PlantingCuttingRecipe}）只做「槽里的物品在不在白名单里」，
    * **不需要在游戏里反射 BotanyPots**，也不需要额外的等级表；没装 BotanyPots 时字段不写、整条链路跳过。</p>
    */
   private static void appendGrowthSoilRequirement(JsonObject json, ResourceLocation seedId) {
      if (!REQUIRES_SOIL_NAMESPACE.equals(seedId.getNamespace())) {
         return;
      }
      Item seedItem = (Item)ForgeRegistries.ITEMS.getValue(seedId);
      if (seedItem == null) {
         return;
      }
      java.util.Set<String> required = seedSoilCategories.get(seedItem);
      if (required == null || required.isEmpty()) {
         return;
      }
      JsonArray categoryArray = new JsonArray();
      for (String category : required) {
         categoryArray.add(category);
      }
      json.add("requiredSoilCategories", categoryArray);

      JsonArray soilItems = new JsonArray();
      for (Entry<Item, java.util.Set<String>> soilEntry : soilCategoriesByItem.entrySet()) {
         if (!soilEntry.getValue().containsAll(required)) {
            continue;
         }
         ResourceLocation soilId = ForgeRegistries.ITEMS.getKey(soilEntry.getKey());
         if (soilId != null) {
            soilItems.add(soilId.toString());
         }
      }
      if (!soilItems.isEmpty()) {
         // 直接写字符串数组（不套 Ingredient 的 JSON 形态）—— 运行时自己解析，格式一眼可核对
         json.add("soils", soilItems);
      }
   }

   private static List<ItemStack> getCuttingResults(MinecraftServer server, ResourceLocation itemId, int count) {
      Item item = (Item)ForgeRegistries.ITEMS.getValue(itemId);
      if (item != null && item != Items.AIR) {
         ItemStack itemStack = new ItemStack(item, 1);
         RecipeType<?> cuttingType = (RecipeType<?>)cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("farmersdelight", "cutting"));
         if (cuttingType == null) {
            return Collections.emptyList();
         } else {
            RecipeWrapper wrapper = new RecipeWrapper(new net.minecraftforge.items.ItemStackHandler(1) {{
               setStackInSlot(0, itemStack);
            }});
            Optional<CuttingBoardRecipe> cuttingOpt = server.getRecipeManager().getRecipeFor(
               (RecipeType<CuttingBoardRecipe>)cuttingType, wrapper, server.overworld());
            if (!cuttingOpt.isPresent()) {
               return Collections.emptyList();
            } else {
               List<ItemStack> results = new ArrayList<>();

               for (ChanceResult chanceResult : cuttingOpt.get().getRollableResults()) {
                  ItemStack resultStack = chanceResult.getStack();
                  ItemStack multiplied = resultStack.copy();
                  long totalCount = (long)multiplied.getCount() * (long)count;
                  multiplied.setCount(totalCount > 2147483647L ? Integer.MAX_VALUE : (int)totalCount);
                  boolean merged = false;

                  for (ItemStack existing : results) {
                     if (ItemStack.isSameItemSameTags(existing, multiplied)) {
                        long sum = (long)existing.getCount() + (long)multiplied.getCount();
                        existing.setCount(sum > 2147483647L ? Integer.MAX_VALUE : (int)sum);
                        merged = true;
                        break;
                     }
                  }

                  if (!merged) {
                     results.add(multiplied);
                  }
               }

               return results;
            }
         }
      } else {
         return Collections.emptyList();
      }
   }

   /**
    * 读取一条 BotanyPots 作物配方的土壤 categories（{@code BasicCrop.getSoilCategories()}）。
    * 反射读取，任何失败都按「无要求」处理（不让生成流程挂掉）。
    */
   private static java.util.Set<String> readBotanyCropSoilCategories(Recipe<?> recipe) {
      try {
         if (cn.ism.mekck.util.Reflect.call(recipe, "getSoilCategories") instanceof java.util.Set<?> raw && !raw.isEmpty()) {
            java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
            for (Object category : raw) {
               if (category != null) {
                  parsed.add(category.toString());
               }
            }
            return parsed;
         }
      } catch (Exception ex) {
         LOGGER.debug("Failed to read BotanyPots crop soil categories: {}", ex.toString());
      }
      return java.util.Set.of();
   }

   /**
    * 收集全部 BotanyPots 土壤配方（type=botanypots:soil）→ 「土壤物品 → categories」。
    * <p>生长方块格的判定依据就是这里：<b>种子的 categories ⊆ 土壤的 categories</b>
    * （高级土壤会把低级的 category 全部累加进来，所以等价于「土壤等级 ≥ 种子等级」）。</p>
    * <p>与作物侧同一套路：纯反射，不引入 BotanyPots 编译依赖；未安装时保持空表。</p>
    */
   private static void collectBotanyPotsSoilRecipes(MinecraftServer server) {
      soilCategoriesByItem.clear();
      RecipeType<?> soilType = cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("botanypots", "soil"));
      if (soilType == null) {
         LOGGER.info("botanypots:soil 配方类型不存在（未安装 BotanyPots），跳过土壤扫描。");
         return;
      }
      try {
         @SuppressWarnings({"unchecked", "rawtypes"})
         java.util.Collection<Recipe<?>> recipes = (java.util.Collection) (java.util.Collection) server.getRecipeManager().getAllRecipesFor((RecipeType) soilType);
         for (Recipe<?> recipe : recipes) {
            String className = recipe.getClass().getName();
            if (!className.contains("botanypots") || !className.endsWith("BasicSoil")) {
               continue;
            }
            try {
               Object categoriesRaw = cn.ism.mekck.util.Reflect.call(recipe, "getCategories");
               if (!(categoriesRaw instanceof java.util.Set<?> raw) || raw.isEmpty()) {
                  continue;
               }
               java.util.Set<String> categories = new java.util.LinkedHashSet<>();
               for (Object category : raw) {
                  if (category != null) {
                     categories.add(category.toString());
                  }
               }
               Object ingredientRaw = cn.ism.mekck.util.Reflect.call(recipe, "getIngredient");
               if (!(ingredientRaw instanceof Ingredient soilIngredient)) {
                  continue;
               }
               for (ItemStack stack : soilIngredient.getItems()) {
                  if (!stack.isEmpty()) {
                     soilCategoriesByItem.putIfAbsent(stack.getItem(), categories);
                  }
               }
            } catch (Exception ex) {
               LOGGER.debug("Failed to parse BotanyPots soil recipe {}: {}", recipe.getId(), ex.toString());
            }
         }
         LOGGER.info("Collected {} BotanyPots soil mappings (生长方块格判定用).", soilCategoriesByItem.size());
      } catch (Exception ex) {
         LOGGER.error("Error scanning BotanyPots soil recipes", ex);
      }
   }

   private static boolean isBotanyPotsInstalled() {
      return cn.ism.mekck.util.RecipeCache.type(BOTANY_CROP_TYPE_ID) != null;
   }

   /**
    * 收集全部 BotanyPots 作物配方（type=botanypots:crop），按种子物品建立映射。
    * 通过反射读取 BasicCrop.getSeed()/getResults() 与 HarvestEntry 的 getChance/getItem/getMinRolls/getMaxRolls，
    * 不引入 BotanyPots 编译依赖（未安装时返回空表）。
    */
   private static Map<Item, BotanyCropData> collectBotanyPotsCropRecipes(MinecraftServer server) {
      Map<Item, BotanyCropData> map = new HashMap<>();
      if (!isBotanyPotsInstalled()) {
         LOGGER.info("botanypots:crop 配方类型不存在（未安装 BotanyPots），跳过植物盆栽配方扫描。");
         return map;
      }
      try {
         RecipeType<?> cropType = cn.ism.mekck.util.RecipeCache.type(BOTANY_CROP_TYPE_ID);
         @SuppressWarnings({"unchecked", "rawtypes"})
         java.util.Collection<Recipe<?>> recipes = (java.util.Collection) (java.util.Collection) server.getRecipeManager().getAllRecipesFor((RecipeType) cropType);
         for (Recipe<?> recipe : recipes) {
            String className = recipe.getClass().getName();
            if (!className.contains("botanypots") || !className.endsWith("BasicCrop")) {
               continue;
            }
            try {
               Method getSeed = recipe.getClass().getMethod("getSeed");
               Method getResults = recipe.getClass().getMethod("getResults");
               if (!(getSeed.invoke(recipe) instanceof Ingredient seedIngredient)) {
                  continue;
               }
               // 该种子要求的土壤 categories（读不到 = 无要求）
               java.util.Set<String> soilCategories = readBotanyCropSoilCategories(recipe);
               if (!soilCategories.isEmpty()) {
                  for (ItemStack seedStack : seedIngredient.getItems()) {
                     if (!seedStack.isEmpty()) {
                        seedSoilCategories.putIfAbsent(seedStack.getItem(), soilCategories);
                     }
                  }
               }
               if (!(getResults.invoke(recipe) instanceof List<?> resultList) || resultList.isEmpty()) {
                  continue;
               }
               List<BotanyDrop> drops = new ArrayList<>();
               for (Object result : resultList) {
                  if (result == null) {
                     continue;
                  }
                  try {
                     float chance = ((Number) cn.ism.mekck.util.Reflect.call(result, "getChance")).floatValue();
                     int minRolls = ((Number) cn.ism.mekck.util.Reflect.call(result, "getMinRolls")).intValue();
                     int maxRolls = ((Number) cn.ism.mekck.util.Reflect.call(result, "getMaxRolls")).intValue();
                     if (cn.ism.mekck.util.Reflect.call(result, "getItem") instanceof ItemStack stack && !stack.isEmpty()) {
                        drops.add(new BotanyDrop(stack.getItem(), chance, minRolls, maxRolls));
                     }
                  } catch (Exception ex) {
                     LOGGER.debug("Failed to parse BotanyPots drop entry: {}", ex.toString());
                  }
               }
               if (drops.isEmpty()) {
                  continue;
               }
               for (ItemStack stack : seedIngredient.getItems()) {
                  if (!stack.isEmpty()) {
                     map.putIfAbsent(stack.getItem(), new BotanyCropData(stack.getItem(), drops, soilCategories));
                  }
               }
            } catch (Exception ex) {
               LOGGER.debug("Failed to parse BotanyPots crop recipe {}: {}", recipe.getId(), ex.toString());
            }
         }
         LOGGER.info("Collected {} BotanyPots crop seed mappings (BotanyPots 配方转换源).", map.size());
      } catch (Exception ex) {
         LOGGER.error("Error scanning BotanyPots crop recipes", ex);
      }
      return map;
   }

   /**
    * 由 BotanyPots 作物配方转换生成我们的种植配方（mekmm:planting + immersiveengineering:cloche + mekck:plantcut）。
    * 转换规则：BP 种子输入 → 我们的种子输入（催化剂）；BP 掉落输出数量 ×2 → 我们的输出数量。
    * 主输出 = 首个非种子掉落（兜底第一个），副输出 = 种子掉落（无则第二个掉落），副输出概率沿用 BP 掉落概率。
    */
   private static boolean generateFromBotanyPots(
      Item seed, BotanyCropData data, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir,
      MinecraftServer server, ResourceLocation displayBlock
   ) throws IOException {
      ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(seed);
      if (seedId == null || data == null || data.drops().isEmpty()) {
         return false;
      }
      BotanyDrop mainDrop = null;
      for (BotanyDrop drop : data.drops()) {
         if (!drop.item().equals(seed)) {
            mainDrop = drop;
            break;
         }
      }
      if (mainDrop == null) {
         mainDrop = data.drops().get(0);
      }
      ResourceLocation mainId = ForgeRegistries.ITEMS.getKey(mainDrop.item());
      if (mainId == null) {
         return false;
      }
      int mainCount = Math.max(1, mainDrop.maxRolls()) * 2;
      BotanyDrop secondaryDrop = null;
      for (BotanyDrop drop : data.drops()) {
         if (drop.item().equals(seed)) {
            secondaryDrop = drop;
            break;
         }
      }
      if (secondaryDrop == null && data.drops().size() >= 2) {
         secondaryDrop = data.drops().get(1);
      }
      boolean hasSecondary = secondaryDrop != null && !secondaryDrop.item().equals(mainDrop.item());
      ResourceLocation secondaryId = hasSecondary ? ForgeRegistries.ITEMS.getKey(secondaryDrop.item()) : null;
      float secondaryChance = hasSecondary ? Math.max(0.05F, Math.min(1.0F, secondaryDrop.chance())) : 0.0F;

      JsonObject jsonMekmm = new JsonObject();
      jsonMekmm.addProperty("type", "mekmm:planting");
      JsonObject itemInput = new JsonObject();
      JsonObject ingredient = new JsonObject();
      ingredient.addProperty("item", seedId.toString());
      itemInput.add("ingredient", ingredient);
      jsonMekmm.add("itemInput", itemInput);
      JsonObject gasInput = new JsonObject();
      gasInput.addProperty("amount", 1);
      gasInput.addProperty("gas", "mekmm:nutrient_solution");
      jsonMekmm.add("gasInput", gasInput);
      JsonObject mainOutputJson = new JsonObject();
      mainOutputJson.addProperty("count", mainCount);
      mainOutputJson.addProperty("item", mainId.toString());
      jsonMekmm.add("mainOutput", mainOutputJson);
      if (hasSecondary && secondaryId != null) {
         JsonObject secondaryOutput = new JsonObject();
         secondaryOutput.addProperty("count", Math.max(1, secondaryDrop.maxRolls()) * 2);
         secondaryOutput.addProperty("item", secondaryId.toString());
         jsonMekmm.add("secondaryOutput", secondaryOutput);
         jsonMekmm.addProperty("secondaryChance", secondaryChance);
      }
      Path pathMekmm = recipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
      Files.writeString(pathMekmm, GSON.toJson(jsonMekmm));

      JsonObject jsonCloche = new JsonObject();
      jsonCloche.addProperty("type", "immersiveengineering:cloche");
      JsonObject inputCloche = new JsonObject();
      inputCloche.addProperty("item", seedId.toString());
      jsonCloche.add("input", inputCloche);
      JsonObject render = new JsonObject();
      render.addProperty("type", "crop");
      render.addProperty("block", displayBlock != null ? displayBlock.toString() : "minecraft:potatoes");
      jsonCloche.add("render", render);
      JsonArray results = new JsonArray();
      JsonObject mainResult = new JsonObject();
      mainResult.addProperty("count", mainCount);
      mainResult.addProperty("item", mainId.toString());
      results.add(mainResult);
      if (hasSecondary && secondaryId != null) {
         JsonObject seedResult = new JsonObject();
         seedResult.addProperty("item", secondaryId.toString());
         results.add(seedResult);
      }
      jsonCloche.add("results", results);
      JsonObject soil = new JsonObject();
      soil.addProperty("item", "minecraft:dirt");
      jsonCloche.add("soil", soil);
      jsonCloche.addProperty("time", 800);
      Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
      Files.writeString(pathCloche, GSON.toJson(jsonCloche));

      generatePlantCutJson(plantcutRecipeDir, seedId, mainId, mainCount, hasSecondary, secondaryId, secondaryChance, server);
      LOGGER.info("Generated planting recipes from BotanyPots: {} -> {} x{}", new Object[]{seedId, mainId, mainCount});
      debugGeneratedSeeds.add(seedId);
      return true;
   }

   /**
    * 依据战利品表为 BotanyPots 生成植物盆 crop 配方（type=botanypots:crop）。
    * 主掉落：chance 1.0、minRolls 1、maxRolls = 我们配方的主输出数量；副掉落（种子）沿用对应概率。
    */
   private static void writeBotanyPotsCropJson(
      Path botanyPotsRecipeDir, ResourceLocation seedId, ResourceLocation displayBlockId,
      ResourceLocation mainOutputId, int mainOutputCount, boolean hasSecondary, ResourceLocation secondaryId, float secondaryChance
   ) throws IOException {
      JsonObject json = new JsonObject();
      json.addProperty("type", "botanypots:crop");
      JsonObject seed = new JsonObject();
      seed.addProperty("item", seedId.toString());
      json.add("seed", seed);
      JsonArray categories = new JsonArray();
      categories.add("dirt");
      categories.add("farmland");
      json.add("categories", categories);
      json.addProperty("growthTicks", BOTANY_GROWTH_TICKS);
      JsonObject display = new JsonObject();
      display.addProperty("type", "botanypots:aging");
      display.addProperty("block", displayBlockId != null ? displayBlockId.toString() : "minecraft:wheat");
      json.add("display", display);
      JsonArray drops = new JsonArray();
      JsonObject mainDrop = new JsonObject();
      mainDrop.addProperty("chance", 1.0);
      JsonObject mainOutput = new JsonObject();
      mainOutput.addProperty("item", mainOutputId.toString());
      mainDrop.add("output", mainOutput);
      mainDrop.addProperty("minRolls", 1);
      mainDrop.addProperty("maxRolls", Math.max(1, mainOutputCount));
      drops.add(mainDrop);
      if (hasSecondary && secondaryId != null) {
         JsonObject secondaryDrop = new JsonObject();
         secondaryDrop.addProperty("chance", Math.max(0.05F, Math.min(1.0F, secondaryChance)));
         JsonObject secondaryOutput = new JsonObject();
         secondaryOutput.addProperty("item", secondaryId.toString());
         secondaryDrop.add("output", secondaryOutput);
         secondaryDrop.addProperty("minRolls", 1);
         secondaryDrop.addProperty("maxRolls", 1);
         drops.add(secondaryDrop);
      }
      json.add("drops", drops);
      String fileName = seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json";
      Files.writeString(botanyPotsRecipeDir.resolve(fileName), GSON.toJson(json));
      LOGGER.debug("Generated BotanyPots crop recipe: {}", fileName);
   }

   private static int generatePlantCutFromExisting(Path plantcutRecipeDir, MinecraftServer server) {
      int count = 0;

      try {
         RecipeType<?> plantingType = (RecipeType<?>)cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("mekmm", "planting"));
         if (plantingType == null) {
            LOGGER.info("mekmm:planting recipe type not found, skipping existing recipe scan.");
            return 0;
         }

         Class<?> plantingRecipeClass;
         try {
            plantingRecipeClass = Class.forName("com.jerry.mekmm.api.recipes.PlantingRecipe", false, PlantingRecipeGenerator.class.getClassLoader());
         } catch (ClassNotFoundException var27) {
            LOGGER.warn("PlantingRecipe class not found, cannot scan existing recipes.");
            return 0;
         }

         Method getItemInputMethod = plantingRecipeClass.getMethod("getItemInput");
         Method getMainOutputDefMethod = plantingRecipeClass.getMethod("getMainOutputDefinition");
         Method getSecondaryOutputDefMethod = plantingRecipeClass.getMethod("getSecondaryOutputDefinition");
         Method getSecondaryChanceMethod = plantingRecipeClass.getMethod("getSecondaryChance");

         @SuppressWarnings({"unchecked", "rawtypes"})
         java.util.Collection<Recipe<?>> recipes = (java.util.Collection)(java.util.Collection)server.getRecipeManager().getAllRecipesFor((RecipeType)plantingType);
         for (Recipe<?> recipe : recipes) {
            if (plantingRecipeClass.isInstance(recipe)) {
               try {
                  Object itemInput = getItemInputMethod.invoke(recipe);
                  if (itemInput != null) {
                     Method getRepsMethod = itemInput.getClass().getMethod("getRepresentations");
                     List<ItemStack> reps = (List<ItemStack>)getRepsMethod.invoke(itemInput);
                     if (!reps.isEmpty()) {
                        ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(reps.get(0).getItem());
                        if (seedId != null) {
                           List<ItemStack> mainOutputs = (List<ItemStack>)getMainOutputDefMethod.invoke(recipe);
                           if (!mainOutputs.isEmpty()) {
                              ItemStack mainOutput = mainOutputs.get(0);
                              ResourceLocation mainOutputId = ForgeRegistries.ITEMS.getKey(mainOutput.getItem());
                              if (mainOutputId != null) {
                                 int mainOutputCount = mainOutput.getCount();
                                 List<ItemStack> secondaryOutputs = (List<ItemStack>)getSecondaryOutputDefMethod.invoke(recipe);
                                 boolean hasSecondary = !secondaryOutputs.isEmpty();
                                 ResourceLocation secondaryId = hasSecondary ? ForgeRegistries.ITEMS.getKey(secondaryOutputs.get(0).getItem()) : null;
                                 float secondaryChance = 0.0F;
                                 if (hasSecondary && getSecondaryChanceMethod.invoke(recipe) instanceof Number n) {
                                    secondaryChance = n.floatValue();
                                 }

                                 generatePlantCutJson(
                                    plantcutRecipeDir, seedId, mainOutputId, mainOutputCount, hasSecondary, secondaryId, secondaryChance, server
                                 );
                                 count++;
                              }
                           }
                        }
                     }
                  }
               } catch (Exception var26) {
                  LOGGER.debug("Failed to extract recipe details from existing mekmm:planting recipe: {}", recipe.getId(), var26);
               }
            }
         }
      } catch (Exception var28) {
         LOGGER.error("Error scanning existing mekmm:planting recipes", var28);
      }

      return count;
   }

   private static Map<Item, Integer> determineAllOutputItems(Block block, Item seed, MinecraftServer server) {
      try {
         ResourceLocation lootTableId = block.getLootTable();
         if (lootTableId == null) {
            LOGGER.warn("Block {} has null loot table id, trying getDrops() fallback...", ForgeRegistries.BLOCKS.getKey(block));
            return evaluateBlockDrops(block, server);
         } else {
            LootTable lootTable = server.getLootData().getLootTable(lootTableId);
            if (lootTable == LootTable.EMPTY) {
               LOGGER.warn("Block {}: loot table {} is empty, trying getDrops() fallback...", ForgeRegistries.BLOCKS.getKey(block), lootTableId);
               return evaluateBlockDrops(block, server);
            } else {
               ServerLevel level = server.overworld();
               if (level == null) {
                  LOGGER.warn("Overworld not available when processing {}", ForgeRegistries.BLOCKS.getKey(block));
                  return Map.of();
               } else {
                  int maxAge = 0;

                  for (Property<?> prop : block.getStateDefinition().getProperties()) {
                     if (prop.getName().equals("age") && prop instanceof IntegerProperty ageProp) {
                        maxAge = ageProp.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
                        break;
                     }
                  }

                  LOGGER.debug("Block {} has maxAge={}", ForgeRegistries.BLOCKS.getKey(block), maxAge);
                  Map<Item, Integer> bestItems = null;
                  int bestAge = -1;
                  int bestCount = 0;

                  for (int age = 0; age <= maxAge; age++) {
                     Map<Item, Integer> itemsAtAge = evaluateLootTableAtAge(lootTable, block, age, level);
                     LOGGER.debug(
                        "Block {} age={}: {} items: {}",
                        new Object[]{
                           ForgeRegistries.BLOCKS.getKey(block),
                           age,
                           itemsAtAge.size(),
                           itemsAtAge.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
                        }
                     );
                     if (itemsAtAge.size() > bestCount) {
                        bestCount = itemsAtAge.size();
                        bestAge = age;
                        bestItems = itemsAtAge;
                     }
                  }

                  if (bestItems != null && !bestItems.isEmpty()) {
                     LOGGER.info(
                        "Block {}: best age={} with {} items: {}",
                        new Object[]{
                           ForgeRegistries.BLOCKS.getKey(block),
                           bestAge,
                           bestCount,
                           bestItems.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
                        }
                     );
                     return bestItems;
                  } else {
                     LOGGER.warn(
                        "Block {}: getRandomItems returned empty for all ages, trying direct loot table parsing...", ForgeRegistries.BLOCKS.getKey(block)
                     );
                     Map<Item, Integer> fallback = parseLootTableDirectly(lootTable, block, server);
                     if (!fallback.isEmpty()) {
                        LOGGER.info(
                           "Block {}: fallback parsing found {} items: {}",
                           new Object[]{
                              ForgeRegistries.BLOCKS.getKey(block),
                              fallback.size(),
                              fallback.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
                           }
                        );
                        return fallback;
                     } else {
                        LOGGER.warn("Block {}: no items extracted from any growth stage (0-{})", ForgeRegistries.BLOCKS.getKey(block), maxAge);
                        return Map.of();
                     }
                  }
               }
            }
         }
      } catch (Exception var12) {
         LOGGER.error("Exception while analyzing loot table for block {}", ForgeRegistries.BLOCKS.getKey(block), var12);
         return Map.of();
      }
   }

   private static Map<Item, Integer> evaluateBlockDrops(Block block, MinecraftServer server) {
      ServerLevel level = server.overworld();
      if (level == null) {
         return Map.of();
      } else {
         int maxAge = 0;

         for (Property<?> prop : block.getStateDefinition().getProperties()) {
            if (prop.getName().equals("age") && prop instanceof IntegerProperty ageProp) {
               maxAge = ageProp.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
               break;
            }
         }

         LOGGER.debug("Block {}: evaluating getDrops() for ages 0-{}", ForgeRegistries.BLOCKS.getKey(block), maxAge);
         Map<Item, Integer> bestItems = null;
         int bestAge = -1;
         int bestCount = 0;

         for (int age = 0; age <= maxAge; age++) {
            BlockState state = block.defaultBlockState();

            for (Property<?> propx : state.getProperties()) {
               if (propx.getName().equals("age") && propx instanceof IntegerProperty ageProp) {
                  state = (BlockState)state.setValue(ageProp, age);
                  break;
               }
            }

            Builder builder = new Builder(level);
            builder.withParameter(LootContextParams.BLOCK_STATE, state);
            builder.withParameter(LootContextParams.ORIGIN, Vec3.ZERO);
            builder.withParameter(LootContextParams.TOOL, ItemStack.EMPTY);

            try {
               List<ItemStack> drops = block.getDrops(state, builder);
               Map<Item, Integer> itemsAtAge = new HashMap<>();

               for (ItemStack stack : drops) {
                  if (!stack.isEmpty()) {
                     itemsAtAge.merge(stack.getItem(), stack.getCount(), Math::max);
                  }
               }

               LOGGER.debug(
                  "Block {} age={}: getDrops returned {} items: {}",
                  new Object[]{
                     ForgeRegistries.BLOCKS.getKey(block),
                     age,
                     itemsAtAge.size(),
                     itemsAtAge.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
                  }
               );
               if (itemsAtAge.size() > bestCount) {
                  bestCount = itemsAtAge.size();
                  bestAge = age;
                  bestItems = itemsAtAge;
               }
            } catch (Exception var14) {
               LOGGER.debug("getDrops age={} failed for block {}: {}", new Object[]{age, ForgeRegistries.BLOCKS.getKey(block), var14.getMessage()});
            }
         }

         if (bestItems != null && !bestItems.isEmpty()) {
            LOGGER.info(
               "Block {}: getDrops fallback best age={} with {} items: {}",
               new Object[]{
                  ForgeRegistries.BLOCKS.getKey(block),
                  bestAge,
                  bestCount,
                  bestItems.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
               }
            );
            return bestItems;
         } else {
            LOGGER.warn("Block {}: getDrops() returned empty for all ages (0-{})", ForgeRegistries.BLOCKS.getKey(block), maxAge);
            return Map.of();
         }
      }
   }

   private static Map<Item, Integer> parseLootTableDirectly(LootTable lootTable, Block block, MinecraftServer server) {
      Map<Item, Integer> result = new HashMap<>();

      try {
         Field poolsField = LootTable.class.getDeclaredField("pools");
         poolsField.setAccessible(true);
         List<LootPool> pools = (List<LootPool>)poolsField.get(lootTable);
         if (pools == null) {
            return result;
         }

         for (LootPool pool : pools) {
            Field entriesField = LootPool.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            List<LootPoolEntryContainer> entries = (List<LootPoolEntryContainer>)entriesField.get(pool);
            if (entries != null) {
               for (LootPoolEntryContainer entry : entries) {
                  extractItemFromEntry(entry, result, pool);
               }
            }
         }
      } catch (Exception var12) {
         LOGGER.debug("Fallback loot table parsing failed for block {}: {}", ForgeRegistries.BLOCKS.getKey(block), var12.getMessage());
      }

      return result;
   }

   private static void extractItemFromEntry(LootPoolEntryContainer entry, Map<Item, Integer> result, LootPool pool) {
      if (entry.getClass().getName().contains("LootItem")) {
         try {
            Field itemField = entry.getClass().getDeclaredField("item");
            itemField.setAccessible(true);
            if (itemField.get(entry) instanceof Item item) {
               int count = getPoolEntryCount(entry, pool);
               result.merge(item, count, Math::max);
            }
         } catch (Exception var9) {
            LOGGER.debug("Failed to extract item from LootItem entry: {}", var9.getMessage());
         }
      }

      try {
         Field childrenField = entry.getClass().getDeclaredField("children");
         childrenField.setAccessible(true);
         Object childrenObj = childrenField.get(entry);
         if (childrenObj instanceof List) {
            for (Object child : (List)childrenObj) {
               if (child instanceof LootPoolEntryContainer childEntry) {
                  extractItemFromEntry(childEntry, result, pool);
               }
            }
         }
      } catch (NoSuchFieldException var10) {
      } catch (Exception var11) {
         LOGGER.debug("Failed to extract children from entry: {}", var11.getMessage());
      }
   }

   private static int getPoolEntryCount(LootPoolEntryContainer entry, LootPool pool) {
      try {
         Field functionsField = LootPoolEntryContainer.class.getDeclaredField("functions");
         functionsField.setAccessible(true);
         if (functionsField.get(entry) instanceof LootItemFunction[] functions) {
            for (LootItemFunction function : functions) {
               if (function.getClass().getName().contains("SetItemCountFunction")) {
                  Field valueField = function.getClass().getDeclaredField("value");
                  valueField.setAccessible(true);
                  if (valueField.get(function) instanceof NumberProvider provider) {
                     int min = 0;
                     int max = 0;
                     if (provider.getClass().getName().contains("ConstantValue")) {
                        Field constantField = provider.getClass().getDeclaredField("value");
                        constantField.setAccessible(true);
                        float val = constantField.getFloat(provider);
                        min = max = Math.round(val);
                     }

                     try {
                        Field minField = provider.getClass().getDeclaredField("min");
                        minField.setAccessible(true);
                        Field maxField = provider.getClass().getDeclaredField("max");
                        maxField.setAccessible(true);
                        if (minField.get(provider) instanceof NumberProvider minProv) {
                           min = extractConstantValue(minProv);
                        }

                        if (maxField.get(provider) instanceof NumberProvider maxProv) {
                           max = extractConstantValue(maxProv);
                        }
                     } catch (NoSuchFieldException var19) {
                     }

                     return Math.max(1, (max + min + 1) / 2);
                  }
               }
            }
         }
      } catch (Exception var20) {
         LOGGER.debug("Failed to get count from functions: {}", var20.getMessage());
      }

      try {
         Field rollsField = LootPool.class.getDeclaredField("rolls");
         rollsField.setAccessible(true);
         if (rollsField.get(pool) instanceof NumberProvider provider) {
            int val = extractConstantValue(provider);
            if (val > 0) {
               return val;
            }
         }
      } catch (Exception var18) {
         LOGGER.debug("Failed to get pool rolls: {}", var18.getMessage());
      }

      return 1;
   }

   private static int extractConstantValue(NumberProvider provider) {
      try {
         if (provider.getClass().getName().contains("ConstantValue")) {
            Field constantField = provider.getClass().getDeclaredField("value");
            constantField.setAccessible(true);
            return Math.round(constantField.getFloat(provider));
         }

         try {
            Field minField = provider.getClass().getDeclaredField("min");
            minField.setAccessible(true);
            Field maxField = provider.getClass().getDeclaredField("max");
            maxField.setAccessible(true);
            Object minObj = minField.get(provider);
            Object maxObj = maxField.get(provider);
            if (minObj instanceof Float minF && maxObj instanceof Float maxF) {
               return Math.max(1, Math.round((minF + maxF) / 2.0F));
            }

            if (minObj instanceof Number minN && maxObj instanceof Number maxN) {
               return Math.max(1, Math.round((minN.floatValue() + maxN.floatValue()) / 2.0F));
            }
         } catch (NoSuchFieldException var7) {
         }
      } catch (Exception var8) {
         LOGGER.debug("Failed to extract constant value: {}", var8.getMessage());
      }

      return 1;
   }

   private static Map<Item, Integer> evaluateLootTableAtAge(LootTable lootTable, Block block, int age, ServerLevel level) {
      Map<Item, Integer> result = new HashMap<>();
      BlockState state = block.defaultBlockState();

      for (Property<?> prop : state.getProperties()) {
         if (prop.getName().equals("age") && prop instanceof IntegerProperty ageProp) {
            state = (BlockState)state.setValue(ageProp, age);
            break;
         }
      }

      Builder builder = new Builder(level);
      builder.withParameter(LootContextParams.BLOCK_STATE, state);
      builder.withParameter(LootContextParams.ORIGIN, Vec3.ZERO);
      builder.withParameter(LootContextParams.TOOL, ItemStack.EMPTY);
      LootParams params = builder.create(LootContextParamSets.BLOCK);

      for (int i = 0; i < 100; i++) {
         try {
            for (ItemStack stack : lootTable.getRandomItems(params)) {
               if (!stack.isEmpty()) {
                  Item item = stack.getItem();
                  int count = stack.getCount();
                  result.merge(item, count, Math::max);
               }
            }
         } catch (Exception var14) {
            LOGGER.debug("getRandomItems age={} attempt {} failed: {}", new Object[]{age, i + 1, var14.getMessage()});
         }
      }

      return result;
   }
}
