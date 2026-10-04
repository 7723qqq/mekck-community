package cn.ism.mekck.command.planting;

import cn.ism.mekck.command.PlantingRecipeGenerator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 种植配方生成器的<b>配方 JSON 写侧</b>：把「一个种子/方块 + 它的产物」拼成
 * {@code mekmm:planting} / {@code immersiveengineering:cloche} 两类配方文件，
 * 以及从既有的 {@code mekmm:planting} 配方反扫出 {@code mekck:plantcut}。
 *
 * <h3>为什么这条边界把「写我们自己的 JSON」从采集与求值里分出来</h3>
 * 这里只做一件事：拿着已经确定好的 <b>种子 + 主/副产物 + 数量 + 概率</b>，
 * 用 {@link #filterOutSeedItems} 剔掉种子，然后拼 JSON 落盘。数据从哪来
 * （原版战利品表走 {@link LootRoller}，BotanyPots 配方走 {@link BotanyPotsCollector}）
 * 与本类无关，两个来源都只是在最后调用本类的写入口 —— 这样「JSON 形状」只有一个落点，
 * 改字段不会被两处采集逻辑各改一遍还改出差异。
 *
 * <h3>搬运规则</h3>
 * {@link #generatePlantCutJson} 及其两个私有辅助（生长方块格判定、切菜结果查询）
 * <b>未</b>搬进本类：{@link BotanyPotsCollector#generateFromBotanyPots} 也要调它，
 * 调用点横跨两个伴生类，依约定保留在入口类里回调（见 {@link PlantingRecipeGenerator}）。
 * 本类<b>不持有自己的状态</b>：GSON / logger 复用入口类的同一实例，
 * 调试产物列表由参数传入，写出的文件名与 JSON 形状与拆分前逐字一致。
 */
public final class RecipeJsonWriter {

   private RecipeJsonWriter() {
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

   public static boolean generateRecipe(
         Item seed, Block cropBlock, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir,
         Path botanyPotsRecipeDir, boolean botanyPotsInstalled, MinecraftServer server,
         List<ResourceLocation> debugGeneratedSeeds, List<ResourceLocation> debugFailedLootTable, List<ResourceLocation> debugNoProducts
   ) throws IOException {
      ResourceLocation seedId = ForgeRegistries.ITEMS.getKey(seed);
      if (seedId == null) {
         return false;
      } else {
         Map<Item, Integer> allItems = LootRoller.determineAllOutputItems(cropBlock, seed, server);
         if (allItems.isEmpty()) {
            PlantingRecipeGenerator.LOGGER.warn("Could not determine output items from loot table for seed: {}, skipping.", seedId);
            debugFailedLootTable.add(seedId);
            return false;
         } else {
            Map<Item, Integer> filteredItems = filterOutSeedItems(allItems);
            if (filteredItems.isEmpty()) {
               PlantingRecipeGenerator.LOGGER.warn("All items from loot table for seed {} were seeds, no products remain. Skipping.", seedId);
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
                  // IE 未装 ⇒ cloche 配方无法解析，跳过写入（mekmm/plantcut 配方不受影响）
                  if (PlantingRecipeGenerator.isImmersiveEngineeringInstalled()) {
                     Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
                     Files.writeString(pathCloche, PlantingRecipeGenerator.GSON.toJson(jsonCloche));
                  }
                  PlantingRecipeGenerator.generatePlantCutJson(plantcutRecipeDir, seedId, mainOutputId, recipeOutputCount, hasSecondaryOutput, seedId, 0.8F, server);
                  // 依据战利品表为 BotanyPots 生成植物盆 crop 配方（任务 3）
                  if (botanyPotsInstalled) {
                     BotanyPotsCollector.writeBotanyPotsCropJson(botanyPotsRecipeDir, seedId, ForgeRegistries.BLOCKS.getKey(cropBlock),
                             mainOutputId, recipeOutputCount, hasSecondaryOutput, seedId, 0.8F);
                  }
                  PlantingRecipeGenerator.LOGGER.debug("Generated both planting recipes: {} -> {} x{}", new Object[]{seedId, mainOutputId, recipeOutputCount});
                  debugGeneratedSeeds.add(seedId);
                  return true;
               }
            }
         }
      }
   }

   public static boolean generateRecipeForNonCrop(
      Block block, Path recipeDir, Path clocheRecipeDir, Path plantcutRecipeDir, Path botanyPotsRecipeDir,
      boolean botanyPotsInstalled, Map<Item, PlantingRecipeGenerator.BotanyCropData> botanyCrops,
      MinecraftServer server, Set<Item> processedSeeds, List<ResourceLocation> debugGeneratedSeeds, List<ResourceLocation> debugNoProducts
   ) throws IOException {
      // 优先级 2（非 CropBlock 可生长方块）：方块物品本身命中有 BotanyPots 作物配方 → 转换
      Item blockItem = block.asItem();
      PlantingRecipeGenerator.BotanyCropData botanyData = botanyCrops.get(blockItem);
      if (botanyData != null && !processedSeeds.contains(blockItem)) {
         if (BotanyPotsCollector.generateFromBotanyPots(blockItem, botanyData, recipeDir, clocheRecipeDir, plantcutRecipeDir, server,
                 ForgeRegistries.BLOCKS.getKey(block), debugGeneratedSeeds)) {
            processedSeeds.add(blockItem);
            return true;
         }
      }
      Map<Item, Integer> allItems = LootRoller.determineAllOutputItems(block, null, server);
      if (allItems.isEmpty()) {
         PlantingRecipeGenerator.LOGGER.debug("No output items from loot table for non-crop block: {}, skipping.", ForgeRegistries.BLOCKS.getKey(block));
         return false;
      } else {
         Map<Item, Integer> filteredItems = filterOutSeedItems(allItems);
         if (filteredItems.isEmpty()) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            PlantingRecipeGenerator.LOGGER.warn("All items from loot table for non-crop block {} were seeds, no products remain. Skipping.", blockId);
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
               PlantingRecipeGenerator.LOGGER.debug("Seed {} already processed from CropBlock, skipping non-CropBlock duplicate.", seedId);
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
               // mekmm 未装 ⇒ mekmm:planting 类型不存在，跳过写入（plantcut 等其余配方不受影响）
               if (PlantingRecipeGenerator.isMekmmInstalled()) {
                  Path pathMekmm = recipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
                  Files.writeString(pathMekmm, PlantingRecipeGenerator.GSON.toJson(jsonMekmm));
               }
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
               // IE 未装 ⇒ cloche 配方无法解析，跳过写入（mekmm/plantcut 配方不受影响）
               if (PlantingRecipeGenerator.isImmersiveEngineeringInstalled()) {
                  Path pathCloche = clocheRecipeDir.resolve(seedId.getNamespace() + "_" + seedId.getPath().replace('/', '_') + ".json");
                  Files.writeString(pathCloche, PlantingRecipeGenerator.GSON.toJson(jsonCloche));
               }
               PlantingRecipeGenerator.generatePlantCutJson(plantcutRecipeDir, seedId, seedId, recipeOutputCount, false, null, 0.0F, server);
               if (botanyPotsInstalled) {
                  BotanyPotsCollector.writeBotanyPotsCropJson(botanyPotsRecipeDir, seedId, blockId, seedId, recipeOutputCount, false, null, 0.0F);
               }
               PlantingRecipeGenerator.LOGGER.info("Generated non-crop planting recipes: block={}, seed={} x{}", new Object[]{blockId, seedId, recipeOutputCount});
               return true;
            }
         }
      }
   }

   public static int generatePlantCutFromExisting(Path plantcutRecipeDir, MinecraftServer server) {
      int count = 0;

      try {
         RecipeType<?> plantingType = (RecipeType<?>)cn.ism.mekck.util.RecipeCache.type(new ResourceLocation("mekmm", "planting"));
         if (plantingType == null) {
            PlantingRecipeGenerator.LOGGER.info("mekmm:planting recipe type not found, skipping existing recipe scan.");
            return 0;
         }

         Class<?> plantingRecipeClass;
         try {
            plantingRecipeClass = Class.forName("com.jerry.mekmm.api.recipes.PlantingRecipe", false, PlantingRecipeGenerator.class.getClassLoader());
         } catch (ClassNotFoundException var27) {
            PlantingRecipeGenerator.LOGGER.warn("PlantingRecipe class not found, cannot scan existing recipes.");
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

                                 PlantingRecipeGenerator.generatePlantCutJson(
                                    plantcutRecipeDir, seedId, mainOutputId, mainOutputCount, hasSecondary, secondaryId, secondaryChance, server
                                 );
                                 count++;
                              }
                           }
                        }
                     }
                  }
               } catch (Exception var26) {
                  PlantingRecipeGenerator.LOGGER.debug("Failed to extract recipe details from existing mekmm:planting recipe: {}", recipe.getId(), var26);
               }
            }
         }
      } catch (Exception var28) {
         PlantingRecipeGenerator.LOGGER.error("Error scanning existing mekmm:planting recipes", var28);
      }

      return count;
   }
}
