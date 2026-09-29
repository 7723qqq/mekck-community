---
navigation:
  title: 通用机械指南
  position: 0
item_ids:
# 通用机械指南手册（mekck:guide_handbook）：手持按 G 应打开本指南，与右键行为一致。
# 注意：GuideME 的 ItemIndex 是**唯一索引**（UniqueIndex），一个物品只能属于一个页面，
#       所以这里声明后，其它页面**不能再声明同一个物品**，否则有一边会作废。
- mekck:guide_handbook
---

# 通用机械（Mekanism）指南

本指南面向 **通用机械 10.4.16.80**（本整合包所用版本），内容全部按 jar 内数据（配方 JSON、语言文件、配置默认值与字节码常量）整理，力求数字可核对。

## 基础

* [[basics/ores.md|矿石与金属]] —— 三条矿物处理链的完整数值
* [[basics/multiplication.md|矿物处理倍率阶梯]] —— 加机器 = 加倍数的 1× ~ 5× 阶梯
* [[basics/energy.md|能量与发电]] —— J / FE 换算、机器耗能、发电机与储能

## 机器

* [[machines/_index.md|机器分类索引]]
* [[machines/overview.md|机器与配方总览]] —— 配方类型 → 机器对照
* [[machines/ore_processing.md|矿石加工机器速查]]
* [[machines/chemical.md|化学系与气体产线]]
* [[machines/factories.md|工厂与并行处理]] —— 9 类 × 4 级 = 36 个工厂
* [[machines/logistics.md|管道、线缆与物流]] —— 五种管道、四档等级、QIO

## 操作

* [[guides/operations.md|侧面配置、升级与红石]]

## 其它

* [[faq.md|常见问题]]

## 本指南的数据来源

* 版本：`Mekanism-1.20.1-10.4.16.80.jar`（方块 199 条 / 物品 168 条语言条目）
* 发电数据：`MekanismGenerators-1.20.1-10.4.16.80.jar`（`GeneratorsConfig` 默认值）
* 能量换算依据：`GeneralConfig.feConversionRate` 的配置注释，**1 FE = 2.5 J**
* 衍生模组（扩展 / 更多机器）将另立指南，见后续版本

> 打开方式：手持通用机械的物品按 GuideME 热键（默认 **G**），或右键 **通用机械指南手册**。
