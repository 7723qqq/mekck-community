---
navigation:
  parent: index.md
  title: 四种合金
  position: 3
item_ids:
- mekanism_extras:alloy_radiance
- mekanism_extras:alloy_thermonuclear
- mekanism_extras:alloy_shining
- mekanism_extras:alloy_spectrum
- mekanism_extras:enriched_osmium
- mekanism_extras:enriched_lead
- mekanism_extras:enriched_radiance
- mekanism_extras:enriched_shining
- mekanism_extras:enriched_spectrum
- mekanism_extras:enriched_thermonuclear
- mekanism_extras:dust_radiance
---

# 四种合金

> 数据来源：`recipes/metallurgic_infusing/alloy/*.json`、`recipes/infusion_conversion/**`、
> `recipes/enriching/enriched/*.json` 及其余相关配方，逐条读取。

四种新合金都用**冶金灌注机**（本体机器）生产，是本模组升级材的骨干。

## 一、合金链

| 产物 | 基材 | 灌注物 | 灌注量 |
|---|---|---|---:|
| **辐光合金** `alloy_radiance` | 本体**原子合金** | radiance | **40** |
| **热核合金** `alloy_thermonuclear` | 辐光合金 | thermonuclear | **120** |
| **闪耀合金** `alloy_shining` | 热核合金 | shining | **160** |
| **光谱合金** `alloy_spectrum` | 闪耀合金 | spectrum | **200** |

⇒ 链条是**一条直线**，不能跳级：

```
本体原子合金 → 辐光 → 热核 → 闪耀 → 光谱
              (绝对)  (至尊)  (寰宇支配)  (悖论无限)
```

## 二、⚠️ 灌注物必须先「富集」，否则亏 7/8

**这一步是整条链最容易亏材料的地方。**

冶金灌注机的**灌注转化**环节（把物品变成灌注物）有两条来源路线：

| 灌注物 | 直接投粉 | **先富集再投** | 差距 |
|---|---|---|---|
| **lead**（铅） | 铅粉 → **10** | 富集铅 → **80** | **8 倍** |
| **radiance**（辐光） | 辐光粉 → **10** | 富集辐光 → **80** | **8 倍** |
| **thermonuclear**（热核） | ❌ **没有这条配方** | 富集热核 → 40 | **只能富集** |
| **shining**（闪耀） | ❌ **没有这条配方** | 富集闪耀 → 40 | **只能富集** |
| **spectrum**（光谱） | ❌ **没有这条配方** | 富集光谱 → 50 | **只能富集** |

> 也就是说：**铅和辐光不富集只能拿到 1/8**；**热核 / 闪耀 / 光谱根本没有直接投粉的配方**，
> 不富集就一步都做不下去。

### 正确顺序是三步，中间的富集仓不能省

```
粉 ──[富集仓]──> 富集粉 ──[冶金灌注机·灌注转化]──> 灌注物 ──[冶金灌注机·灌注]──> 合金
       ↑
   省掉这一步 ⇒ 产量直接掉到 1/8
```

**算一笔账**：做 **1 个光谱合金**需要 radiance 40、thermonuclear 120、shining 160、spectrum 200。

| 灌注物 | 需求 | 用富集材料 | 若直接投粉 |
|---|---:|---|---|
| radiance | 40 | 富集辐光 **0.5 个** | 辐光粉 **4 个**（8 倍） |
| thermonuclear | 120 | 富集热核 **3 个** | —（无此配方） |
| shining | 160 | 富集闪耀 **4 个** | —（无此配方） |
| spectrum | 200 | 富集光谱 **4 个** | —（无此配方） |

## 三、六种富集材料怎么来

| 富集材料 | 输入 | 机器 |
|---|---|---|
| **富集铅** `enriched_lead` | 铅粉 ×1 | **富集仓** |
| **富集锇** `enriched_osmium` | 锇粉 ×1 | **富集仓** |
| **富集辐光** `enriched_radiance` | 辐光粉 ×1 | **富集仓** |
| **富集热核** `enriched_thermonuclear` | 富集辐光 + **熔融热核 120 mB** + 岩浆 120 mB（60 tick） | **加压反应室** `reaction` |
| **富集闪耀** `enriched_shining` | 富集热核 + **反物质 1**（2000 tick） | **反质子核合成器** `nucleosynthesizing` |
| **富集光谱** `enriched_spectrum` | 富集闪耀 + **反物质 1** | **压缩机**（锇压缩机） |

> **只有前三种能直接从粉富集**；后三种要走**堆级工艺**（反物质、熔融热核），
> 这也就是为什么四种合金对应的是绝对 → 至尊 → 寰宇支配 → 悖论无限。

> 顺带：**富集锇**不用于合金，它是拿来产**锇气体**的（`enriched_osmium` → **锇 1600 mB**，气体转化）。

### ⚠️ 上游前置：这两样东西**没有配方**，要建多方块

| 材料 | 怎么来 |
|---|---|
| **过热钠** `superheated_sodium` | **不是合成出来的** —— 由本体的**热力锅炉**（Thermoelectric Boiler，多方块）把钠加热得到（气体 ⇄ 流体经回旋式气液转换机互转） |
| **反物质** `antimatter` | **不是合成出来的** —— 由本体的**超临界移相器**（SPS，多方块）消耗**钋**产出；结晶成**反物质球**后，可再经任意机器的气体槽转回气体（1000 mB ⇄ 1 球） |

> 换句话说：**想做「富集热核」和「富集闪耀」，光有灌注机不够，得先把热力锅炉和 SPS 建起来。**
> 这两个都是本体的多方块结构，搭建方式见《通用机械》指南。
## 四、两种上游材料怎么来

| 材料 | 配方 | 机器 |
|---|---|---|
| **辐光粉** `dust_radiance` | 荧石粉 + **氧化铀 1** | 压缩机 |
| **熔融热核** `molten_thermonuclear` | **下界合金锭** + **过热钠 500 mB** + 岩浆 500 mB（900 tick、1000 J）→ **240 mB** | 加压反应室 |

> **熔融热核是气体**（不是流体），要占化学品罐与加压管道。

## 五、合金的用途

| 合金 | 用途 |
|---|---|
| **辐光合金** | **绝对**安装器材料（`mekanism_extras:alloys/radiance` 标签） |
| **热核合金** | 至尊等级相关；**堆叠升级**配方 |
| **闪耀合金** | 寰宇支配等级相关；**离子膜升级**配方 |
| **光谱合金** | 无限等级相关 |

## 六、容易踩的坑

1. 🔴 **不富集直接投粉 → 产量只有 1/8**（铅、辐光）；热核 / 闪耀 / 光谱**根本就没有投粉配方**；
2. **基础是本体原子合金**（`mekanism:alloy_atomic`），不是普通锭；
3. **四种合金串行**，想要光谱合金就得把前三种都做一遍；
4. **灌注量涨得快**（40 → 120 → 160 → 200），上游要提前铺产能；
5. **熔融热核是气体**，别往流体罐里灌；
6. 富集仓要**单独喂**：粉进富集仓、富集粉再进冶金灌注机 —— 别把粉直接倒进冶金灌注机的灌注槽。

另见 [[tiers.md|八级体系与安装器]]、[[materials/naquadah.md|硅岩全链]]、[[machines/factories.md|工厂]]。
