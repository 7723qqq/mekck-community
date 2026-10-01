# tools/ —— 一次性脚本与资产管线

这些脚本**不参与构建**，`gradlew build` 不会调用它们。

## 依赖分三档（README 曾声称"全部只用 Python 标准库"，那是错的）

| 档位 | 脚本 | 怎么跑 |
|---|---|---|
| **纯标准库** | `conditional_recipe_audit.py`、`predict_residual_recipe_errors.py`、`verify_only_conditions_changed.py`、`gen_skewer_assets.py`、`gen_grilling_assets.py`、`gen_bioreactor_texture.py`、`convert_bioreactor_obj.py`、`finalize_bioreactor_obj.py`、`preview_bioreactor_mc.py` | `python tools/<name>.py` |
| **需 Blender** | `blender_build_true_mek_bioreactor.py`、`blender_export_true_mek_bioreactor.py` | `import bpy` / `bmesh` / `mathutils`，**只能**在 Blender 的 Python 里跑（`blender -b -P`）。系统 Python 装不了这些 |

资产管线已经收敛到**一条**自洽链路，两套 Blender 脚本分别负责"建模"和"导出"，
中间用 `.blender-backup/bioreactor_true_mek.blend` 传递（两个 `blender -b -P`
是不同进程，建模器产出的是内存里的集合，不存盘导出器就看不到）。
`scratch/` 已全部 gitignore，里面的 `create_true_mek_atlas.py` 已被
`gen_bioreactor_texture.py` 取代（纯标准库、读同一张格位表）。

所有脚本的仓库根都从 `__file__` 推导（`os.path.dirname(os.path.dirname(...))`），
可以从任意工作目录调用——除了下面明确标注"须在仓库根跑"的两个。

按用途分三类。

---

## 一、配方条件化（2026-09-29 那轮 462 条失败配方的处置）

背景见 `docs/STATUS.md` §四 与 `docs/audit/2026-09-29-conditional-recipe-report.md`。

### `conditional_recipe_audit.py`

给 `src/main/resources/data/mekck/recipes/` 下的配方补 Forge 顶层 `conditions`，
让外部模组缺失时配方**干净跳过**而不是抛 `Parsing error`。

只发两种条件：`forge:mod_loaded`（命名空间有东西）与 `forge:item_exists`
（模组装了但那个具体物品可能没注册）。

**默认只读**，加 `--write` 才原地改写（外科式文本编辑，diff 只落在 conditions 块）。
写入前先在内存里校验，校验不过**不落盘**；手写的非本工具条件
（`forge:false` / `forge:not` / 第三方类型）原样保留。

```bash
python tools/conditional_recipe_audit.py            # 预览
python tools/conditional_recipe_audit.py --write    # 落盘
```

### `predict_residual_recipe_errors.py`

按**实例实际安装的模组集合**模拟 Forge 的闸门，预测还有哪些配方会失败。

```bash
python tools/predict_residual_recipe_errors.py <mods-dir> [--client-jar <jar>]
```

条件求值遵循 Forge 1.20.1 的真实 schema（取自
`net.minecraftforge.common.crafting.conditions.*Condition$Serializer`）：
`forge:and` / `forge:or` 用 `"values"` 数组，`forge:not` 用 `"value"` 单对象。
未识别的条件类型保守判为**不满足**，从而落进 residual 等人看见。

`--client-jar` 用于发现本 MC 版本根本没注册的 `minecraft:` id
（枚举 jar 里的 `assets/minecraft/models/item/*.json`）——这类配方条件化救不了，
需要单独做内容决策。

已知局限：`forge:item_exists` 只按 modid 判断，无法离线确认具体物品是否注册。
真正的确认要启动客户端看 `logs/latest.log`。

### `verify_only_conditions_changed.py`

拿 git 对比 HEAD，**证明**每个被改动的配方除了顶层 `conditions` 之外 payload 完全没变。
覆盖三类文件：已跟踪已修改（比 payload）、已删除（无基线，只报名字）、
未跟踪新文件（无基线，只校验 conditions 形状）。`git diff` 单独用会漏掉后两类。

> 须在仓库根跑（用相对路径 `src/main/resources/data` 调 git）。

---

## 二、资产生成（占位素材，非美术成品）

程序化画贴图 + 生成物品模型 / 配方 / lang 条目。产物直接写进 `src/main/resources/`，
属于**一次性生成**——重跑会覆盖同名文件。

### `gen_skewer_assets.py`

生成 8 个穿串物品（`mekck:skewering`）：贴图、模型、tag、配方、lang。

### `gen_grilling_assets.py`

生成 8 个烤制物品（`mekck:grilling`）：贴图、模型、配方、lang。

> 两者都**刻意不设 `food` 属性**——营养值是平衡决策，不是机制决策。
> 见 `docs/STATUS.md` §三「内容缺口」。

> ⚠️ 两个脚本都用 `d.setdefault` 写 lang，所以**重跑修不了已经存在的错译**；
> 并且它们会把整个 lang 文件（~500 行）经 `json.dump` 重写一遍。当前 round-trip
> 恰好字节一致，但任何一次手工改动都会被静默规范化。

### `gen_bioreactor_texture.py`

绘制生物反应堆的 256×256 材质图集（`MAT_Mek_*`）。格位表从
`tools/bioreactor_atlas.json` 读，本脚本只读不猜。空白格填洋红，脚本退出前
会检查有没有残留洋红像素，漏画的材质会直接失败而不是悄悄留个洞。

```bash
python tools/gen_bioreactor_texture.py
```

---

## 三、生物反应炉模型管线

几何由 Blender 产出，OBJ 落到
`src/main/resources/assets/mekck/models/mesh/bioreactor.obj`，
由 `BioreactorRenderer` 按 `bioreactor_layer0/1/2` 三个分组、
`translate(0, i, 0)` 堆叠渲染。

> 为什么切成 3 层：vanilla baked model 的元素坐标被限制在 `[-16, 32]`
> （跨度 48px），而反应堆高 48px。烘焙成 OBJ 是为了绕开该限制，
> **几何本身不变**。

### 管线（唯一一条，已收敛）

```bash
# 1) 建模：产出 COL_Bioreactor_TrueMek，并存盘给第 2 步
blender -b -P tools/blender_build_true_mek_bioreactor.py

# 2) 导出 + UV 投影 + 切三层，并写出 bioreactor_atlas.json
blender -b -P tools/blender_export_true_mek_bioreactor.py

# 3) 画图集（读第 2 步写出的格位表）
python tools/gen_bioreactor_texture.py

# 4) 规整分组名（Blender 的 obj_export 会加 .001 后缀）
python tools/finalize_bioreactor_obj.py

# 5) 只读验收
python tools/preview_bioreactor_mc.py
```

第 1、2 步是两个进程：建模器产出的是内存里的集合，不存盘导出器就看不到，
所以建模器会 `save_as_mainfile` 到 `.blender-backup/bioreactor_true_mek.blend`，
导出器发现集合缺失时自动打开它。

导出器**先校验再落盘**：层数、每层局部 Y∈[0,1]、水平足迹∈[-1.5,1.5] 任一不过
就 `raise`，不碰现有的 OBJ；通过后先导到 `.part` 再 `os.replace` 原子替换
（`build.gradle` 的 `processResources` 也排除了 `**/*.part`，残留不会被打进 jar）。
未登记的材质直接 `KeyError`，不会静默落回默认格（那会让贴图串色且毫无告警）。

### 关于绕序：`finalize_bioreactor_obj.py` 的主要价值不是绕序

`BioreactorRenderer` 用的是 `MekCkRenderTypes.objCutoutNoCull`，
其 `CompositeState` 里 `.setCullState(NO_CULL)` —— **背面剔除是关掉的**。
所以内翻的面照样被光栅化，不会变成"看不见的洞"，真实后果只是**光照不对**
（法线朝内导致该面按错误方向受光）。

它真正不可替代的原因是**分组名**：Blender 的 `obj_export` 会给重名对象加
`.001` 后缀，而 `BioreactorRenderer.java:89` 是
`if (!mesh.hasGroup(group)) continue;` ——三个分组名对不上就**三层全静默跳过，
什么都不渲染，日志里也没有**。这就是它 `sys.exit(1)` 的价值：宁可让流水线停下，
也不要留下一个 `bioreactor_layer0_bioreactor_layer0.001` 的 OBJ。

### 已删除的脚本

- `blender_bioreactor_build.py`、`blender_export_bioreactor_mc.py` —— 早期管线，
  用 `BODY_*` / `MAT_Alterra_*` 命名空间 + **不夹紧的重心分带**（layer1 局部 Y
  会远超 `[0,1]`），且是 `bioreactor_atlas.json` 的另外两个写入者。
- `blender_export_deep_mek_bioreactor.py` —— 孤儿：要求 `COL_Bioreactor_DeepMek`，
  仓库里没有任何脚本创建该集合；`ATLAS` 还缺 4 个建模脚本实际在用的材质。

三者都写同一个 OBJ，其中两个还写同一个 atlas JSON，与主线直接冲突。

### `convert_bioreactor_obj.py`

把生物反应炉的 block model JSON 合并成单个 OBJ 网格——这是**另一条独立路线**
（不经过 Blender，也不涉及材质图集）。本目录里唯一做到"先校验绕序与三角形数、
再 `open(OUT,"w")`"的写入器。

### `bioreactor_atlas.json`

`blender_export_true_mek_bioreactor.py` 写、`gen_bioreactor_texture.py` 读的
共享格位表，**唯一写入者**。格式是矩形而非均分格位（多数材质 64×64，
警示条纹与终端屏 128×128，均分网格表达不了）：

```json
{"size": 256.0, "inset": 2.0,
 "materials": {"MAT_Mek_SlateSteel": [x0, y0, x1, y1], ...}}
```
