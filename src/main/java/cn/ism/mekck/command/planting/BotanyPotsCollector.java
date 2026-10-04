package cn.ism.mekck.command.planting;

import cn.ism.mekck.command.PlantingRecipeGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 种植配方生成器的 <b>BotanyPots 侧</b>：扫描 {@code botanypots:crop} / {@code botanypots:soil}
 * 配方，并把 BotanyPots 的作物配方<b>转换</b>成我们自己的种植配方。
 *
 * <h3>为什么这条边界在「反射读 BotanyPots」与「写我们自己的 JSON」之间</h3>
 * 这一整段共享同一个特征：<b>全部通过反射读 BotanyPots，不引入任何 BotanyPots 编译依赖</b>
 * （未安装时整段退化为空表/空操作）。它与 {@link RecipeJsonWriter} 的区别是数据来源：
 * 后者从<b>原版战利品表</b>推产物，前者从<b>第三方模组的配方对象</b>推产物。
 * 两者的失败模式、可选依赖判据、产物口径都不同，混在一起时读一种来源得先跳过另一种的细节。
 *
 * <h3>搬运规则</h3>
 * 采集结果（种子→土壤 categories、土壤物品→categories）是跨类共享的生成期状态，
 * 因此<b>本类不持有它</b>：由入口类持有并通过参数传入本类的采集方法
 * （{@code seedSoilCategories} / {@code soilCategoriesByItem}），本类只往传入的表里写。
 * 同理，调试产物列表（{@code debugGeneratedSeeds}）也由参数传入。
 * 日志复用 {@link PlantingRecipeGenerator#LOGGER}（同一 logger 实例，日志文本逐字不变）。
 */
public final class BotanyPotsCollector {

   private BotanyPotsCollector() {
   }

   /** BotanyPots 作物配方的默认生长时间（tick），写进 botanypots:crop 的 {@code growthTicks}。 */
   private static final int BOTANY_GROWTH_TICKS = 1200;

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
         PlantingRecipeGenerator.LOGGER.debug("Failed to read BotanyPots crop soil categories: {}", ex.toString());
      }
      return java.util.Set.of();
   }

   /**
    * 收集全部 BotanyPots 土壤配方（type=botanypots:soil）→ 「土壤物品 → categories」。
    * <p>生长方块格的判定依据就是这里：<b>种子的 categories ⊆ 土壤的 categories</b>
    * （高级土壤会把低级的 category 全部累加进来，所以等价于「土壤等级 ≥ 种子等级」）。</p>
    * <p>与作物侧同一套路：纯反射，不引入 BotanyPots 编译依赖；未安装时保持空表。</p>
    */
   public static void collectBotanyPotsSoilRecipes(MinecraftServer server, Map<Item, java.util.Set<String>> soilCategoriesByItem) {
      soilCategoriesByItem.clear();
      RecipeType<?> soilType = cn.ism.mekck.recipe.RecipeCache.type(ResourceLocation.fromNamespaceAndPath("botanypots", "soil"));
      if (soilType == null) {
         PlantingRecipeGenerator.LOGGER.info("botanypots:soil 配方类型不存在（未安装 BotanyPots），跳过土壤扫描。");
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
               PlantingRecipeGenerator.LOGGER.debug("Failed to parse BotanyPots soil recipe {}: {}", recipe.getId(), ex.toString());
            }
         }
         PlantingRecipeGenerator.LOGGER.info("Collected {} BotanyPots soil mappings (生长方块格判定用).", soilCategoriesByItem.size());
      } catch (Exception ex) {
         PlantingRecipeGenerator.LOGGER.error("Error scanning BotanyPots soil recipes", ex);
      }
   }

   /**
    * 收集全部 BotanyPots 作物配方（type=botanypots:crop），按种子物品建立映射。
    * 通过反射读取 BasicCrop.getSeed()/getResults() 与 HarvestEntry 的 getChance/getItem/getMinRolls/getMaxRolls，
    * 不引入 BotanyPots 编译依赖（未安装时返回空表）。
    */
   public static Map<Item, PlantingRecipeGenerator.BotanyCropData> collectBotanyPotsCropRecipes(
         MinecraftServer server, Map<Item, java.util.Set<String>> seedSoilCategories) {
      Map<Item, PlantingRecipeGenerator.BotanyCropData> map = new HashMap<>();
      if (!PlantingRecipeGenerator.isBotanyPotsInstalled()) {
         PlantingRecipeGenerator.LOGGER.info("botanypots:crop 配方类型不存在（未安装 BotanyPots），跳过植物盆栽配方扫描。");
         return map;
      }
      try {
         RecipeType<?> cropType = cn.ism.mekck.recipe.RecipeCache.type(PlantingRecipeGenerator.BOTANY_CROP_TYPE_ID);
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
               List<PlantingRecipeGenerator.BotanyDrop> drops = new ArrayList<>();
               for (Object result : resultList) {
                  if (result == null) {
                     continue;
                  }
                  try {
                     float chance = ((Number) cn.ism.mekck.util.Reflect.call(result, "getChance")).floatValue();
                     int minRolls = ((Number) cn.ism.mekck.util.Reflect.call(result, "getMinRolls")).intValue();
                     int maxRolls = ((Number) cn.ism.mekck.util.Reflect.call(result, "getMaxRolls")).intValue();
                     if (cn.ism.mekck.util.Reflect.call(result, "getItem") instanceof ItemStack stack && !stack.isEmpty()) {
                        drops.add(new PlantingRecipeGenerator.BotanyDrop(stack.getItem(), chance, minRolls, maxRolls));
                     }
                  } catch (Exception ex) {
                     PlantingRecipeGenerator.LOGGER.debug("Failed to parse BotanyPots drop entry: {}", ex.toString());
                  }
               }
               if (drops.isEmpty()) {
                  continue;
               }
               for (ItemStack stack : seedIngredient.getItems()) {
                  if (!stack.isEmpty()) {
                     map.putIfAbsent(stack.getItem(), new PlantingRecipeGenerator.BotanyCropData(stack.getItem(), drops, soilCategories));
                  }
               }
            } catch (Exception ex) {
               PlantingRecipeGenerator.LOGGER.debug("Failed to parse BotanyPots crop recipe {}: {}", recipe.getId(), ex.toString());
            }
         }
         PlantingRecipeGenerator.LOGGER.info("Collected {} BotanyPots crop seed mappings (BotanyPots 配方转换源).", map.size());
      } catch (Exception ex) {
         PlantingRecipeGenerator.LOGGER.error("Error scanning BotanyPots crop recipes", ex);
      }
      return map;
   }

   /**
    * 由 BotanyPots 作物配方转换生成我们的种植配方（mekmm:planting + immersiveengineering:cloche + mekck:plantcut）。
    * 转换规则：BP 种子输入 → 我们的种子输入（催化剂）；BP 掉落输出数量 ×2 → 我们的输出数量。
    * 主输出 = 首个非种子掉落（兜底第一个），副输出 = 种子掉落（无则第二个掉落），副输出概率沿用 BP 掉落概率。
    */
   public static boolean generateFromBotanyPots(
      Item seed, PlantingRecipeGenerator.BotanyCropData data, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir,
      MinecraftServer server, ResourceLocation displayBlock, List<ResourceLocation> debugGeneratedSeeds
   ) throws IOException {
      ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(seed);
      if (seedId == null || data == null || data.drops().isEmpty()) {
         return false;
      }
      PlantingRecipeGenerator.BotanyDrop mainDrop = null;
      for (PlantingRecipeGenerator.BotanyDrop drop : data.drops()) {
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
      PlantingRecipeGenerator.BotanyDrop secondaryDrop = null;
      for (PlantingRecipeGenerator.BotanyDrop drop : data.drops()) {
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
      // mekmm 未装 ⇒ mekmm:planting 类型不存在，跳过写入（plantcut 等其余配方不受影响）
      if (PlantingRecipeGenerator.isMekmmInstalled()) {
         Path pathMekmm = recipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
         Files.writeString(pathMekmm, PlantingRecipeGenerator.GSON.toJson(jsonMekmm));
      }

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
      // IE 未装 ⇒ cloche 配方无法解析，跳过写入（mekmm/plantcut 配方不受影响）
      if (PlantingRecipeGenerator.isImmersiveEngineeringInstalled()) {
         Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
         Files.writeString(pathCloche, PlantingRecipeGenerator.GSON.toJson(jsonCloche));
      }

      PlantingRecipeGenerator.generatePlantCutJson(plantcutRecipeDir, seedId, mainId, mainCount, hasSecondary, secondaryId, secondaryChance, server);
      PlantingRecipeGenerator.LOGGER.info("Generated planting recipes from BotanyPots: {} -> {} x{}", new Object[]{seedId, mainId, mainCount});
      debugGeneratedSeeds.add(seedId);
      return true;
   }

   /**
    * 依据战利品表为 BotanyPots 生成植物盆 crop 配方（type=botanypots:crop）。
    * 主掉落：chance 1.0、minRolls 1、maxRolls = 我们配方的主输出数量；副掉落（种子）沿用对应概率。
    */
   public static void writeBotanyPotsCropJson(
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
      Files.writeString(botanyPotsRecipeDir.resolve(fileName), PlantingRecipeGenerator.GSON.toJson(json));
      PlantingRecipeGenerator.LOGGER.debug("Generated BotanyPots crop recipe: {}", fileName);
   }
}
