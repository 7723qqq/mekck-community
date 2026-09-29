---
navigation:
  parent: machines/overview.md
  title: 联动机器
  position: 14
  icon: mekck:sushi_maker
categories:
- machines
item_ids:
- mekck:sushi_maker
- mekck:average_slicer
- mekck:rice_ball_maker
- mekck:curd_maker
- mekck:dehydrator
- mekck:fermenter
- mekck:steamer
- mekck:winery
- mekck:juicer
- mekck:bakery_oven
- mekck:stove
- mekck:cocktail_shaker
- mekck:blender
- mekck:tea_brewer
---
# 联动机器（妖怪们的归家 / 樱途旅事 / let's do / 烘焙坊 / 酒馆）

联动机器均为单等级机器（无工厂版本），用于执行其它模组的机器配方。

## 妖怪们的归家

* **智能料理台**（mekck:sushi_maker）：料理台配方自动化（cuisine）
* **平均切段机**（mekck:average_slicer）：寿司卷切段（2×速度 / 0.5×能耗），并支持烘焙坊面包切片（bread_knife）
* **饭团成型机**（mekck:rice_ball_maker）：做米饭 + 饭团
* **食品脱水机**（mekck:dehydrator）：晾干架配方，并支持沉浸农艺晾晒（drying）；运行时产热并显示机身温度
* **发酵机**（mekck:fermenter）：发酵罐配方（含流体），并支持烘焙坊发酵（fermentation）与盛节精酿、喝啤酒啦的酿造配方
* **智能蒸箱**（mekck:steamer）：蒸笼配方；运行时产热并显示机身温度

## 樱途旅事

* **凝乳成型机**（mekck:curd_maker）：凝乳块 → 奶酪轮（免 10 分钟等待），并支持青青草甸奶酪配方（奶桶 + 凝乳酶）

## let's do 系列

* **智能陈酿机**（mekck:winery）：葡园酒香葡萄酒发酵配方（含酒馆酒桶批次酿造与空瓶分装）；果汁以「类型 + 液位」结算（每件果汁 25 液位、上限 100），界面显示果汁液位条（装葡园酒香时复用其绘制，未装则自绘），潜行右键机器可清空液位
* **鲜果榨汁机**（mekck:juicer）：苹果 → 苹果汁（两步合一），并支持酒馆榨汁槽（pressing_tub）配方
* **糕点烘焙机**（mekck:bakery_oven）：馥郁烘焙烘焙站配方，并支持烘焙坊烤箱（oven）与咖啡（coffee）配方；运行时产热并显示机身温度
* **智能烤炉**（mekck:stove）：沉浸农艺灶台配方（农夫面包等），并支持烘焙坊石窑（stone_kiln）、烤肉架（roaster）、煨茶酝露茶壶（kettle_brewing）与青青草甸烹饪锅（cooking）配方；运行时产热并显示机身温度
* **电力研磨机**（mekck:electric_grinding_machine）：见「电力研磨机与研磨工厂」页

## 烘焙坊（bakeries）

* **搅拌机**（mekck:blender）：搅拌配方（1~9 个输入，共 9 个输入槽）

## 酒馆（kaleidoscope_tavern）

* **调酒机**（mekck:cocktail_shaker）：调酒配方（3 个酒类/原料，酒类需优质以上，即 BrewLevel ≥ 4）

## 温度系统

部分联动机器接入通用机械（Mekanism）的热容量模型，参数与电阻型加热器一致（热容量 100 J/K、电能到热量效率 0.6），并与相邻热力设备双向传导：

* **加热类机器**：糕点烘焙机、智能烤炉、智能蒸箱、食品脱水机——运行时按实际耗电产热，温度不影响加工，界面显示机身温度；
* **急冻制冰机**：见「急冻制冰机与制冰工厂」页。

## 简单的茶（simplytea）

* **智能茶艺机**（mekck:tea_brewer）：把「茶杯 + 茶包 + 热茶壶 → 杯装茶」的手工合成搬进机器，可量产绿茶、红茶、蒲公英花茶、印度奶茶、黄春菊花茶、紫颂花茶、热可可等全部杯装茶。
  * 3 个输入格**位置无关匹配**，材料齐备即自动冲泡（200 tick/份，20 FE/t）。
  * 只接受「简单的茶」自己的产物配方，不会把原版的上万条合成配方一并吞进来。
  * 需要安装「简单的茶」才有配方可做。
  * 注意：未烧制的茶杯 / 茶壶烧制成成品、以及茶叶烟熏成红茶属于**熔炉 / 烟熏配方**，由晶钛矩阵以上等级的**烧烤工厂**处理。
