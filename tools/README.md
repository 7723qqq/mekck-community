# tools/ —— 一次性脚本与审计工具

这里的脚本**不参与构建**，`gradlew build` 不会调用它们。全部只用 Python 标准库，
无第三方依赖（`json` / `os` / `re` / `sys` / `struct` / `zlib` / `zipfile` / `glob` /
`argparse` / `subprocess` / `collections`）。

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

```bash
python tools/conditional_recipe_audit.py            # 预览
python tools/conditional_recipe_audit.py --write    # 落盘
```

### `predict_residual_recipe_errors.py`

按**实例实际安装的模组集合**模拟 Forge 的两道闸门，预测还有哪些配方会失败。

```bash
python tools/predict_residual_recipe_errors.py <mods-dir> [--client-jar <jar>]
```

已知局限：`forge:item_exists` 只按 modid 判断，无法离线确认具体物品是否注册。
真正的确认要启动客户端看 `logs/latest.log`。

### `verify_only_conditions_changed.py`

拿 `git diff` 对比 HEAD，**证明**每个被改动的配方除了顶层 `conditions` 键之外
payload 完全没变。条件化那轮的自检工具，无参数。

```bash
python tools/verify_only_conditions_changed.py
```

---

## 二、资产生成（占位素材，非美术成品）

两个脚本都是**程序化画 16×16 贴图** + 生成物品模型 / 配方 / lang 条目。
产物直接写进 `src/main/resources/`，属于**一次性生成**——重跑会覆盖同名文件。

### `gen_skewer_assets.py`

生成 8 个穿串物品（`mekck:skewering`）：贴图、模型、tag、配方、lang。

### `gen_grilling_assets.py`

生成 8 个烤制物品（`mekck:grilling`）：贴图、模型、配方、lang。

> 两者都**刻意不设 `food` 属性**——营养值是平衡决策，不是机制决策。
> 见 `docs/STATUS.md` §三「内容缺口」。

---

## 三、模型烘焙

### `convert_bioreactor_obj.py`

把生物反应炉的 3 层 block model JSON 合并成单个 OBJ 网格。

原因：vanilla baked model 的元素坐标被限制在 `[-16, 32]`（跨度 48px），而反应堆高 48px，
所以资产被切成 3 个 16px 层模型、由 `BioreactorRenderer` 用 `translate(0, i, 0)` 堆叠。
烘焙成 OBJ 可绕开该限制，**几何本身完全不变**。

```bash
python tools/convert_bioreactor_obj.py
```

输出：`src/main/resources/assets/mekck/models/mesh/bioreactor.obj`
