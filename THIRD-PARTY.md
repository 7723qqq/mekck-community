# 第三方资源声明 / Third-Party Notices

MekCK 自身不包含任何第三方模组的**代码**。以下列出仓库内**再分发的资源**及其许可证。

MekCK itself contains no third-party mod **code**. The following lists **redistributed assets**
in this repository together with their licenses.

---

## 1. 纹理资源 / Redistributed Textures

路径 `src/main/resources/assets/mekck/textures/block/vendor/` 下的纹理**复制自**以下模组，
用于让 MekCK 的工厂方块在外观上与对应模组保持一致。源代码中未复制任何第三方代码。

The textures under `src/main/resources/assets/mekck/textures/block/vendor/` are **copied from**
the mods below, so that MekCK's factory blocks visually match the corresponding mods.
No third-party source code has been copied.

### Mekanism

- 项目 / Project: https://github.com/mekanism/Mekanism
- 版权 / Copyright: (c) 2017-2023 Aidan C. Brady
- 许可证 / License: **MIT**
- 使用的资源 / Assets used:
  - `vendor/mekanism/block/crusher/`
  - `vendor/mekanism/block/energized_smelter/`
  - `vendor/mekanism/block/factory/`
  - `vendor/mekanism/block/models/`
  - `vendor/mekanism/block/precision_sawmill/`
  - `vendor/mekanism/item/upgrade_gas.png`

### Mekanism Extras

- 项目 / Project: https://github.com/lostmyself8/Mekanism-Extras
- 版权 / Copyright: (c) 2024 lostmyself
- 许可证 / License: **MIT**
- 使用的资源 / Assets used:
  - `vendor/mekanism_extras/block/factory/`

### MIT 许可证全文

```
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. 构建期依赖（不随仓库分发）/ Build-time Dependencies (NOT redistributed)

以下模组仅作为**编译期 / 运行期依赖**引用，其 jar **不在本仓库中**，也**不随发布版分发**。

The following mods are referenced as **compile-time / runtime dependencies only**.
Their jars are **not part of this repository** and are **not redistributed** with releases.

| 模组 / Mod | 许可证 / License | 关系 / Relation |
|---|---|---|
| Mekanism | MIT | 必需前置，使用其 API / Required dependency, uses its API |
| Mekanism Extras | MIT | 可选，提供绝对~悖论无限安装器 / Optional |
| Farmer's Delight | MIT | 必需前置，配方类型 / Required dependency, recipe types |
| Barbeque's Delight | — | 可选，穿串/烧烤配方 / Optional |
| Avaritia (Re-Avaritia) | MIT | 可选，高阶合成 / Optional |
| Avaritia's Delight | MIT | 可选，刀具与厨锅 / Optional |
| Kaleidoscope Cookery | BSD-3-Clause **+ CC BY-NC** | 可选，**仅通过 API 联动，未再分发其任何资源** / Optional, **API-only, no assets redistributed** |
| Kaleidoscope Grilling | — | 可选 / Optional |
| Applied Energistics 2 | LGPL-3.0 | 可选，ME 网络联动 / Optional |
| GuideME | LGPL-3.0 | 可选，游戏内指南 / Optional |
| Industrial Foregoing | — | 可选，肉汤发电 / Optional |
| Some Assembly Required | — | 可选，三明治联动 / Optional |
| Simply Tea | — | 可选，茶饮联动 / Optional |

> ⚠️ **注意**：Kaleidoscope Cookery 采用 **CC BY-NC（署名-非商业性使用）** 许可。
> MekCK 未再分发其任何资源文件，仅通过公开 API 读取其配方类型；但若你计划将 MekCK 用于**商业用途**，
> 请自行确认此类 API 联动是否满足其条款。
>
> ⚠️ **Note**: Kaleidoscope Cookery is licensed under **CC BY-NC**. MekCK does not redistribute
> any of its assets and only reads its recipe types through the public API. If you intend to use
> MekCK **commercially**, verify that such API-level integration complies with its terms.

---

## 3. 开发期参考资料 / Development-time References

开发过程中参考过其它开源模组的实现（仅用于理解 API 用法），**相关源码不在本仓库中**。
所有 MekCK 源代码均为原创实现，未复制任何第三方代码。

During development, other open-source mods were consulted to understand API usage.
**Their sources are not part of this repository.** All MekCK source code is an original
implementation; no third-party code has been copied.
