# mekck 配方条件化改造报告

生成脚本：`tools/conditional_recipe_audit.py --write`

## 机制

Forge 在 `RecipeManager` 加载循环里、**任何序列化器运行之前**，检查每个配方 JSON 的顶层 `conditions`；不满足则 `continue`，配方被安静跳过，不产生日志错误。条件数组语义为 AND（`CraftingHelper.processConditions` 遇首个 false 即返回 false）。

因此不需要 `forge:conditional` 包装：顶层 `conditions` 是更直接、diff 更小的写法，且对三类失败（未知配方类型 / 未知物品 / 写错 schema 的 conditions）同时有效。

## 统计

| 项 | 值 |
|---|---|
| 扫描配方文件 | 545 |
| 已是 forge:conditional（未动） | 8 |
| 跳过（另一 AI 阶段 1 占用） | 1 |
| 本次条件化 | 454 |

## 被门控的外部 mod

| 配方文件数 | modid |
|---|---|
| 59 | `youkaishomecoming` |
| 58 | `createcafe` |
| 54 | `fruitsdelight` |
| 38 | `farmersrespite` |
| 35 | `mekanism_extras` |
| 35 | `mekmm` |
| 33 | `industrialforegoing` |
| 28 | `immersiveengineering` |
| 27 | `avaritia` |
| 17 | `coffeecraft` |
| 14 | `trailandtales_delight` |
| 13 | `ends_delight` |
| 13 | `twilightdelight` |
| 12 | `alexscaves` |
| 11 | `ice_and_fire_delight` |
| 10 | `twilightforest` |
| 10 | `vinery` |
| 9 | `farm_and_charm` |
| 9 | `aquaculture` |
| 7 | `mynethersdelight` |
| 7 | `muffins_thaidelight` |
| 7 | `iceandfire` |
| 7 | `candlelight` |
| 7 | `aquaculturedelight` |
| 5 | `create` |
| 5 | `avaritia_delight` |
| 5 | `cavedelight` |
| 5 | `oceansdelight` |
| 4 | `pineapple_delight` |
| 3 | `bakery` |
| 2 | `kaleidoscope_cookery` |
| 2 | `pasterdream` |
| 1 | `miners_delight` |
| 1 | `mekanismgenerators` |
| 1 | `some_assembly_required` |
| 1 | `roasted` |
| 1 | `tconstruct` |
| 1 | `drinkbeer` |
| 1 | `ccw` |
| 1 | `artifacts` |

## 条件化解决不了、需要单独决策

- `src/main/resources/data/mekck/recipes/crafting_shaped/frost_cold_brew_upgrade.json` — `minecraft:powder_snow`

  `minecraft:powder_snow` 在 1.20.1 **不是物品**。客户端 jar 里只有
  `assets/minecraft/models/block/powder_snow.json`，没有 `models/item/powder_snow.json`，
  说明 `Blocks.POWDER_SNOW` 没有注册 BlockItem，`CraftingHelper.getItem` 必然抛
  `Unknown item`。可用的替代是 `minecraft:powder_snow_bucket`（或改用 Mek 的冷冻流体）。
  这是配方设计决策，条件化无法绕过——任何条件要么让它永远不加载，要么仍然报错。

## 改造前的问题归因（实例日志实测）

`1.20.1-Forge_47.4.23/logs/latest.log` 中 `Parsing error loading recipe` 共 **924 行 = 462 个不同配方 × 2**
（资源加载跑两轮）。三类失败机制：

| 类别 | 数量（去重） | 机制 | 堆栈依据 |
|---|---|---|---|
| 未知配方类型 | 158 | `type` 的序列化器来自未安装的 mod | `RecipeManager.fromJson:169` |
| `conditions` 写错 schema | 155 | 用了 advancement 的 `condition` / `minecraft:all_of` / `terms`，而非 Forge 配方的 `type` / `mod_loaded` / `modid` | `CraftingHelper.getCondition:228` |
| 未知物品 | 149 | `type` 合法，但 `item` 指向未注册物品 | `CraftingHelper.getItem:160` / `ShapedRecipe:286` |

第二类**全部是 mekck 自己的 `beverage_assembly` 配方**，此前一直被误当成"外部 mod 缺失"。
Forge 对每个配方都调用 `CraftingHelper.processConditions(json, "conditions", ctx)`，
所以那个 `conditions` 块不是被忽略，而是被当成 Forge 条件解析后抛异常。

462 个失败配方的归属：**422 个在本仓库**（本次修复对象），**40 个来自用户世界存档的数据包**
`saves/新的世界/datapacks/mekck_planting/`（`data/mekck/recipes/plant_ie/*` 20 个 +
`data/mekmm/recipes/planting/*` 20 个）。后者不在本仓库，属于世界本地内容，未做修改。

本次条件化 454 个文件 = 422 个当前报错 + 32 个仅含外部 mod 的 tag 引用。
后者不会在解析期抛异常（tag 是惰性的），但对应配方实际做不出来，属于静默失效，一并修掉。

## 改造后预测（按实例已装 mod 集合模拟 Forge 条件判定）

`tools/predict_residual_recipe_errors.py <mods目录>`：

```
clean (will load)   : 120
skipped by condition: 424
residual failures   : 1
  creative_upgrade_from_49_foods.json -> recipe_type avaritia
```

残留的 1 个是**另一个 AI 的阶段 1 Task 8 文件**，本次按约定未动。它的
`"type": "avaritia:shapeless_table"` 指向 Avaritia 的序列化器，而 Avaritia 从未在
`mods.toml` 中声明为依赖。仅把产物从 `mekanism_extras:upgrade_creative` 换成
`mekck:upgrade_randomize` **不足以修复**——`type` 仍需改成 mekck 自有或原生的类型，
否则该配方仍会报错。

预测脚本的已知局限：`forge:item_exists` 只按 modid 判断是否通过，无法离线确认具体物品
是否注册。`mekanism_extras` 在实例里已安装（1.5.0），但其 `infinite/cosmic/supreme/absolute_planting_factory`
只有 assets 与战利品表、没有注册物品，这 8 个配方在日志里确认为失败——`item_exists`
会让它们**安静跳过**（符合预期），但预测脚本会把它们误报为"干净"。

## 验证

`tools/verify_only_conditions_changed.py` 逐文件比对 `git show HEAD:<file>` 与工作区，
确认 454 个文件中**除 `conditions` 键外逐键完全一致**（454/454，0 问题）。

真实的运行期验证需要启动客户端，看 `logs/latest.log` 里
`Parsing error loading recipe` 是否从 462 降到只剩上表那 1 个。

