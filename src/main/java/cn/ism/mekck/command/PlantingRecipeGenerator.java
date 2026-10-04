package cn.ism.mekck.command;

import cn.ism.mekck.command.planting.BotanyPotsCollector;
import cn.ism.mekck.command.planting.GeneratorFs;
import cn.ism.mekck.command.planting.LootRoller;
import cn.ism.mekck.command.planting.RecipeJsonWriter;
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
import java.util.stream.Stream;
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

/**
 * 种植配方的 <b>dev 命令入口</b>：服务器启动时清旧内容、生成 {@code mekmm:planting} /
 * {@code immersiveengineering:cloche} / {@code mekck:plantcut} / {@code botanypots:crop}
 * 四类配方 JSON，并把 BotanyPots 数据与战利品表求值结果汇总成我们的配方。
 *
 * <h3>为什么拆出四个伴生类，本类只留入口</h3>
 * 拆分前这是一个约 1700 行的 God 命令，混着四件本可分开读的事：碰磁盘的杂务、
 * 拼配方 JSON、反射读 BotanyPots、试跑战利品表。四个伴生类分别接管这四件事
 * （{@link GeneratorFs} / {@link RecipeJsonWriter} / {@link BotanyPotsCollector} /
 * {@link LootRoller}），本类退化为<b>编排者</b>与<b>跨类共享状态的唯一持有者</b>：
 * <ul>
 *   <li>可生长的种子/方块映射、调试产物列表、BotanyPots 土壤 categories 表 ——
 *       都是生成期状态，多个伴生类要读写，按「伴生类不持状态」的约定集中放在这里，
 *       由 {@link #generate} 通过参数传给伴生类；</li>
 *   <li>可选依赖判据（{@link #isBotanyPotsInstalled} / {@link #isImmersiveEngineeringInstalled} /
 *       {@link #isMekmmInstalled}）与它们的类型 ID 常量 —— 调用点横跨多个伴生类，
 *       依约定留在入口类被回调；</li>
 *   <li>{@link #generatePlantCutJson} 及其两个私有辅助 —— 三个写侧
 *       （BotanyPots 转换、战利品表推产物、既有配方反扫）都要用它，
 *       调用点不落在单一伴生类内，故同样留在入口类。</li>
 * </ul>
 * <p>本类是 {@code final} 且全静态；拆分只搬运方法，不改路径/文件名/JSON 形状/删除逻辑。</p>
 */
public final class PlantingRecipeGenerator {
   public static final Logger LOGGER = LoggerFactory.getLogger(PlantingRecipeGenerator.class);
   public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

   /** BotanyPots 作物配方类型 ID（生成期扫 botanypots:crop 用）。 */
   public static final ResourceLocation BOTANY_CROP_TYPE_ID = ResourceLocation.fromNamespaceAndPath("botanypots", "crop");

   /** BotanyPots 作物配方的一次掉落条目（运行时反射解析，无编译依赖）。 */
   public record BotanyDrop(Item item, float chance, int minRolls, int maxRolls) { }

   /** BotanyPots 作物配方（种子输入 + 掉落条目列表 + 该种子要求的土壤 categories）。 */
   public record BotanyCropData(Item seed, List<BotanyDrop> drops, java.util.Set<String> soilCategories) { }

   /** 种子 → 要求的土壤 categories（生成期扫 botanypots:crop 得到）。 */
   private static final Map<Item, java.util.Set<String>> seedSoilCategories = new LinkedHashMap<>();

   /** 土壤物品 → 提供的 categories（生成期扫 botanypots:soil 得到；高级土壤会累加低级 category）。 */
   private static final Map<Item, java.util.Set<String>> soilCategoriesByItem = new LinkedHashMap<>();

   /** 生长方块格只对神秘农业种子生效（用户 2026-09-17 决定：其余模组的种植配方无视该格）。 */
   private static final String REQUIRES_SOIL_NAMESPACE = "mysticalagriculture";

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
               GeneratorFs.deleteDirectoryRecursively(owned);
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
         Set<Item> blacklist = GeneratorFs.loadBlacklist(server);
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
         // IE 未安装时 plant_ie 里的 cloche 配方整批是坏的（type 解析失败，每次启动刷 20 条 ERROR）
         // ⇒ 不生成，并清掉上一轮留下的旧文件（否则换环境后旧文件仍在那里继续报错）。
         // 目录本身已在上面 createDirectories 建好，这里只需在 IE 缺席时清空它。
         boolean ieInstalled = isImmersiveEngineeringInstalled();
         if (!ieInstalled) {
            GeneratorFs.purgeDirectory(clocheRecipeDir, "plant_ie (Immersive Engineering not installed)");
         }
         // 同理：mekmm 未装时 mekmm:planting 类型不存在，那批配方全是坏的 ⇒ 不生成并清旧文件。
         boolean mekmmInstalled = isMekmmInstalled();
         if (!mekmmInstalled) {
            GeneratorFs.purgeDirectory(recipeDir, "planting (mekmm not installed)");
         }
         Map<Item, BotanyCropData> botanyCrops = BotanyPotsCollector.collectBotanyPotsCropRecipes(server, seedSoilCategories);
         // 土壤 categories（生长方块格判定）：必须在写任何 plantcut 配方之前收集好
         BotanyPotsCollector.collectBotanyPotsSoilRecipes(server, soilCategoriesByItem);
         int existingCount = RecipeJsonWriter.generatePlantCutFromExisting(plantcutRecipeDir, server);
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
                     generated = BotanyPotsCollector.generateFromBotanyPots(entry.getKey(), botanyData, recipeDir, clocheRecipeDir,
                             plantcutRecipeDir, server, ForgeRegistries.BLOCKS.getKey(entry.getValue()), debugGeneratedSeeds);
                  }
                  if (!generated) {
                     // 优先级 3：无种植配方且无 BP 配方 → 依据战利品表生成（同时为 BotanyPots 生成植物盆配方）
                     generated = RecipeJsonWriter.generateRecipe(entry.getKey(), entry.getValue(), recipeDir, clocheRecipeDir,
                             plantcutRecipeDir, botanyPotsRecipeDir, botanyPotsInstalled, server,
                             debugGeneratedSeeds, debugFailedLootTable, debugNoProducts);
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
               if (RecipeJsonWriter.generateRecipeForNonCrop(blockx, recipeDir, clocheRecipeDir, plantcutRecipeDir, botanyPotsRecipeDir,
                       botanyPotsInstalled, botanyCrops, server, processedSeeds, debugGeneratedSeeds, debugNoProducts)) {
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

   private static boolean isDebugEnabled(MinecraftServer server) {
      // 2026-09-16：原为「TOML 开关 + 独立 planting_debug.json 开关」两道门，
      // 且两者默认值互相矛盾（TOML 默认 true、JSON 默认 false ⇒ 实际等于关）。
      // 现已合并为 mekck-common.toml 里唯一的一项 [planting] print_planting_debug_to_chat。
      GeneratorFs.migrateLegacyPlantingConfigs(server);
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

   /**
    * 写出 {@code mekck:plantcut} 配方 JSON。
    *
    * <h3>为什么留在入口类而非 {@link RecipeJsonWriter}</h3>
    * 它的三个调用点横跨两个伴生类：{@link RecipeJsonWriter}（战利品表推产物、既有配方反扫）
    * 与 {@link BotanyPotsCollector}（BP 转换）。按「辅助方法仅当全部调用点都在同一伴生类内才搬」
    * 的约定，它保留在这里，由两个伴生类回调。
    */
   public static void generatePlantCutJson(
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
         RecipeType<?> cuttingType = (RecipeType<?>)cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("farmersdelight", "cutting"));
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

   public static boolean isBotanyPotsInstalled() {
      return cn.ism.mekck.recipe.RecipeCache.type(BOTANY_CROP_TYPE_ID) != null;
   }

   /**
    * 沉浸工程（Immersive Engineering）的园艺玻璃罩配方类型是否可用。
    *
    * <h3>为什么必须判（本仓实测缺陷）</h3>
    * {@code plant_ie} 目录下的配方全部写成 {@code "type": "immersiveengineering:cloche"}，
    * 而原实现<b>无条件生成</b>它们。IE 未安装时（本机 2026-10-03 实测）配方序列化器里没有
    * 这个 type ⇒ 每一条都在 {@code RecipeManager.fromJson} 抛
    * {@code JsonSyntaxException: Invalid or unsupported recipe type}，一次启动刷 20 条 ERROR：
    * <pre>
    * Parsing error loading recipe mekck:plant_ie/minecraft_sweet_berries
    * com.google.gson.JsonSyntaxException: Invalid or unsupported recipe type 'immersiveengineering:cloche'
    * </pre>
    * 判据与 {@link #isBotanyPotsInstalled()} 同源（查 recipe type 是否已注册），
    * 不用 {@code ModList.isLoaded} 是刻意的：即使装了 IE，只要它的 cloche 类型没注册成功，
    * 生成出来的仍是坏配方，所以以「类型真的可用」为准。
    */
   public static boolean isImmersiveEngineeringInstalled() {
      return cn.ism.mekck.recipe.RecipeCache.type(IE_CLOCHE_TYPE_ID) != null;
   }

   /** 沉浸工程园艺玻璃罩的配方类型 id。 */
   private static final net.minecraft.resources.ResourceLocation IE_CLOCHE_TYPE_ID =
           net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("immersiveengineering", "cloche");

   /** 通用机械：更多机器（mekmm）的种植配方类型 id。 */
   private static final net.minecraft.resources.ResourceLocation MEKMM_PLANTING_TYPE_ID =
           net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mekmm", "planting");

   /**
    * mekmm（通用机械：更多机器）的种植配方类型是否可用。
    *
    * <h3>为什么必须判（本仓实测缺陷）</h3>
    * {@code recipeDir} 下的配方写成 {@code "type": "mekmm:planting"}，而原实现<b>无条件生成</b>。
    * mekmm 未安装时（本机 2026-10-03 实测）每一条都在 {@code RecipeManager.fromJson} 抛
    * {@code Invalid or unsupported recipe type 'mekmm:planting'}，一次启动刷 20 条 ERROR；
    * 与之相邻的 {@code plant_ie} 又是 20 条 —— 合计 40 条。
    *
    * <p>判据与 {@link #isBotanyPotsInstalled()} / {@link #isImmersiveEngineeringInstalled()} 同源：
    * 以「配方类型真的已注册」为准，而不是 {@code ModList.isLoaded}。</p>
    */
   public static boolean isMekmmInstalled() {
      return cn.ism.mekck.recipe.RecipeCache.type(MEKMM_PLANTING_TYPE_ID) != null;
   }
}
