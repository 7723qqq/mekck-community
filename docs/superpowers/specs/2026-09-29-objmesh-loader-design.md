# OBJ 网格加载器移植到 mekck —— 设计文档

- 日期：2026-09-29
- 状态：**已实施**（本文档已与最终实现对齐）
- 目标工程：`D:\mc\mod\mekck`（MC 1.20.1 / Forge 47.4.16）
- 源工程：`D:\mc\霓虹科技V 7.6\模块化\mek_adapter`（MC 1.12.2 / Forge 14.23.5）
- 源文件：`mek_adapter/src/main/java/cn/neontest/mekadapter/client/mesh/ObjMesh.java`（272 行）

## 1. 目标

为 mekck 引入通用的外部 OBJ 网格加载与渲染能力，把 1.12.2 集成包（mek_adapter）中已验证的
"从资源加载任意 OBJ → 按分组绘制" 管线移植到 1.20.1，**并完成对生物反应炉的接入**。

接入动机：`BioreactorRenderer` 受 vanilla baked model 的元素坐标限制（`[-16, 32]`，跨度 48px），
不得不把 48px 高的几何切成 3 个 16px 层模型叠加绘制。OBJ 管线绕开该限制。

几何**完全未变**——由现有 58 个 box 烘焙而来。这是最重要的验证条件：改造前后视觉应一致，
任何差异都直接指向 UV 映射、法线绕序或坐标合并的缺陷。

## 2. 范围与交付物

| # | 交付物 | 状态 |
|---|---|---|
| 1 | `cn.ism.mekck.client.mesh.ObjMesh` | 新建 |
| 2 | `cn.ism.mekck.client.mesh.ObjMeshLoader` | 新建 |
| 3 | `cn.ism.mekck.client.mesh.ObjMeshRenderer` | 新建 |
| 4 | `MekCkRenderTypes.objSolid(ResourceLocation)` | 改现有类（+1 方法） |
| 5 | `tools/convert_bioreactor_obj.py` + `assets/mekck/models/mesh/bioreactor.obj` | 新建 |
| 6 | `BioreactorRenderer` 改用 ObjMesh 渲染 | 改现有类 |
| 7 | `TestObjMeshParse` / `TestObjMeshRender` / `TestBioreactorObjAsset` | 新建测试类 |
| 8 | 移除 `UniversalCuttingMachine.onRegisterAdditionalModels` | 改现有类（−1 方法） |

不包含：不改变反应堆几何（不引入高模）；不移植 mek_adapter 的其他消费者；
不实现动画、LOD、逐面光照、背面剔除策略；不修改 `blockstates/bioreactor.json`；
**不删除** 3 个源 JSON（它们是转换脚本的输入，删除会让脚本失去可重跑性）。

## 3. 已核实的资产事实

- 三层各 14 / 30 / 14 个 box，共 **58 个**。
- 每层 y 范围均为 `[0,16]`，靠 `poseStack.translate(0, i, 0)` 堆叠成 48px（3 block）。
- x/z：layer0/2 为 `[0,32]`，layer1 为 x`[0,27]` z`[5,27]`。
- 贴图 `block/mekck/bioreactor/bioreactor.png` = **128×128**。
- `ambientocclusion: false` → 无顶点 AO 数据，转换**无损**。
- 6 种面朝向齐全，**零个面带 `rotation`**。
- `parent` 均为 `block/block`，不含几何 → 无几何继承。
- `blockstates/bioreactor.json` 的 8 个 variant **全部指向 layer0**。
- **面记录 338 条**（不是 58×6=348：资产中确有 box 省略了不可见面）→ 676 个三角形，
  分配为 layer0=160、layer1=350、layer2=166。

## 4. 架构

```
cn.ism.mekck.client.mesh
├── ObjMesh          不可变数据 + 纯解析器。零 MC / GL 依赖，可在普通 JVM 单测。
├── ObjMeshLoader    ResourceLocation → ObjMesh。持有缓存，实现失效与失败冷却。
└── ObjMeshRenderer  ObjMesh → VertexConsumer 顶点。不持有状态。
```

包名选 `client.mesh` 而非既有的 `client.model`：后者属于 vanilla `ModelPart` 体系
（几何由 `CubeListBuilder` 在 Java 中构建），数据来源与生命周期均不同，混放会误导调用方。

### 4.1 ObjMesh

从源文件移植（**仅解析部分**）：`STRIDE = 8`（`x,y,z,u,v,nx,ny,nz`）、
`DEFAULT_GROUP`、`parse(BufferedReader)`（`v`/`vt`/`vn`/`g`/`f`、扇形三角化、负数相对索引、
`#` 与空行跳过、空分组不入结果、坏索引**整面丢弃**）、`vt` 的 `1.0f - v` 翻转、
`LinkedHashMap` 分组顺序。

新增两个入口供 `ObjMeshLoader` 使用：`empty()` 与 `of(Map)`（后者剔除空分组）。

不移植：display list 字段、`compile()`、`release()`、`load()`、`loadFailedUntil`。

### 4.2 ObjMeshLoader

`Map<ResourceLocation, ObjMesh>` 缓存，**只缓存成功结果**。
读取走 `ResourceManager.getResource(rl)`（`Optional<Resource>`，取资源包栈中优先级最高的一份）。
失败记 ERROR 并置 5 秒冷却；冷却期内重复请求直接返回空网格，不重试、不刷日志。
`Minecraft` 不可用时同样降级为空网格而非抛异常。

### 4.3 ObjMeshRenderer

**1.20.1 `VertexConsumer` 签名经 `javap` 核实**（`forge-1.20.1-47.4.16_mapped_official_1.20.1.jar`）：

```java
PoseStack.Pose pose = poseStack.last();   // pose.pose() -> Matrix4f, pose.normal() -> Matrix3f
consumer.vertex(pose.pose(), x, y, z)
         .color(0xFF, 0xFF, 0xFF, 0xFF)
         .uv(u, v)
         .overlayCoords(overlay)
         .uv2(light)                       // 1.20.1 中 light 走 uv2，不是 lightCoords
         .normal(pose.normal(), nx, ny, nz)
         .endVertex();
```

- `consumer = buffers.getBuffer(type)`。
- **每个顶点调用一次 `endVertex()`**（它提交当前顶点并开始下一个），不是每 3 个顶点一次。
- **顶点色承载逐面明暗**：OBJ 不携带顶点色，但顶点色必须写——它还原 vanilla baked 模型里
  由 `BakedQuad` 预先烘焙的那份明暗（**上 1.0 / 南北 0.8 / 东西 0.6 / 下 0.5**），
  见 `ObjMeshRenderer.shadeOf`。用**模型空间**法线判定，与原实现一致（不随渲染旋转改变）。
- **法线**：`normal(Matrix3f, float, float, float)` 在 1.20.1 中存在（`default` 方法），
  法线随 `PoseStack` 变换——等价于源实现依赖 GL 立即模式自动处理法线的行为。
- 分组不存在 → 静默跳过；`null` 网格 → 直接返回。
- "分组即骨骼"：调用方对每组自行 `pushPose()` / 变换 / `renderGroup` / `popPose()`。

### 4.4 RenderType 工厂

`MekCkRenderTypes.objSolid(ResourceLocation)`，沿用该类 `getIce()` 的 `CompositeState.builder()` 写法：
格式 `DefaultVertexFormat.NEW_ENTITY`、shader **`RENDERTYPE_SOLID_SHADER`**、
state 为 `LIGHTMAP` + `OVERLAY` + 不透明 `CULL` + 传入纹理。

**必须用 block shader 而不是 entity shader**：本类型是为替换方块渲染而存在的，
要与 `RenderType.solid()` 行为一致。`rendertype_solid` 直接用 lightmap、不看法线，
逐面明暗由顶点色承担；`rendertype_entity_solid` 则按法线做漫反射
（`mix_light(Light0, Light1, Normal, Color)`），用后者会让原有的烘焙明暗消失、
换上一套不同的方向光结果，E/W 与 down 面会明显偏暗。与 `solid()` 的差别仅在绑定自定义纹理。

（这些常量都定义在父类 `RenderStateShard` 上，是 `protected static final`，
故 `MekCkRenderTypes` 继承 `RenderType` 后可直接引用。）

### 4.5 JSON → OBJ 几何烘焙

`tools/convert_bioreactor_obj.py` 把三层 JSON 烘焙为单个
`assets/mekck/models/mesh/bioreactor.obj`。

- **坐标**：`(px/16, py/16, pz/16)`，1 OBJ 单位 = 1 block。
  **保持每层原有的 y[0,16] 局部坐标，不做层间平移**——层间堆叠由渲染器施加，
  这样每层才能各自取 `pos.above(i)` 的光照，与原实现完全一致。
- **UV 值**：`u_obj = u_px/128`，`v_obj = 1.0 - v_px/128`。
  写入 OBJ 的是标准 OBJ 约定（v 向上）；`ObjMesh.parse` 会再翻成 MC 约定，
  两次翻转后得 `v_px/128`，与原 JSON 渲染一致。**只翻一次会导致纹理上下颠倒。**
- **顶点顺序与 UV 角分配：逐字照抄 MC 1.20.1 的 `FaceInfo` / `BlockFaceUV`，不做几何推导。**
  曾用「把顶点投影到观察者 2D 平面，a 最小为左、b 最小为上」来分配 uv 矩形的四角，
  前提是「`(u0,v0)` 是面的左上角」——**但 MC 的约定并非视角一致，`(u0,v0)` 只在 up 面
  是左上角**。照那个前提做，338 个面里 285 个的 uv 与源 JSON 不符（整块面板镜像），
  而几何类测试（分组数、三角形数、y 范围）全绿，完全测不出来。
  现在脚本里的 `FACE_INFO` 表是字面量，并由 §9.3 的测试逐顶点复核。
- **绕序**：采用 MC 的面顺序后天然逆时针，脚本仍保留**叉积自检**兜底
  （退化零面积三角形只警告、真正反向才报错，两者不混）。
- **索引**：OBJ 索引是 **1-based**，内部 0-based 累加，输出时必须 +1。
  （写 0 会被 `ObjMesh.resolve` 当成 `size + 0` 越界 → 整面丢弃；
  这个 bug 真实发生过，被 §9.3 的端到端测试抓出。）
- **法线**：6 个固定方向，必须全部写出（缺失会让模型全黑）。
- **分组**：按 `layer0` / `layer1` / `layer2` 输出三个 `g` 分组。

产出：1352 顶点 / 1352 UV / 338 法线 / **676 三角形**。

### 4.6 BioreactorRenderer 改造

```
MESH = "mekck:models/mesh/bioreactor.obj"
TYPE = MekCkRenderTypes.objSolid("mekck:block/mekck/bioreactor/bioreactor")
```

- 移除 `BakedModel` 获取与 `LAYER_MODELS` 循环。
- **保留逐层平移与逐层光照**：对 `bioreactor_layer{i}` 施加 `translate(0, i, 0)`
  并取 `LevelRenderer.getLightColor(level, state, pos.above(i))`。
  合成整体只能用单一光照值，方块遮挡时上下层亮度会与改造前不同。
- 保留绕足迹中心旋转变换（`translate(1,0,1)` → `mulPose` → `translate(-1,0,-1)`）
  与 `BioreactorBlock.FACING` → `Axis.YN.rotationDegrees` 换算。
- 网格不可用时直接 return（缺件不渲染，不崩）；缺单层时跳过该层而非整台不渲染。
- `blockstates/bioreactor.json` **保持不变**：仍指向 layer0，承担物品栏与掉落物外观。
  layer0 与 OBJ 底部几何一致，叠加渲染视觉无差异——与改造前现状相同。
- `UniversalCuttingMachine.onRegisterAdditionalModels` 整体删除（改用 OBJ 后不再需要
  ModelManager 预加载 layer1/layer2；layer0 由 blockstate 自动加载）。
- 3 个源 JSON **保留**：它们是转换脚本的输入，删除会让脚本不可重跑。
  运行时不会被加载（blockstate 不引用、代码也不注册）。

## 5. 数据流

```
BioreactorRenderer.render(...)
  │ ObjMeshLoader.load("models/mesh/bioreactor.obj")
  ▼
ObjMeshLoader ──缓存命中──▶ ObjMesh（复用）
  │ 缓存未命中
  │ ResourceManager.getResource(rl) → Optional<Resource>
  │ → openAsReader() → ObjMesh.parse() → 写入缓存
  ▼
ObjMesh（groups: bioreactor_layer0/1/2 → float[]）
  │ 绕足迹中心旋转；逐组 translate(0, i, 0) + 取该层光照
  ▼
ObjMeshRenderer.renderGroup(mesh, "bioreactor_layer" + i, ...)
  │ buffers.getBuffer(TYPE) → VertexConsumer
  │ 每 STRIDE 个 float 一个顶点，逐个 endVertex()
  ▼
GPU
```

## 6. 生命周期

缓存失效通过 `ObjMeshLoader` 自订阅 `ModelEvent.BakingCompleted`（mod bus, client）实现：

```java
@Mod.EventBusSubscriber(modid = UniversalCuttingMachine.MOD_ID,
                        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ObjMeshLoader {
    @SubscribeEvent
    public static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        invalidateAll();
    }
}
```

**为什么不用 `RegisterClientReloadListenersEvent`**：它的 `registerReloadListener` 只接受
`PreparableReloadListener`，其唯一抽象方法带 6 个参数（本实现一个都用不上），
且 `ContextAwareReloadListener` 抽象基类在 1.20.1 尚不存在。
`BakingCompleted` 同样发生在资源重载之后、渲染之前，时机等价而写法干净。

失效时机为资源重载（启动、F3+T、服务器资源包变更），使 OBJ 可热重载——
这是源实现所不具备的能力（源实现每次 `load()` 都重读，等价于永不缓存）。

## 7. 错误处理

| 情况 | 行为 |
|---|---|
| 资源不存在（`Optional` 为空） | 记 ERROR，返回不可用空网格 |
| 读取 / 解析抛异常 | 同上，日志含异常信息 |
| 解析成功但无任何非空分组 | 同上，措辞区分 |
| 失败后 5s 内的重复请求 | 返回空网格，不重试、不刷日志 |
| 渲染时分组不存在 | 静默跳过 |
| 反应炉网格不可用 | 渲染器直接 return，机器其余部分正常 |

失败**不写缓存**，以免把失败状态永久固化；冷却结束后允许重试，
以支持「先启动游戏、后放入 obj」这类开发场景。缓存归属加载器，失效也由加载器负责。

## 8. 风险

1. **UV 方向与面朝向**（最高风险）。两次翻转的链路与每面 4 顶点的 UV 分配顺序，
   任一处出错都表现为纹理旋转 / 镜像 / 颠倒。缓解：实机截图对比。
2. **三角形绕序**若与 `CULL` 正面判定相反，模型整体不可见。
   缓解：脚本内叉积自检 + 实机截图。
3. **法线缺失**会让模型全黑；转换脚本已为每个面写出 6 个固定方向之一。
4. layer0 重复渲染（chunk + BER/OBJ 底部）：改造前后一致，非新引入；几何一致故无害。
5. **实机验证未做**：本环境无法启动 mekck dev 客户端。已完成编译、全量单测、
   端到端资产解析与绕序自检，但**真实 GL 输出、光照与混合表现仍未经验证**。
6. mekck 非 git 仓库。实施前已备份至 `.backup-20260929/`
   （3 个 Java 文件 + `bioreactor` 模型目录 + `blockstates/bioreactor.json`）。

## 9. 测试与验证

测试位于 `src/test/java/cn/ism/mekck/client/mesh/`，JUnit 4
（`build.gradle:212` 声明 `junit:junit:4.13.2`，`:217` 配置 `useJUnit()`）。

**当前状态：73 个测试，0 失败，0 跳过，`BUILD SUCCESSFUL`。**

### 9.1 TestObjMeshParse —— 解析器契约
基本 `v`/`vt`/`vn`/`g`/`f` 解析；扇形三角化；负数相对索引；`#` 与空行跳过；
空分组不入结果；坏索引整面丢弃；`1.0f - v` 翻转；无 `g` 归入 `default`；分组顺序保持。

**容错契约**（三条规则一致，坏数据只丢最小的一块，绝不因一个 token 毁掉整个文件）：
非有限浮点（`nan` / `1e999`）让整行被跳过；分量不足的数值行只丢自己；
`of()` 丢弃长度不是 `STRIDE*3` 整数倍的分组（否则 `triangleCount` 会被静默截断）。

### 9.2 TestObjMeshRender —— 顶点写出契约
记录型 `VertexConsumer` 桩件：已知三角形 → 恰好 3 次 `vertex` + 3 次 `endVertex`；
`x/y/z`、`u/v`、`nx/ny/nz` 逐个正确；顶点色为**逐面明暗灰度**且 alpha 恒不透明；
四种法线朝向给出四种不同明暗（255/204/153/128）；分组不存在时零顶点且不抛异常；
空网格安全返回；`renderAll` 覆盖全部分组。

注意：`overlayCoords(int)` / `uv2(int)` 是 default 方法（`javap -c` 核实），
会把打包值拆成**两个 16 位**分量 `u = packed & 0xFFFF`、`v = (packed >> 16) & 0xFFFF`，
断言时须比对拆分后的值而非原值。

### 9.3 TestBioreactorObjUvs —— UV 逐顶点复核（最关键的一条）
**不读转换脚本**，而是把 MC 1.20.1 的逐面顶点顺序与 uv 角分配
（`FaceInfo` / `BlockFaceUV`）以字面量写死在测试里，从源 JSON 重新推导期望的
(位置, uv) 顶点集合，再与产物 OBJ 的实际顶点集合逐个比对。

**这条测试的价值已被证实两次**：先抓出 0-based 索引 bug（layer0 少 2 个三角形），
再抓出 uv 分配错误（338 面中 285 面的 uv 与源 JSON 不符，整块面板镜像）。
后者是纯几何推导造成的，且当时**所有其它测试都是绿的**——
分组数、三角形数、y 范围、绕序自检全都测不出 uv 镜像。

### 9.4 TestBioreactorObjAsset —— 端到端资产契约
用 `ObjMesh.parse` 实际读取 classpath 中的 `bioreactor.obj`：
资源已打包；3 个分组齐全；每层 y 范围 `[0,1]`（供渲染时堆叠）；
三角形数 160/350/166 合计 676；足迹跨度 2 block。

### 9.5 几何自检（脚本内）
转换脚本每次运行都会做叉积绕序自检（退化零面积只警告、真正反向才报错）与三角形计数校验，
任一不符即非零退出。注意该自检只能发现 `FACE_INFO` 与声明法线自相矛盾，
**权威的 uv 校验在 §9.3 的测试里**。

### 9.6 实机验证（未做）
需启动 mekck dev 客户端，进入含生物反应炉的世界截图，与改造前对比；
因几何与 uv 均未变，视觉应一致。F3+T 验证热重载。

## 10. 走过的弯路（留给后人）

- **不要用几何推导去猜 MC 的 uv 约定**。曾按「`(u0,v0)` 是面的左上角」把顶点投影到
  观察者平面来分配四角，而 MC 的约定**并非视角一致**——`(u0,v0)` 只在 up 面是左上角。
  结果 338 面里 285 面 uv 错误、整块面板镜像，而几何类测试全绿。
  正确做法：逐字抄 `FaceInfo` / `BlockFaceUV`，并用测试把表钉死（§9.3）。
- **block shader 与 entity shader 的光照模型不同**。`rendertype_solid` 不看法线，
  逐面明暗由顶点色承担；`rendertype_entity_solid` 做法线漫反射。替换 baked 模型渲染
  时必须用前者并自己写明暗，否则 E/W、down 面明显偏暗。
- **FaceBakery 探针不可行**：曾试图用 MC 自己的 `FaceBakery` 烘焙一个 box 来确定
  uv 矩形的分配规则。`FaceBakery()` 有无参构造器、`MissingTextureAtlasSprite.create()`
  可提供 `SpriteContents`、`TextureAtlasSprite` 可反射构造——但**编译通过、运行时
  `ExceptionInInitializerError`**：`TextureAtlasSprite` 的静态初始化需要游戏环境。
  无头单元测试里这条路走不通，别再尝试。
- **JUnit 4 的 `assertArrayEquals` 对 `float[]` 只有带 delta 的重载**，
  没有 `(float[], float[])` 无参版本。
- **源资产不能删**：删除 3 个源 JSON 会让转换脚本失去可重跑性。
  「删掉源、留产物」是反模式。
- **待办（非本次引入）**：`ChestRenderer.render` 以方块**中心**为 BER 帧原点，
  而 `BlockRenderDispatcher` 用方块**最小角**，整台机器相对底座偏 0.5 格。
  改造前后一致，故不影响本次对比，但若实机截图觉得偏移，多半是这个原因。
