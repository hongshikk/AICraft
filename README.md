# AICraft —— Paper AI 合成器 / AI 建造插件

1. 把材料丢进 3×3 合成区，点「合成!」，由免费 AI 接口创造性地合成出一件**全新的**物品。
2. 用 `/aibuild 帮我在这里建造一个黄鹤楼`，让 AI 把一句话变成真实建在世界里的建筑。

- 目标平台：**Paper 26.2**（服务端 `26.2-129` / API `26.2.build.129-stable`；新版 `ItemStack` 数据组件 API，`io.papermc.paper.datacomponent.*`）
  - 注意：`plugin.yml` 的 `api-version` 与 `build.gradle.kts` 的 `paper-api` 版本**必须 ≤ 服务端 API 版本**，否则 Paper 会直接拒绝加载（见第 9 节）。
- Java：**25**
- 命令：
  - `/aicraft`，别名 `/aic` —— AI 合成器界面
  - `/aibuild`，别名 `/aib` —— AI 建造（默认仅 OP）
- 权限节点：`aicraft.use`（默认所有人可用）、`aicraft.reload`（默认 OP）、`aicraft.build`（默认 OP）

构建产物：`build/libs/AICraft-1.0-SNAPSHOT.jar`

---

## 1. 构建

```bash
# 用 JDK 25 执行（Gradle 9.4 + Java 25 toolchain）
JAVA_HOME=/path/to/jdk-25 ./gradlew build      # 若已有 wrapper
# 或使用本机 Gradle 9.4+
gradle build
gradle test                                    # 单元测试（ai 包里不依赖 Bukkit 的纯逻辑）
```

产物在 `build/libs/AICraft-1.0-SNAPSHOT.jar`，丢进服务端 `plugins/` 即可。

> **关于 `run-paper`**：原工程里的 `xyz.jpenilla.run-paper:3.1.0` 要求 Gradle ≥ 9.7，
> 而本工程的 wrapper 固定为 Gradle 9.4.0，配置阶段会直接失败（
> `No matching variant of xyz.jpenilla:run-task:3.1.0`）。因为本次交付只需要可用的插件 jar、
> 且不需要下载服务端，我从 `build.gradle.kts` 里移除了该插件。
> 若你升级到 Gradle 9.7+，把下面两行加回 `plugins { }` 即可恢复 `runServer` 任务：
>
> ```kotlin
> id("xyz.jpenilla.run-paper") version "3.1.0"
> ```
> ```kotlin
> tasks { runServer { minecraftVersion("26.3"); jvmArgs("-Xms2G", "-Xmx2G") } }
> ```

---

## 2. `/aibuild` —— 让 AI 建造建筑

```
/aibuild 帮我在这里建造一个黄鹤楼      把这句话发给 AI，AI 返回一串建造指令并施工
/aibuild undo [次数]                  还原最近若干次 AI 建造（默认 1 次）
/aibuild cancel                       停掉正在进行的施工
/aibuild help                         用法
/aibuild reload                       重载配置（需要 aicraft.reload）
```

- **权限**：`aicraft.build`，默认 **OP**。非 OP 会收到「你没有权限使用该命令」。
- **建造原点**：优先取玩家**准星指向的方块表面**（最远 `build.max-distance` 格），
  没瞄到方块时取身前 4 格。AI 用 `~` 相对坐标写指令，所以建筑就出现在你指向的位置。
- **施工过程**：AI 返回的指令按 tick 分片执行（每 tick 最多 `build.blocks-per-tick` 个方块），
  不会一次性改几万方块把服务器卡死。
- **思考过程可见**：建造期间会把模型的思维链实时转达给玩家（见下方「思考链转达」），
  默认开启，用 `build.stream-thinking: false` 关闭。

### 指令集

AI 现在返回的是**结构化 JSON**：`ops` 是一个对象数组，每条 op 自己声明「干什么、在哪里、用什么方块」，
坐标是相对原点的整数三元组 `[x, y, z]`（不写 `~`）。示例（AI 实际会输出的形状）：

```json
{
  "success": true,
  "name": "角斗场",
  "summary": "椭圆形石造看台 + 沙地竞技场",
  "ops": [
    { "op": "fill",     "from": [-14, 0, -11], "to": [14, 0, 11], "block": "stone_bricks" },
    { "op": "walls",    "from": [-14, 1, -11], "to": [14, 3, 11], "block": "stone_bricks" },
    { "op": "replace",  "from": [-13, 1, -10], "to": [13, 3, 10], "block": "air" },
    { "op": "cylinder", "at": [0, 1, 0], "radius": 3, "height": 4, "block": "sand" },
    { "op": "sphere",   "at": [0, 5, 0], "radius": 2, "block": "gold_block", "hollow": true }
  ]
}
```

| op | 必填字段 | 可选字段 | 说明 |
|---|---|---|---|
| `fill` | `from` `to` `block` | — | 实心长方体 |
| `setblock` | `at` `block` | — | 单个方块 |
| `hollow` | `from` `to` `block` | — | 长方体六面外壳 |
| `walls` | `from` `to` `block` | — | 四面竖墙（不含顶/底） |
| `line` | `from` `to` `block` | — | 两点连线 |
| `sphere` | `at` `radius` `block` | `hollow` | 球体 |
| `cylinder` | `at` `radius` `height` `block` | `hollow` | 圆柱，高度沿 Y 轴 |
| `replace` | `from` `to` `block` | `filter` | 区域内替换，`filter` 默认 `any`，可为方块名 / `#方块标签` / `any` |
| `sign` | `at` `text` | `block` | 告示牌，`text` 是字符串数组，最多 4 行，支持 `&` 颜色代码 |

`OpJson` 负责把对象翻成 `BuildOp` 认得的规范指令文本，并对字段名 / 坐标写法做了大量容错
（`position`/`pos` = `at`、坐标写成 `{"x":..}` 或 `"0, 0, 0"`、`place_block` = `setblock`…），
所以模型偶尔的漂移不会让整条 op 丢掉。

**文本指令仍然被完全支持**，只是不再要求 AI 输出它：其它插件 / 控制台 / 数据包可以直接生成
`BuildOp` 解析的这些指令行，坐标支持 `~X ~Y ~Z`（相对原点，`~0 ~0 ~0` 是地基那层）或世界绝对坐标，
方块写法就是原版 ID，可带方块状态：

| 指令 | 语法 | 说明 |
|---|---|---|
| `fill` | `fill <x1 y1 z1> <x2 y2 z2> <方块>` | 实心长方体 |
| `setblock` | `setblock <x y z> <方块>` | 单个方块 |
| `hollow` | `hollow <x1 y1 z1> <x2 y2 z2> <方块>` | 长方体六面外壳 |
| `walls` | `walls <x1 y1 z1> <x2 y2 z2> <方块>` | 四面竖墙（不含顶/底） |
| `line` | `line <x1 y1 z1> <x2 y2 z2> <方块>` | 两点连线 |
| `sphere` | `sphere <x y z> <半径> <方块> [hollow]` | 球体 |
| `cylinder` | `cylinder <x y z> <半径> <高度> <方块> [hollow]` | 圆柱 |
| `replace` | `replace <x1 y1 z1> <x2 y2 z2> <过滤> <方块>` | 区域内替换，过滤可为方块名 / `#方块标签` / `any` |
| `sign` | `sign <x y z> [告示牌方块] <文本>` | 告示牌，文本用 `\|` 分最多 4 行，支持 `&` 颜色代码 |

### 安全阀

| 机制 | 配置键 | 默认 |
|---|---|---|
| 单次建造总方块上限（超出截断） | `build.max-blocks` | `120000` |
| 单条指令方块上限（超出整条跳过） | `build.max-volume` | `200000` |
| 指令条数上限 | `build.max-operations` | `400` |
| 不允许被覆盖的方块 | `build.blacklist` | 基岩 / 屏障 / 传送门 / 命令方块 … |
| 超出世界高度的方块 | 直接跳过并提示 | — |
| 每位玩家建造冷却 | `build.cooldown-seconds` | `60` |
| 撤销记录上限 / 次数 | `build.undo.max-blocks` / `build.undo.history` | `80000` / `3` |

`/aibuild undo` 会把「被改动方块原来的样子」按反序写回，所以连**替换掉的原方块**也能还原。
快照只存在内存里，服务器重启后失效。

### 容错

- 单条指令写错（未知方块、参数不足）只会计入「已跳过」，整次建造照常进行。
- 规模超限时**截断**而不是整次失败，玩家会收到上限提示。
- 结构化 `ops` 的字段名 / 坐标写法漂移由 `OpJson` 吸收：`op`/`operation`/`action`/`command`/`type`
  都认；坐标接受 `[x,y,z]`、`{"x":..,"y":..,"z":..}`、`"0, 0, 0"`、`"~0 ~0 ~0"`，
  也接受平铺的 `x1 y1 z1 x2 y2 z2`；`position`/`pos`/`center` = `at`，
  `material` = `block`，`place_block`/`box`/`shell`… = 对应指令；方块状态里的空格会被清掉。
  一条 op 确实缺关键字段时只跳过它，不影响其余 op。
- `success` 只有写成明确的假值（`false` / `"false"` / `0`）才算失败；含糊的取值一律按成功处理，
  免得因为一个字段把明明有 `ops` 的方案判死。
- 模型没按约定输出 JSON、而是直接吐出一行行指令时，会自动降级成「按行解析」：
  剥掉 Markdown 围栏 / 列表符号 / 行号 / 反引号 / 开头的 `/`，只认 `fill`、`setblock`、`hollow` …
  这些首词，说明性文字整行忽略。**无数组包裹的 JSON 字符串**（`"fill …",` 这种行尾带引号逗号的写法）
  与**同一行里塞了多条指令**（用 `,` / `;` / `，` 分隔）也都能救回来。
- 旧协议（`"commands": ["fill ~0 ~0 ~0 …"]` 字符串数组，或整段指令塞进一个多行字符串）照常可解析。
- JSON 抠出来了但一条指令都解析不出来（数组写坏、字段名不对）时，会把整段正文再按行扫一遍，
  往往还能救回真正的指令 —— 只有两条路都失败才会告诉玩家「指令全部无法解析」。
- 常用同义词一样接受：`box`/`cube` = `fill`、`shell`/`outline` = `hollow`、
  `block`/`add`/`place` = `setblock`、`wall` = `walls`、`ball` = `sphere`、`cyl` = `cylinder`。
- 「全部无法解析」时，`debug: true` 会在控制台打出**模型的原始行**与**每一条被丢弃的原因**
  （例如「未知方块: oak_plankss」、「指令参数不足」），玩家侧则提示去看控制台。
- 传输失败与「正文里没有内容」会按 `api.max-attempts` 重试；`success:false` 视为合法业务失败、不重试。
- **超时不重试**：撞到超时红线会立刻结束，并单独提示 `messages.build-timeout`（带上
  `build.timeout-seconds` 的秒数）—— 免费车道上把同一份请求重发一遍只会再等一个超时周期，
  白白拖长玩家等待、白烧免费额度。
- **「只思考、没写正文」也不重试**：推理模型把预算花在思维链上的回合单独提示
  `messages.build-no-content`，而不是傻等 3 遍。详见 [§4](#只思考没正文不重试)。

### 思考链转达

推理模型经常先吐几分钟思维链（SSE 里的 `delta.reasoning`）才动笔。建造期间插件会把
**思考链的最新片段**按固定间隔发到玩家聊天框（默认模板 `messages.build-thinking-stream`）：

```
[思考中 12s] …所以先把外圈看台的半径定在 14，中间下沉两格铺沙地
```

| 配置键 | 默认 | 说明 |
|---|---|---|
| `build.stream-thinking` | `true` | 是否转达思考链；关闭后玩家只会看到 `messages.build-thinking` |
| `build.thinking.interval-ms` | `1500` | 两条思考消息之间的最小间隔（毫秒），防止逐 token 刷屏 |
| `build.thinking.chunk-chars` | `96` | 每条消息最多展示多少个字符（取最新的尾巴） |

实现上没有为它另起一套解析：`AiClient.requestStreamingText(...)` 把响应体交给
`BodyReader.readLines(...)`（同一套闲置 / 总时长看门狗），每读到一个 SSE 帧就把
`delta.reasoning` 交给 `AiStreamHook`，正文增量就地攒下来，收尾时重建一个最小的
`{"choices":[{"delta":{"content":…}}]}` 信封、仍走同一个 `extractContent` —— 流式只是
多了一个「顺便转达」的副作用，不会解析出不同结果，也不会为了转达思考链把几 MB 的
原始流留在内存里（推理模型的思维链动辄 2~3 MB，读完就丢）。
钩子回调在读取线程上，`BuildService` 只更新限流状态，真正发消息用 `runTask` 切回主线程。
每次重试都会新造一个钩子（`Supplier<AiStreamHook>`），上一次半途而废的思考不会混进来。

这个功能不只是体验问题：实测中模型真的会在**没有要求**的情况下给角斗场插一块写着
「黄鹤楼」的告示牌 —— 思考链可见之后，这类「设计跑偏」当场就能看出来，而不是等建筑建完
才发现（提示词侧也加了对应约束，见下）。

### 提示词

建造系统提示词在 [`src/main/resources/build-prompt.txt`](src/main/resources/build-prompt.txt)，
加载优先级：`config.yml` 的 `build.system-prompt`（非空则整体覆盖）→ `resources/build-prompt.txt` → 内置精简兜底。

**2026-10 重写**：旧版提示词有两个把模型带偏的问题，已整篇重写。

1. **示例过度具体，模型会抄示例**：旧版的示例 JSON 是
   `{"name":"黄鹤楼", …, "sign ~0 ~4 ~-8 dark_oak_sign 黄鹤楼|&6天下江山第一楼"}`，
   于是玩家要「角斗场」时，模型直接照抄例子里的名字与匾额文本
   —— 建出来的角斗场门口插着一块写着「黄鹤楼」的告示牌。
   新版示例只用**中性/古罗马式**的角斗场指令，并明确写了「示例只演示格式，不要抄示例的建筑」。
2. **缺少「只建 request 要的东西」这条硬约束**：新版把它放在**第一原则**位置：
   name / summary 只能概括 `request`；只在玩家明确要牌子 / 匾额时才用 `sign`，
   且文本必须来自原话、一个字都不能编；request 没提牌子就一条 `sign` 都不要写。

其余重写要点：

- 坐标章节补上对称写法（`~-9` 与 `~9` 成对）与合理高度区间（房屋 4~8、塔 15~40）；
- 给了一份**材质配色表**（石造 / 中式 / 木屋 / 现代），并说明「不认识的方块会整条被跳过」；
- 按建筑类型给了粗粒度思路（角斗场 = 环形看台 + 下沉场地、塔 = 逐层收进 + 塔尖…），
  只讲结构套路，不再给具体建筑的成品示例；
- 移除旧版「每层比下一层小一圈」这类容易让模型写成几十条 `setblock` 的细碎建议，
  改成「优先 fill / hollow / walls，15~60 条就能建成一座像样的建筑」。

**2026-10 第二轮：输出协议从「文本指令数组」换成「结构化 ops 对象数组」。**
第一轮的提示词仍在要求模型写
`"commands": ["fill ~-9 ~0 ~-9 ~9 ~0 ~9 stone_bricks", …]` —— 一行一条指令文本。
实测这条协议对免费车道太难：模型会漏写 `~`、把坐标和方块名串成散文、
写成函数调用 `fill(-8,0,-8, 8,0,8, stone)`、或者只输出推演、`commands` 一个都没有。
现在提示词改成每条形如
`{ "op": "fill", "from": [-9,0,-9], "to": [9,0,9], "block": "stone_bricks" }`：
坐标是相对原点的整数数组，不需要记 `~`、逗号位置与指令词序，模型只要填字段。
配套改动：

- 新增 [`OpJson`](src/main/java/com/hongshikaikai/aicraft/build/OpJson.java)：
  把 op 对象翻成 `BuildOp` 认得的规范文本，并吸收字段名 / 坐标写法的漂移；
- `BuildPlan.parse` 优先读 `ops`，仍兼容 `commands` 字符串数组与整段多行字符串；
- `success` 只有明确假值才算失败；
- 提示词里补上「不要写思考过程」「`ops` 至少一条、最多 60 条」「不要写注释 / 尾逗号 /
  字符串指令」等输出纪律，并把示例标注为「只演示格式，不要照抄」。

---

## 3. 界面与交互

```
第1行： 0  1  2 |  3  4  5 |  6  7  8
第2行： 9 10 11 | 12 13 14 | 15 16 17
第3行：18 19 20 | 21 22 23 | 24 25 26
```

| 区域 | 槽位 | 内容 |
|---|---|---|
| 左侧 3×3 | `0,1,2,9,10,11,18,19,20` | 外圈 8 格淡灰色玻璃板（锁定）；中心 `10` 红色玻璃板「取消」 |
| 中间 3×3 | `3,4,5,12,13,14,21,22,23` | 空白，玩家放入待合成物品 |
| 右侧 3×3 | `6,7,8,15,16,17,24,25,26` | 外圈 8 格淡灰色玻璃板（锁定）；中心 `16` 绿色玻璃板「合成!」 |

**锁定格保护**（`CraftListener`）：

- `InventoryClickEvent` 中，原始槽位属于顶栏且不在中间 3×3 → `setCancelled(true)`；
- 双键收集 / 双击（`CollectToCursor` / `DoubleClick`）**无论点在哪一格都取消**，否则玩家可以拿一个玻璃板
  双击把锁定格的玻璃板一起吸到光标上；
- 玩家背包区域的 `MoveToOtherInventory`（Shift 点击）取消，防止物品被自动塞进锁定格；
- `InventoryDragEvent` 中，只要拖拽路径碰到任何一个锁定格，整次拖拽取消。

**合成流程**（严格按规格顺序）：

1. **空检查** —— 合成区全空：提示「合成区域不能为空」，不关闭界面。
2. **堆叠检查** —— 任意一格 `amount > 1`：返还所有材料、清空合成区、提示「合成材料每格数量必须为 1」。
3. **提交 AI** —— 提示「AI 正在思考合成结果...」，材料**深拷贝后立即从界面移出**（材料改由插件保管，
   避免等待期间关界面 / 退服造成复制或丢失），异步请求 AI。
4. **回调**（切回主线程）—— 构建物品并发放；背包满则掉在脚下并提示；成功提示
   「合成成功：{item_name}」。失败重试 3 次仍失败 → 返还材料 + 「AI 无响应…」提示。

**物品安全**：

- `InventoryCloseEvent`（含退出、传送、死亡导致的关闭）→ 返还合成区剩余物品；`PlayerQuitEvent` 再兜一次底，
  两处都会先清空槽位，**不会重复返还**；
- 玩家在等待 AI 响应期间退出游戏 → 合成结果 / 返还的材料会掉落在提交时的位置；
- 背包塞不下的部分统一用 `World#dropItemNaturally` 掉在脚下；
- 插件卸载时会把所有还开着的界面里的材料还回去。

---

## 4. AI 接口是怎么接的

工程内的 `dsh-our-free-model-main/` 是一个 DeepSeek Harness 插件，不含 Python 调用封装，
真正的调用逻辑在它的 `src/upstream.js`、`src/http.js`、`src/adapter.js`、`src/catalog.js` 里。
我按这些源码逐条复刻了它的出网方式，并在 2026-10 用真实请求验证通过：

| 项目 | 值 | 出处 |
|---|---|---|
| 地址 | `POST https://opencode.ai/zen/v1/chat/completions` | `upstream.js#endpointFor` |
| 鉴权 | `Authorization: Bearer public`（公开免密额度，不是用户私钥） | `upstream.js#gatewayHeaders` |
| 客户端指纹 | `User-Agent: opencode/1.18.31`（网关要求 ≥ 1.17）+ `x-opencode-client: desktop`、`x-opencode-session`、`x-opencode-request`、`x-opencode-project: global` | `upstream.js#gatewayHeaders` / `CLIENT_UA` |
| 工具指纹闸门 | 请求体必须声明 `bash/glob/grep/read` 四个**小写**工具名，否则 `403 FreeTierError` | `upstream.js#applyFingerprint` |
| 必须流式 | `stream` 必须为 `true`，`stream:false` 同样被闸门拒绝，因此按 SSE 解析 | `adapter.js#buildPayload` |
| 会话 ID | 格式 `ses_[0-9a-f]{12}[0-9A-Za-z]{14}`，稳定（额度按会话计量） | `upstream.js#sessionForConversation` |
| 请求 ID | 格式 `msg_[0-9a-f]{12}[0-9A-Za-z]{14}` | `upstream.js#mintRequestId` |
| 默认模型 | `mimo-v2.6-flash-free`（实测可用，中文 / JSON 稳定） | `catalog.js` 免费车道模型表 |

实测要点（`curl` 直连复现）：

- 不带工具指纹 → `{"type":"error","error":{"type":"FreeTierError"...}}`；
- `stream:false` → 同样 `FreeTierError`；
- 会话 ID 长度不对 → 同样 `FreeTierError`；
- 用正确格式 + 四个工具 + `stream:true` → 正常返回 SSE 帧流，最后是 `data: [DONE]`。

Java 侧实现见 `AiClient`：

- `java.net.http.HttpClient#sendAsync` + 自带守护线程池，**完全不阻塞主线程**；
- SSE 逐行读 `data:` 帧，累加 `choices[].delta.content`（`delta.reasoning` 是推理模型的
  思维链，不计入正文，但确实占用生成时间和 `max_tokens`）；建造路径还会把
  `delta.reasoning` 实时交给 `AiStreamHook` 转达给玩家（见 [思考链转达](#思考链转达)）；
- 兼容网关回非流式 JSON 的情况与各种错误信封；
- 从模型正文里抠 JSON 时容错 Markdown 围栏与前后解释文字；
- 对外提供 `requestCraft`（合成：正文 -> `AiCraftResult`）、`requestText`（建造：只要正文）
  与 `requestStreamingText`（建造 + 思考链回调）三个入口，共用同一套重试 / 超时 / 指纹逻辑；
- 请求失败或结构错误自动重试，最多 `api.max-attempts`（默认 3）次，退避 `retry-backoff-ms × 第几次`；
  **超时是例外，不重试**；
- 重试耗尽 → 返还材料 + 「AI 无响应，已返还材料，请稍后重试」；
- 日志里的失败原因统一走 `AiClient.describe(...)`：`getMessage()` 为 null 时退化成异常类名，
  不会再出现「AI 请求第 1 次失败（null）」这种没法排查的日志；
- 回调一律用 `Bukkit.getScheduler().runTask(...)` 切回主线程再操作背包 / 玩家。

### 超时：闲置红线 + 总时长红线

2026-10-04 的实际故障：合成正常（约 20 秒），一 `/aibuild` 就
`AI 请求第 1 次失败（null）`。当时用的是 `CompletableFuture.orTimeout(90 + 15)` ——
一个从发请求那一刻起算的**总时长**哨兵。建造请求实测 160 秒仍在正常吐字，
于是 105 秒被硬砍；而 `orTimeout` 抛的 `TimeoutException` **message 为 null**，
日志里就只剩一个 `（null）`（时间戳也对得上：21:18:35 提交 → 21:20:20 报错 = 105 秒）。

现在改由 [`BodyReader`](src/main/java/com/hongshikaikai/aicraft/ai/BodyReader.java) 管两条红线：

| 红线 | 配置键 | 默认 | 含义 |
|---|---|---|---|
| 闲置 | `api.idle-timeout-seconds` | `30` | 连续这么久收不到**任何新字节**才判死 —— 这才是真正的「卡住」 |
| 总时长（合成） | `api.timeout-seconds` | `90` | 单次合成的绝对上限 |
| 总时长（建造） | `build.timeout-seconds` | `360` | 建造的绝对上限，比合成宽得多 |

每读到一个字节就刷新进度，所以「慢但一直在出数据」的流不会被误杀。超时的实现方式是让看门狗线程
`close()` 响应流：`java.net.http` 的响应流被关闭时，阻塞中的 `read` 会在毫秒级抛 `IOException`，
顺带取消这次 exchange、释放连接（不需要额外读线程或轮询）。超时抛 `AiTimeoutException`，
消息里写清楚超的是哪条红线、超了多久（单测覆盖见
[`BodyReaderTest`](src/test/java/com/hongshikaikai/aicraft/ai/BodyReaderTest.java)）。

### 免费车道的推理模型：正文可能一个字都没有

修完超时之后继续往下挖，才找到「建造为什么这么慢」的真正原因：免费车道上的模型是
**推理模型**，思维链（SSE 里的 `delta.reasoning`）和正文共用同一个 `max_tokens`，
而且思维链对用户不可见。实测数据（2026-10-04，Java 直连 + 同一份 `build-prompt.txt`）：

| 模型 / 请求 | 结果 |
|---|---|
| `mimo-v2.6-flash-free` 合成，`max_tokens=2048` | 23 秒返回，`completion_tokens=1175` 里 `reasoning_tokens=800` |
| `mimo-v2.6-flash-free` 建小木屋，`=3072` | 74 秒 `length` 结束，**正文 0 字符** |
| `mimo-v2.6-flash-free` 建小木屋，`=8192` | 243 秒 `length` 结束，`reasoning_tokens=8191/8192`，**正文 0 字符** |
| `mimo-v2.6-flash-free` 建黄鹤楼，`=16384` | **304 秒被上游无结束帧截断**，899KB 全是 `reasoning` |
| `ling-3.1-flash-free` 建小木屋，`=8192` | 83 秒 `length` 结束，**正文 0 字符**（同一个毛病） |
| `deepseek-v4-flash-free` / `jev-1.13-free` | `HTTP 400` / `HTTP 500`，这条车道上用不了 |
| **`nemotron-3.5-lightning-free` 建小木屋，`=8192`** | **50 秒给出正文**（2.7MB 流，`reasoning_tokens=6366`，可见正文 2.3 万字符，里面就有真实建造指令） |
| `nemotron-3.5-lightning-free` 同一请求，`=12288`、车道繁忙时 | 360 秒仍没读完，被插件的总时长红线拦下（`AI 响应超时：总时长超过 360 秒`） |

结论：

1. **选模型比调参数重要得多**。`mimo-v2.6-flash-free` 对「设计一栋建筑」这类开放任务会把
   整个预算用在思维链上，正文永远不出现；`nemotron-3.5-lightning-free` 速度约 5 倍
   （~164 tok/s vs ~34 tok/s）、会真的把答案写出来（它的推演是写在**可见正文**里的，
   所以插件按行降级解析能直接从里面取出指令）。因此新增
   `build.model`（默认 `nemotron-3.5-lightning-free`）与 `api.model`（合成，默认 mimo）**分开配置**；
2. **上游网关自己有一条约 304 秒的硬上限**，超过就无结束帧地掐断。所以
   `build.timeout-seconds` 默认 `360`：不去和上游比谁先掐，而是让上游的截断先到，
   我们据此给出准确的失败原因；
3. **`max-tokens` 不是越大越好**（推理量跟着它涨，等待时间成正比），也不能太小
   （3072 会被思维链吃光），默认维持 `8192`。

另外，工作区里的参考实现 [`dsh-our-free-model-main`](dsh-our-free-model-main) 早就记录了同样的
规律（`src/effort.js`：`mimo-v2.6` 的 `canDisableThinking: false`，实测 82% 的输出 token 是
reasoning，所以给「永远要思考」的模型的预算要翻倍；网关会忽略 `reasoning_effort` 之类的开关）。
想让它想得少，唯一的办法是换模型或**把需求说小**，调这些参数没用。

> **想让 `/aibuild` 稳定可用**：把它指到一个不做（或很少做）隐藏推理的模型上即可 ——
> 换商用 / 本地 OpenAI 兼容服务时，把 `api.base-url` / `api.path` / `api.key` 改成对方的值，
> `api.tool-fingerprint: false`，再把 `build.model` 留空（跟随 `api.model`）。

> **注意**：以上耗时是免费车道在**空载**时的实测值。这条车道是共享的，忙起来之后同一个请求
> 可能从 50 秒变成几分钟，甚至中途几十秒一个字节都不出（此时会触发
> `api.idle-timeout-seconds`，玩家看到的是明确的超时提示）。要可预期地建造，请换模型或换接口。

### 「只思考、没正文」不重试

思维链吃光预算的回合，正文 0 字符、流也没有正常结束帧。这种回合重发同一份请求
只会再想几分钟、再烧一次免费额度，所以插件把它单独判成
[`AiNoContentException`](src/main/java/com/hongshikaikai/aicraft/ai/AiNoContentException.java)
（不可重试），玩家会收到 `messages.build-no-content`：
「AI 光顾着想、没写出结果……把建筑描述改简单些再试」。
日志里则是 `AI 请求最终失败（共尝试 1 次）：AI 把生成预算全花在了思考上，没有写出正文`。
`build-prompt.txt` 里也加了「不要逐步推演、不要复述规则、直接输出 JSON」的输出纪律，
并在示例中把指令规模往小里压。

### 换成别的 OpenAI 兼容接口

```yaml
api:
  base-url: "https://api.deepseek.com"
  path: "/v1/chat/completions"
  key: "sk-你的密钥"
  model: "deepseek-chat"
  tool-fingerprint: false   # 非 OpenCode Zen 车道请关掉工具指纹
```

`AiClient` 依旧以 `stream: true` 读取 SSE（OpenAI 兼容接口都支持）。

---

## 5. 提示词

系统提示词在 [`src/main/resources/prompt.txt`](src/main/resources/prompt.txt)。
加载优先级：`config.yml` 的 `api.system-prompt`（非空则整体覆盖）→ `resources/prompt.txt` → 内置精简兜底常量。

提示词的基调是**放开创作**，只保留两条游戏引擎层面的硬约束：

- **放开**：物品名称、Lore、背景故事、附魔名称与等级、属性数值、物品强度——随便发挥，不需要向原版看齐。
  提示词里明确写了「锋利 100 级完全可以」「可以自创 LIFESTEAL、星辰之力这类附魔名」
  「不必刻意维持平衡」。
- **硬约束**（写错会被引擎丢弃，所以必须说明）：
  1. `material` 必须是真实存在的原版物品 ID —— 引擎无法创建不存在的物品；
  2. `attributes[].attribute` 必须是真实存在的原版属性 ID —— 引擎无法创建不存在的属性。

提示词末尾附上了原版的**原版附魔清单（43 项）与原版属性清单（38 项）**，
告诉 AI「想要真正生效就用这些名字，想更炫就自己编」——既给了自由，又不让它因为写错关键字段而白白失败。

发给模型的 user message 形如（`ItemSerializer` 从 `ItemStack` 的数据组件读出）：

```json
{
  "materials": [
    { "material": "DIAMOND", "amount": 1, "name": null, "lore": [], "enchantments": {}, "components": {} },
    { "material": "BLAZE_ROD", "amount": 1, "name": "&c火焰之核", "lore": ["&7炽热"], "enchantments": {}, "components": {} }
  ]
}
```

---

## 6. 响应解析与物品构建

`AiCraftResult#parse`：

- `success:false` → 视为**合法**的业务失败，直接提示 `{reason}`，不重试；
- `material` 必须能通过 `Material.matchMaterial` 且 `isItem()` 且不是空气，否则算结构错误 → 触发重试；
- `amount` 夹到 1–64，`enchantments` 等级夹到 1–255。

`ItemFactory#build` 全部走新版数据组件 API：

| AI 字段 | 数据组件 |
|---|---|
| `name` | `CUSTOM_NAME`（`ChatColor.translateAlternateColorCodes('&', …)` → Adventure `Component`） |
| `lore` | `LORE`（`ItemLore`） |
| `enchantments`（原版名） | `ENCHANTMENTS`（`ItemEnchantments`，走 `RegistryAccess` 注册表查名，兼容 `SHARPNESS` / `sharpness` / `minecraft:sharpness` 与旧版别名 `DAMAGE_ALL` 等；等级上限放到 255，不受原版附魔等级上限约束） |
| `enchantments`（**自创名**） | 不是原版附魔名的，**不会**被丢弃：追加到 `LORE` 展示，并写入持久化数据 `aicraft:custom_enchantments`（值形如 `SOUL_REND=5`） |
| `glow` | `ENCHANTMENT_GLINT_OVERRIDE`（1.20.5+ 取代「隐藏附魔 + HIDE_ENCHANTS」的旧写法） |
| `unbreakable` | `UNBREAKABLE` |
| `custom_model_data` | `CUSTOM_MODEL_DATA`（1.21.4+ 起是浮点列表） |
| `attributes` | `ATTRIBUTE_MODIFIERS`（`ItemAttributeModifiers`，属性名自动剥掉旧版 `GENERIC_`/`PLAYER_` 前缀；数值不受原版范围限制） |
| `components.max_stack_size` | `MAX_STACK_SIZE` |
| `components.fire_resistant` | `DAMAGE_RESISTANT` + `#minecraft:is_fire`（26.2 起原版 `fire_resistant` 组件已被 `damage_resistant` 取代，`ItemMeta#setFireResistant` 自 1.21.2 起标记过时） |
| `components.enchantment_glint_override` | `ENCHANTMENT_GLINT_OVERRIDE` |
| `components.rarity` / `damage` / `max_damage` / `hide_tooltip` / `unbreakable` / `custom_model_data` | 对应组件 |
| `components.custom_name` / `lore` | `CUSTOM_NAME` / `LORE`（等同顶层 `name` / `lore`） |
| `components.repair_cost` / `enchantable` / `glider` / `intangible_projectile` | 对应组件 |
| `components.item_model` / `tooltip_style` | `ITEM_MODEL` / `TOOLTIP_STYLE`（Adventure `Key`） |
| `components.food` / `weapon` / `attack_range` / `use_cooldown` / `equippable` / `tool` | 对应组件的 Builder（`food.nutrition`、`equippable.slot`、`tool.rules[].blocks` 等按提示词里的字段名） |

> 26.3 的 `DataComponentType` 带类型参数，API **没有**「给个键名 + 一段 JSON 就通用解码」的入口，
> 因此 `ItemFactory` 只映射上面列出的组件（与 `AICraft.COMPONENT_REFERENCE` 注入到提示词里的清单一致）。
> `tool.rules[].blocks` 只支持方块标签写法（`#minecraft:mineable/pickaxe`）。

### 关于「自创附魔」

Minecraft 的附魔注册表在服务端启动后就冻结了，运行时无法新增真正的自定义附魔，
所以对不上原版注册表的附魔名走**展示 + 存档**这条路，而不是静默丢弃：

- 在 Lore 末尾追加一行，格式在 `config.yml` 的 `craft.custom-enchantments.lore-format` 里配置
  （默认 `&7✦ &d{name} &8[&7{level}&8]`）；
- 纯 ASCII 下划线命名会美化成可读形式（`SOUL_REND` → `Soul Rend`），
  中文名或已经带 `&` 颜色代码的名字原样保留，不会被 `toLowerCase` 破坏；
- 原始名字与等级写进物品持久化数据 `aicraft:custom_enchantments`（`String` 列表，值形如 `SOUL_REND=5`），
  其它插件 / 数据包可以直接读。

两项都可以在 `craft.custom-enchantments` 里关掉。

单个组件设置失败只会被跳过并记一条 FINE 日志，不会让整次合成失败
（AI 给出的组件未必适用于该材质，例如给石头发「不可破坏」）。

锁定的装饰玻璃板用 `CUSTOM_NAME` + `TOOLTIP_DISPLAY`（隐藏 `ATTRIBUTE_MODIFIERS`/`ENCHANTMENTS`）
实现旧版 `hideFlags` 的效果。

---

## 7. 配置

完整注释见 [`src/main/resources/config.yml`](src/main/resources/config.yml)，要点：

| 键 | 默认值 | 说明 |
|---|---|---|
| `api.base-url` / `api.path` | `https://opencode.ai` / `/zen/v1/chat/completions` | 接口地址 |
| `api.key` | `public` | 免费车道固定值 |
| `api.model` | `mimo-v2.6-flash-free` | 合成用的模型名 |
| `api.max-attempts` | `3` | 含首次的最大尝试次数（超时与「只思考没正文」不重试） |
| `api.retry-backoff-ms` | `800` | 第 n 次重试等待 `n × 该值` 毫秒 |
| `api.timeout-seconds` / `api.idle-timeout-seconds` | `90` / `30` | 合成的总时长红线 / 闲置红线（秒），见 [§4](#超时闲置红线--总时长红线) |
| `api.tool-fingerprint` | `true` | 免费车道必须开启；换别的服务请关掉 |
| `api.max-tokens` / `api.temperature` | `2048` / `0.8` | 生成参数 |
| `api.system-prompt` | `""`（空） | 留空则用 `resources/prompt.txt`；填了就整体覆盖提示词，`/aicraft reload` 生效 |
| `craft.custom-enchantments.show-in-lore` | `true` | 自创附魔是否追加到物品 Lore |
| `craft.custom-enchantments.lore-format` | `&7✦ &d{name} &8[&7{level}&8]` | 自创附魔展示格式，占位符 `{name}` / `{level}` |
| `craft.custom-enchantments.store-in-pdc` | `true` | 是否写入 `aicraft:custom_enchantments` 持久化数据 |
| `gui.title` | `AI合成器` | 界面标题 |
| `build.enabled` | `true` | 是否启用 `/aibuild` |
| `build.cooldown-seconds` | `60` | 每位玩家建造冷却 |
| `build.max-distance` | `40` | 准星追踪建造原点的最大距离 |
| `build.max-operations` / `build.max-volume` / `build.max-blocks` | `400` / `200000` / `120000` | 指令条数 / 单条指令方块 / 单次建造总方块上限 |
| `build.blocks-per-tick` | `8192` | 每 tick 放置的方块数（越大越快、越容易卡顿） |
| `build.apply-physics` | `false` | 放置方块是否触发方块物理 |
| `build.model` | `nemotron-3.5-lightning-free` | 建造用的模型名（留空 = 跟随 `api.model`）；实测 mimo/ling 对建造只会「想」不写，见 [§4](#免费车道的推理模型正文可能一个字都没有) |
| `build.stream-thinking` | `true` | 建造时是否把模型思考链实时转达给玩家（见 [思考链转达](#思考链转达)） |
| `build.thinking.interval-ms` / `build.thinking.chunk-chars` | `1500` / `96` | 思考消息的最小间隔（毫秒）/ 单条最多展示多少字符 |
| `build.max-tokens` | `8192` | 建造请求的生成上限（推理模型的思维链也吃这个额度，别调太小） |
| `build.timeout-seconds` | `360` | 建造请求的总时长红线（秒），比合成的宽，也宽于上游约 304 秒的截断线 |
| `build.blacklist` | 基岩 / 屏障 / 传送门 / 命令方块 … | 不允许 AI 覆盖的方块 |
| `build.undo.history` / `build.undo.max-blocks` | `3` / `80000` | 撤销次数 / 单次快照方块上限 |
| `build.system-prompt` | `""`（空） | 留空则用 `resources/build-prompt.txt` |
| `messages.*` | 见文件 | 全部消息模板，支持 `&` 颜色代码与 `{item_name}` / `{reason}` / `{seconds}` / `{name}` / `{blocks}` 等占位符 |

---

## 8. 源码结构

```
src/main/java/com/hongshikaikai/aicraft/
├── AICraft.java                  主类：命令注册、合成流程编排、线程切换
├── command/
│   ├── CraftCommand.java         /aicraft [reload]
│   └── BuildCommand.java         /aibuild <描述> | undo | cancel | help | reload
├── build/                        —— /aibuild ——
│   ├── BuildService.java         编排：原点解析、AI 请求、思考链转达、冷却、撤销历史
│   ├── BuildPlan.java            AI JSON -> 指令列表（尽力解析 + 规模限制 + 丢弃诊断）
│   ├── OpJson.java               结构化 ops -> 规范指令文本（字段名 / 坐标写法容错）
│   ├── BuildOp.java              九种建造指令的语法与解析（sealed interface，含常用别名）
│   ├── BuildSession.java         按 tick 分片施工 + 撤销快照
│   ├── RestoreSession.java       按 tick 分片还原
│   ├── UndoSnapshot.java         一次建造的撤销快照
│   └── BlockOps.java             方块读写 / 高度与黑名单检查
├── config/PluginConfig.java      config.yml 的类型化视图 + 消息模板
├── gui/
│   ├── CraftHolder.java          InventoryHolder（身份标记 + 会话状态）
│   ├── CraftGui.java             27 格布局常量与装饰物
│   └── CraftListener.java        Click / Drag / Close / Quit 监听
├── ai/
│   ├── AiClient.java             免费 AI 接口客户端（SSE + 重试 + 通用 requestJson）
│   ├── AiStreamHook.java         流式回调：思维链 / 思考结束（不依赖 Bukkit）
│   ├── BodyReader.java           响应体读取 + 闲置 / 总时长看门狗（readAll / readLines）
│   ├── AiCraftResult.java        AI JSON 的解析与校验
│   ├── AiException.java          调用 / 结构错误
│   ├── AiTimeoutException.java   超时专用（据此决定「不重试」）
│   └── AiNoContentException.java 「只思考没正文」专用（同样不重试）
├── item/
│   ├── ItemSerializer.java       ItemStack -> 提示词 JSON
│   └── ItemFactory.java          AI JSON -> ItemStack（数据组件）
└── util/
    ├── Text.java                 & 颜色代码 -> Component
    └── ItemStacks.java           给玩家 or 掉落
src/main/resources/{plugin.yml, config.yml, prompt.txt, build-prompt.txt}
src/test/java/com/hongshikaikai/aicraft/ai/
├── BodyReaderTest.java           超时看门狗：正常流 / 慢流 / 卡死 / 总时长 / 按行流式
├── AiStreamTest.java             思考链转达：逐帧回调 / 噪声帧 / 正文即推演 / 只思考没正文 / 与不流式结果一致
└── AiClientContentTest.java      SSE 正文提取：正常 / 只思考没正文 / 错误信封
src/test/java/com/hongshikaikai/aicraft/build/
└── OpJsonTest.java               结构化 ops 翻译：字段名漂移 / 坐标写法 / 同义词 / 旧协议兼容
```

---

## 9. 已验证 / 未验证

**已验证**

- `gradle build` 通过（JDK 25 + `paper-api:26.2.build.129-stable`），
  无编译错误、无缺失类 / 方法。产物：`build/libs/AICraft-1.0-SNAPSHOT.jar`（含全部 class、
  `plugin.yml`（版本号已展开、`api-version: '26.2'`）、`config.yml`、`prompt.txt`、`build-prompt.txt`）。
- `config.yml` 通过 YAML 解析校验，`build.*` 与 `messages.build-*` 键全部就位。
- 免费 API 直连实测（2026-10，由前一轮验证）：请求头、工具指纹、`stream:true`、会话 ID 格式
  全部按预期工作，模型返回了严格合法的合成 JSON。
- 离线单元验证（16 + 15 项，由前一轮验证）：真实 SSE 响应解析、Markdown 围栏 / 带噪 JSON 提取、
  网关错误信封识别、非流式响应兼容、多帧拼接、`ses_`/`msg_` ID 格式与稳定性、
  自创附魔展示名美化。
- `OpJsonTest`（本轮新增 18 项）：结构化 ops 的九种指令翻译、字段名 / 坐标写法漂移、
  同义词、旧协议兼容、缺字段降级、`success` 判定。

> **本轮修复（AI 不给建造命令 / 给了也无法解析）**：玩家反馈建造要么没有任何指令、
> 要么指令全部无法解析。根因是**旧协议本身太难写**：提示词要求模型在
> `"commands": ["fill ~-9 ~0 ~-9 ~9 ~0 ~9 stone_bricks", …]` 里逐行拼文本指令，
> 免费车道模型经常漏 `~`、串标点、写成函数调用，或者把预算全花在推演上、
> `commands` 一条都没有。本轮把协议换成**结构化 JSON**：
>
> - `build-prompt.txt` 整篇改为 `"ops": [{ "op": "fill", "from": [-9,0,-9], "to": [9,0,9],
>   "block": "stone_bricks" }, …]`：坐标是相对原点的整数数组，不写 `~`，
>   模型只需填字段，不再需要记住指令词序与标点；并补上「不要思考 / 不要字符串指令 /
>   不要注释与尾逗号 / `ops` 至少一条」等输出纪律，示例注明「只演示格式」；
> - 新增 `OpJson` 作为翻译层，宽容地接受字段名与坐标写法的各种漂移
>   （`position`/`pos` = `at`、`{"x":..}` / `"0, 0, 0"`、平铺 `x1..z2`、`place_block`/`box`…），
>   单条缺字段只跳过它，不牵连其余 op；
> - `BuildPlan.parse` 优先读 `ops`，同时保留对旧 `commands` 字符串数组、
>   整段多行字符串、`success` 含糊取值的兼容；
> - `BuildService` 的内置兜底提示词同步改成新 schema。
>
> `gradle test`（JDK 25，offline）通过：全套 36 个用例，其中新增 `OpJsonTest` 18 个。
> 按你的要求**没有下载或启动服务端**，真实模型对这份新提示词的遵循度仍需在测试服上验证。

> **本轮修复（建造指令全部无法解析 / 思考链不可见 / 提示词被示例带偏）**：
> 玩家反馈三个问题，对应三处修改：
>
> 1. **「AI 给出的建造指令全部无法解析」**
>    - `BuildPlan` 现在能救回三类以前会整段丢掉的输入：无数组包裹的 JSON 字符串
>      （行首/行尾的引号与尾逗号会被剥掉）、同一行里塞了多条指令（按 `,` / `;` / `，` 拆）、
>      以及在 JSON 抠出来但指令全废时，再用「按行解析」扫一遍整段正文；
>    - `BuildOp` 接受常用同义词（`box`/`cube` = fill、`shell` = hollow、`add`/`place` = setblock…），
>      并把 `NaN` / `Infinity` / 超大坐标挡在解析层；
>    - 一条都解析不出来时，`BuildPlan` 会留下**原始行**与**每条被丢弃的原因**，
>      `debug: true` 时打进控制台，玩家侧提示去看控制台 —— 以前只有一句「无法解析」，无从排查。
> 2. **「建造过程中要能看到 AI 的思考链」**
>    - 新增 `BodyReader.readLines`（逐行流式 + 同一套闲置 / 总时长看门狗）、
>      `AiStreamHook`（思维链 / 思考结束回调）与 `AiClient.requestStreamingText`；
>    - `BuildService` 把思考链按 `build.thinking.interval-ms`（默认 1.5 秒）限流、
>      只保留最新 `chunk-chars`（默认 96）个字符后发给玩家，
>      发送一律 `runTask` 切回主线程；`build.stream-thinking: false` 可关闭；
>    - 正文解析路径完全没变：流式读出来的正文仍交给同一个 `extractContent`。
> 3. **「我让他建造一个角斗场他插了一个牌子：上面写着黄鹤楼」**
>    - 根因是旧提示词的示例 JSON 就是黄鹤楼 + 一块 `sign … 黄鹤楼|&6天下江山第一楼`，
>      模型照抄了示例。`build-prompt.txt` 已整篇重写：示例改成中性的角斗场指令并注明
>      「示例只演示格式」，同时把「只建 request 里要的东西」提到第一原则：
>      `name`/`summary` 只能是 request 的概括；只有玩家明确要牌子 / 匾额时才用 `sign`，
>      文本必须来自原话；request 没提牌子就一条 `sign` 都不写。
>
> 本轮 `gradle test` 新增 8 个用例（`AiStreamTest` 6 个 + `BodyReaderTest` 的按行流式 / 流式看门狗 2 个），
> 全套 `gradle build`（JDK 25，offline）通过，共 18 个用例。按你的要求**没有下载或启动服务端**，
> 因此告示牌文本、施工分片、撤销还原等仍需在测试服上点一遍（见下方「未验证」）。

> **本轮修复（`/aibuild` 必定超时）**：现象是合成正常、一建造就
> `[AICraft] AI 请求第 1 次失败（null）`。时间戳显示提交后正好 **105 秒**失败
> （`api.timeout-seconds 90` + `orTimeout` 的 15 秒），且 `TimeoutException` 的 message 为
> null，所以日志里是 `（null）`。实测免费车道一次建造请求 160 秒仍在正常出数据，
> 属于被旧哨兵误杀。修复内容：
>
> - 新增 `BodyReader`：闲置红线（默认 30 秒无新字节）+ 总时长红线（合成 90 秒 / 建造 360 秒），
>   慢流不再被误杀；看门狗 `close()` 响应流即可让阻塞中的 `read` 毫秒级返回；
> - `AiTimeoutException` / `AiNoContentException` 均不重试，玩家分别看到
>   `build-timeout` / `build-no-content`，不再出现「（null）」日志；
> - 查清并记录推理模型吃预算的规律（见 §4），新增 `build.model`（默认
>   `nemotron-3.5-lightning-free`，与合成的 `api.model` 分开），`build.max-tokens` 维持 8192、
>   `build.timeout-seconds` 定 360（略高于上游约 304 秒的截断线）。
>
> 本轮新增 `gradle test`（10 个用例）：`BodyReaderTest` 覆盖正常流 / 慢但活着的流 / 卡死 /
> 总时长四条路径，`AiClientContentTest` 覆盖 SSE 正文提取、只思考没正文、错误信封与非流式响应。
> 真实接口直连复核（2026-10-04）：合成 23 秒返回；对 6 个免费模型各打了一次真实建造请求，
> 结论写进了 §4 的表格（mimo/ling 只想不写、16384 会在上游 304 秒被截断、
> `deepseek-v4-flash-free` 400、`jev-1.13-free` 500、`nemotron-3.5-lightning-free` 50 秒给出
> 带真实建造指令的正文）。看门狗也在真实连接上验证过一次：nemotron 中途 30 秒不出数据时，
> 插件按设计抛出了 `AI 响应超时：已 30 秒没有收到新数据`，而不是像旧代码那样干等到 105 秒才报 `null`。

> **本轮修复（插件在 Paper 26.2 上加载失败）**：此前 jar 里的 `plugin.yml` 声明
> `api-version: '26.3'`，而服务端是 `Paper 26.2-129`，Paper 在
> `CraftMagicNumbers.checkSupported` 直接抛出
> `InvalidPluginException: Unsupported API version 26.3`，插件**根本没有被启用**
> （日志里只有 `Could not load plugin 'AICraft-1.0-SNAPSHOT.jar'`，没有 `Enabling AICraft`）。
> 现已把 `build.gradle.kts` 的 `paper-api` 钉到 `26.2.build.129-stable`（与服务端完全一致）
> 并把 `api-version` 改成 `26.2`，重新构建通过 —— 说明源码没有依赖任何 26.3 独有签名。
> 服务端将来升到 26.3 时，这两处再一起改回 26.3 即可。

> **上一轮的编译问题**：仓库里原有的 `ItemFactory` 用的是
> `DataComponentType<T>#codec()` + `com.mojang.serialization.JsonOps` 的「通用数据组件通道」，
> 但 26.2 / 26.3 的 paper-api 里 `DataComponentType` **不带类型参数、也没有 `codec()`**
> （`compileOnly` 也不会带 `datafixerupper`），所以那份代码在真实 API 上根本编译不过。
> 现在改成按类型显式映射 `DataComponentType.Valued<T>` / `NonValued`
> （`MAX_STACK_SIZE` / `RARITY` / `FOOD` / `TOOL` / `WEAPON` / `EQUIPPABLE` / `DAMAGE_RESISTANT` …），
> AI 可用的 `components` 子集见 `AICraft.COMPONENT_REFERENCE`，与提示词里的清单一致。

**未验证**

- 服务端内的实际运行表现（按你的要求**没有下载服务端测试**）：`/aibuild` 的准星取点、
  分片施工速度、撤销还原、告示牌文本、方块状态解析（`oak_stairs[facing=north]` 等）、
  以及合成侧数据组件在真实物品上的呈现。这些代码严格按 `paper-api 26.2.build.129-stable`
  的公开签名编写，但首次上线前建议在测试服点一遍。
- AI 返回的坐标是否总是规整对称——这取决于模型与提示词，建议先用小体量请求（如「一间小木屋」）
  验证，再用 `/aibuild undo` 回退不满意的结果。
