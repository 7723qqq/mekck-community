package cn.ism.mekck.command.planting;

import cn.ism.mekck.command.PlantingRecipeGenerator;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 种植配方生成器的<b>战利品表求值侧</b>：把一个可生长方块在其全部 {@code age} 上
 * 试跑战利品表，挑出「产物条目最多」的那一档，作为要生成配方的产物来源。
 *
 * <h3>为什么这条边界在「求值」与「写配方」之间</h3>
 * 这一段是纯<b>读世界数据</b>的：它只拿到 {@code Block} 与 {@code MinecraftServer}
 * （外加 {@code getDrops()} 兜底与反射直读 {@code LootTable}），产出一个
 * {@code Map<Item,Integer>}，从不碰磁盘、不写 JSON。它与「把产物拼成配方文件」
 * 的关注点不同，也<b>不共享任何生成期状态</b>，因此可以整段独立。
 *
 * <h3>搬运规则</h3>
 * {@link #determineAllOutputItems} 是唯一对外入口（被 {@link RecipeJsonWriter} 调用）；
 * 其辅助方法（{@code evaluateBlockDrops} / {@code parseLootTableDirectly} /
 * {@code extractItemFromEntry} / {@code getPoolEntryCount} / {@code extractConstantValue} /
 * {@code evaluateLootTableAtAge}）的调用点全部落回本类内，故一并搬来。
 * 本类<b>不持有自己的状态</b>，日志复用 {@link PlantingRecipeGenerator#LOGGER}
 * （同一 logger 实例，日志文本与拆分前逐字一致）。
 */
public final class LootRoller {

   private LootRoller() {
   }

   public static Map<Item, Integer> determineAllOutputItems(Block block, Item seed, MinecraftServer server) {
      try {
         ResourceLocation lootTableId = block.getLootTable();
         if (lootTableId == null) {
            PlantingRecipeGenerator.LOGGER.warn("Block {} has null loot table id, trying getDrops() fallback...", ForgeRegistries.BLOCKS.getKey(block));
            return evaluateBlockDrops(block, server);
         } else {
            LootTable lootTable = server.getLootData().getLootTable(lootTableId);
            if (lootTable == LootTable.EMPTY) {
               PlantingRecipeGenerator.LOGGER.warn("Block {}: loot table {} is empty, trying getDrops() fallback...", ForgeRegistries.BLOCKS.getKey(block), lootTableId);
               return evaluateBlockDrops(block, server);
            } else {
               ServerLevel level = server.overworld();
               if (level == null) {
                  PlantingRecipeGenerator.LOGGER.warn("Overworld not available when processing {}", ForgeRegistries.BLOCKS.getKey(block));
                  return Map.of();
               } else {
                  int maxAge = 0;

                  for (Property<?> prop : block.getStateDefinition().getProperties()) {
                     if (prop.getName().equals("age") && prop instanceof IntegerProperty ageProp) {
                        maxAge = ageProp.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
                        break;
                     }
                  }

                  PlantingRecipeGenerator.LOGGER.debug("Block {} has maxAge={}", ForgeRegistries.BLOCKS.getKey(block), maxAge);
                  Map<Item, Integer> bestItems = null;
                  int bestAge = -1;
                  int bestCount = 0;

                  for (int age = 0; age <= maxAge; age++) {
                     Map<Item, Integer> itemsAtAge = evaluateLootTableAtAge(lootTable, block, age, level);
                     PlantingRecipeGenerator.LOGGER.debug(
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
                     PlantingRecipeGenerator.LOGGER.info(
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
                     PlantingRecipeGenerator.LOGGER.warn(
                        "Block {}: getRandomItems returned empty for all ages, trying direct loot table parsing...", ForgeRegistries.BLOCKS.getKey(block)
                     );
                     Map<Item, Integer> fallback = parseLootTableDirectly(lootTable, block, server);
                     if (!fallback.isEmpty()) {
                        PlantingRecipeGenerator.LOGGER.info(
                           "Block {}: fallback parsing found {} items: {}",
                           new Object[]{
                              ForgeRegistries.BLOCKS.getKey(block),
                              fallback.size(),
                              fallback.entrySet().stream().map(ex -> ForgeRegistries.ITEMS.getKey((Item)ex.getKey()) + "x" + ex.getValue()).toList()
                           }
                        );
                        return fallback;
                     } else {
                        PlantingRecipeGenerator.LOGGER.warn("Block {}: no items extracted from any growth stage (0-{})", ForgeRegistries.BLOCKS.getKey(block), maxAge);
                        return Map.of();
                     }
                  }
               }
            }
         }
      } catch (Exception var12) {
         PlantingRecipeGenerator.LOGGER.error("Exception while analyzing loot table for block {}", ForgeRegistries.BLOCKS.getKey(block), var12);
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

         PlantingRecipeGenerator.LOGGER.debug("Block {}: evaluating getDrops() for ages 0-{}", ForgeRegistries.BLOCKS.getKey(block), maxAge);
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

               PlantingRecipeGenerator.LOGGER.debug(
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
               PlantingRecipeGenerator.LOGGER.debug("getDrops age={} failed for block {}: {}", new Object[]{age, ForgeRegistries.BLOCKS.getKey(block), var14.getMessage()});
            }
         }

         if (bestItems != null && !bestItems.isEmpty()) {
            PlantingRecipeGenerator.LOGGER.info(
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
            PlantingRecipeGenerator.LOGGER.warn("Block {}: getDrops() returned empty for all ages (0-{})", ForgeRegistries.BLOCKS.getKey(block), maxAge);
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
         PlantingRecipeGenerator.LOGGER.debug("Fallback loot table parsing failed for block {}: {}", ForgeRegistries.BLOCKS.getKey(block), var12.getMessage());
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
            PlantingRecipeGenerator.LOGGER.debug("Failed to extract item from LootItem entry: {}", var9.getMessage());
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
         PlantingRecipeGenerator.LOGGER.debug("Failed to extract children from entry: {}", var11.getMessage());
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
         PlantingRecipeGenerator.LOGGER.debug("Failed to get count from functions: {}", var20.getMessage());
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
         PlantingRecipeGenerator.LOGGER.debug("Failed to get pool rolls: {}", var18.getMessage());
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
         PlantingRecipeGenerator.LOGGER.debug("Failed to extract constant value: {}", var8.getMessage());
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
            PlantingRecipeGenerator.LOGGER.debug("getRandomItems age={} attempt {} failed: {}", new Object[]{age, i + 1, var14.getMessage()});
         }
      }

      return result;
   }
}
