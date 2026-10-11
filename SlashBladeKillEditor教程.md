# SlashBladeKillEditor 教程

> 在线修改手持拔刀剑的 **杀敌数 / 耀魂 / 锻造数**
> 目标环境：**Arclight NeoForge 1.21.1** + **SlashBlade: Resharped 2.0.x**
> 产物：`SlashBladeKillEditor-1.0.0.jar`（已编译好，直接丢 plugins）
>
> ✅ **状态：已在本服实机验证通过**（2026-10-11 15:58，`/sbkill set 5555` → 聊天栏成功提示
> + 控制台 `[写入成功] QZRS KILL 5000 -> 5555`）。本服有插件接管了 `/item` 与 `/kill`，
> 方案已针对这种情况定型，详见第二节。

---

## 一、它改的是什么

1.21.1 版的 **SlashBlade: Resharped（拔刀剑:重锋）** 不再把刀刃数据写在根 NBT 里，
而是改用**模组自定义物品组件**：

| 项目 | 值 |
|------|-----|
| 组件 ID | `slashblade:blade_state` |
| 数据类 | `mods.flammpfeil.slashblade.capability.slashblade.BladeStateData` |
| 杀的实体数 | 字段 `killCount`（int） |
| 耀魂 | 字段 `proudSoul`（int） |
| 锻造数 | 字段 `refine`（int） |
| 对应原版命令路径 | `SelectedItem.components."slashblade:blade_state".killCount` |

来源：仓库 `0999312/SlashBlade_Resharped` 分支 `1.21.1`
（`SlashBladeDataComponents.java` 注册组件，`BladeStateData.java` 定义 `killCount/proudSoul/refine`）。

> ⚠️ 注意：网上流传的 `{killCount:5000, ProudSoul:10000}` 是 **1.20.1 Forge 及更早**的写法，
> 在 1.21.1 上**不生效**。铭文/SA/模型等仍在同一组件内，本插件只动指定字段，不会破坏其他数据。

---

## 二、原理

### 两条硬约束

| # | 约束 | 说明 |
|---|------|------|
| 1 | **`/data` 改不了玩家数据** | Minecraft 规定「**无法修改（包括设置和删除）玩家的 NBT 数据**」，`data modify entity <玩家>` 必定失败；官方给的正道是 **`/item` 命令** |
| 2 | **Bukkit 的类型枚举里没有模组组件** | `DataComponentTypes` 只列原版组件，`slashblade:blade_state` 不在其中——**但这不等于读不到** |

### 读取：纯 Bukkit，零命令（主路）

关键发现（已在本服 Arclight 实测）：`ItemMeta#getAsComponentString()` 返回的是**原始完整组件
SNBT**，连模组自定义组件都原样在内：

```
[slashblade:blade_state={adjust: [0.0d,...], baseAttackModifier: 2.0f, ..., killCount: 0,
                         proudSoul: 0, refine: 0, ..., texture: "slashblade:model/wood.png", ...},
 slashblade:blade_runtime_state={attackAmplifier: 0.0f, ..., targetEntityId: 0}]
```

所以读取只需**反射调用该方法**，再用「正则 + 花括号配平」把 `slashblade:blade_state={...}`
这一段截出来，读里面的数字字段：

- 配平会**跳过字符串与转义**，所以 `carryType: "pso2"`、`comboRoot: "slashblade:standby"` 里的冒号不会干扰
- 正则带 `(?<![\w:])` 前缀，确保只命中**本段**的 `killCount`，不会误伤 `blade_runtime_state` 段里的同名字段

这一步不执行任何命令，是 `/sbkill get` 的唯一路径。

### 写入：一次只发一条命令，校验延后一拍

方案按「**`minecraft:item` → `item`（普通）→ 命令版中转盔甲架**」依次尝试，
**每换一个方案前先等一拍复验**，所以正常情况下只发一条命令。

**首选：`minecraft:` 命名空间强制原版（本服实测的成功路径 ✅）**

> 本服有个「防刷物 / 物品管理」类插件接管了 `/item`，普通写法会被拦，控制台提示
> `We can't give you this, its not an item`（带 `§x` 前缀 = **不是原版消息**）。
> 加上命名空间后就绕过它了：

```
minecraft:item replace entity <玩家> weapon.mainhand with <物品ID>[<改好字段的完整组件SNBT>]
```

> 实测成功，原版回显 `Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]`
> （**原版消息不带 `§x` 前缀**，据此可区分是谁在回话）。
> 只有命名空间写法确实无效时（极少数禁用了 `minecraft:` 前缀命令的服）才会退回普通写法。

### 关键：校验必须「延后一拍」

本服实测到一个很坑的行为：**命令明明执行成功，插件却判定失败**。

原因：命令反馈是**即时**的（原版 `Replaced a slot on …` 立刻出现），但
`player.getInventory().getItemInMainHand()` 在**同一 tick 内**读到的还是旧值
（Bukkit 侧物品镜像要等 tick 结束才回灌）。于是"写完立刻回读"必然读到旧值 → 误判失败。

修法：**写入后先同步校验一次**（常规服务端在这里就过了，零延迟），
**不过则等下一 tick 复验**，通过才换下一个方案。所以你看到的成功提示会晚约 50 ms。

> 本服最终日志（2026-10-11 15:58 实测通过）：
> ```
> [CMD] /minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[ … ]
> Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]          ← 原版反馈
> [写入成功] QZRS KILL 5000 -> 5555（通道：minecraft:item（命名空间） + 延后复验）
> ```
> 注意 `KILL 5000 -> 5555`：旧值 5000 正是上一轮写入的结果，说明**写入一直是成功的**，
> 早期版本报的"写入失败"纯属校验误判。

### 兜底：命令版中转盔甲架

前两条都不行时才启用（本服不会用到）。它不是 Bukkit 造实体，而是**全命令**实现：

```
① execute at <玩家> run execute unless entity @e[type=armor_stand,tag=sbke_<玩家>,limit=1] run summon armor_stand ~ ~ ~ {...}
② execute at <玩家> run item replace entity @e[tag=sbke_<玩家>,limit=1] weapon.mainhand from entity <玩家> weapon.mainhand
③ execute at <玩家> run data modify entity @e[tag=sbke_<玩家>,limit=1] equipment.mainhand.components."slashblade:blade_state".<字段> set value <值>
④ execute at <玩家> run execute if data entity @e[tag=…,limit=1] equipment.mainhand.count run item replace entity <玩家> weapon.mainhand from entity @e[tag=…,limit=1] weapon.mainhand
```

三个设计要点：

| 设计 | 为什么 |
|------|--------|
| **每玩家一个常驻**隐形 Marker 盔甲架 + `unless entity` **懒召唤** | 热路径**完全不需要 `/kill`**。本服 `/kill` 被插件接管，裸 `kill @e[…]` 会被当玩家名查、必失败 |
| 第 ④ 步前面加 `execute if data entity … equipment.mainhand.count` | **绝不用空手覆盖玩家主手**。没有这道判断，万一中转没接到刀，第 ④ 步会把玩家的刀换成空气（等于销毁） |
| 中转实体在插件卸载时由 Bukkit 统一 `remove()` | 不用命令，不依赖任何被接管的命令 |

### 要点

- **零 NMS 依赖**，不碰模组内部类，Arclight / Spigot / Paper 通用
- 所有通道**以回读结果为准**；单次读取不可信时自动延后一拍复验
- 读取（`/sbkill get`）**完全不执行命令、不生成实体**
- 所有命令都以 `[CMD] /…` 打到控制台（`verbose-commands: true` 时），排错一目了然
- 写入成功/失败都会在控制台留一行 `[写入成功] …（通道：…）` / `[写入失败] … -> 原因`

---

## 三、安装

1. 把 `SlashBladeKillEditor-1.0.0.jar` 放进服务端 `plugins/` 目录
2. 重启服务端（Arclight）
3. 控制台出现下面这行即成功：
   ```
   [SlashBladeKillEditor] SlashBladeKillEditor 已启用 —— 目标组件路径 slashblade:blade_state
   ```
4. 首次启动会生成 `plugins/SlashBladeKillEditor/config.yml`

> 两台后端（家园 / 资源）**都要放**，否则跨服切换后命令时有时无。

---

## 四、命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/sbkill get` | 查看手持刀的三个数值 | `sbkilleditor.use` |
| `/sbkill set <数量>` | 设置杀敌数 | `sbkilleditor.use` |
| `/sbkill add <数量>` | 增减杀敌数（可填负数） | `sbkilleditor.use` |
| `/sbkill reset` | 杀敌数归零 | `sbkilleditor.use` |
| `/sbkill soul <数量>` | 设置耀魂 | `sbkilleditor.admin` |
| `/sbkill refine <数量>` | 设置锻造数 | `sbkilleditor.admin` |
| `/sbkill reload` | 重载配置 | `sbkilleditor.admin` |
| `/sbkill <玩家> <子命令>` | 代其他玩家操作 | 追加 `sbkilleditor.others` |

别名：`/bladekill`、`/sbke`。

### 权限：默认**只有 OP 能执行**

采用**双保险**，非 OP 玩家既执行不了、Tab 补全里也看不到这条命令：

| 层 | 机制 | 效果 |
|----|------|------|
| 命令级 | `plugin.yml` 里 `sbkill.permission: sbkilleditor.use`（default: op） | 无权限者输入 `/sbkill` 直接提示"没有权限"，且**命令名不在 Tab 补全中出现** |
| 代码级 | 入口统一门禁 + `ops-only: true` 硬开关（`sender.isOp()` 判定） | 即使权限被误配给非 OP，仍拒绝执行 |

- `ops-only: true`（**默认**）：只有服务端 OP 能执行，控制台天然视为 OP
- `ops-only: false`：关闭 OP 限制，改用权限节点下放（配合 LuckPerms 用）

> 想严格"只有 OP"就保持默认，什么都不用改。

示例：

```
/sbkill set 5000       # 手持刀的杀敌数 = 5000
/sbkill add 500        # 在现有基础上 +500
/sbkill Steve set 0    # 把 Steve 手上那把刀的杀敌数清零
```

---

## 五、配置（plugins/SlashBladeKillEditor/config.yml）

```yaml
# 仅允许服务端 OP 执行（true = 最严格，非 OP 看不到也用不了；false = 走权限节点）
ops-only: true

limits:
  max-kill-count: 1000000     # 上限，admin 无视
  max-proud-soul: 1000000
  max-refine: 1000000
  allow-negative: false       # 是否允许负数
sync-inventory-after-write: true   # 改完强制刷新背包，让 tooltip 立即变
verbose-commands: true      # 把派发出去的每条命令打到控制台（[CMD] 前缀），排错用
messages:                     # 全部文案可改，支持 & 颜色码
  prefix: "&8[&c拔刀剑&8] &r"
```

> `verbose-commands` 默认 **开**。确认一切正常后可改成 `false` 让日志安静下来；
> 排查写入问题时**务必先开着**，控制台会逐条打印插件执行了什么命令。

---

## 六、上线后的自检（重要）

**装好后请按顺序验证一次**：

```
① 手上拿一把拔刀剑
② /sbkill get
   成功 → 显示「杀敌数 x | 耀魂 y | 锻造数 z」，收工
③ /sbkill set 5000
   成功 → 聊天栏提示已写入，tooltip 立刻变；控制台能看到（本服实测输出）：
          [SlashBladeKillEditor]: [CMD] /minecraft:item replace entity <你> weapon.mainhand with …(共 1xxx 字符)
          Replaced a slot on <你> with [『…』]                          ← 原版反馈，不带 §x
          [SlashBladeKillEditor]: [写入成功] <你> KILL 0 -> 5000（通道：minecraft:item（命名空间） + 延后复验）
   ↑ 正常情况下只发一条命令；成功这一行会晚约 50 ms（校验延后一拍），属正常
④ 若提示「手上没有拿着拔刀剑 / 未找到刀刃数据组件」：
   执行 /sbkill debug（仅 OP）
   → 聊天栏与服务端 latest.log 会输出：
     · 手持物命名空间 ID 与两条组件串（getAsComponentString / getAsString）
     · 解析出的 killCount / proudSoul / refine
     · **两条写入命令预览**（命名空间写法 + 普通写法）
     · 写入方案顺序、中转标签名
   把日志里 ===== SlashBladeKillEditor 诊断 ===== 那一段发我即可定位
```

**怎么区分"是原版在回话"还是"插件在回话"**：原版反馈**不带** `§x` 前缀，
插件/第三方的消息**都带 `§x`**。例：

| 日志行 | 谁 | 含义 |
|---|---|---|
| `Replaced a slot on QZRS with [...]` | 原版 | `item replace` 成功 |
| `Modified entity data of Armor Stand` | 原版 | `data modify` 成功 |
| `§xWe can't give you this, its not an item` | 插件 | 普通 `/item` 被拦（已被 `minecraft:` 写法绕过） |
| `§xCan't find player with this name! … (kill -> @e[…])` | 插件 | 裸 `/kill` 被接管（插件已不再用它） |

**排错时最有用的一行**：把 `verbose-commands` 保持为 `true`，控制台里所有 `[CMD] /…`
就是插件实际执行的命令，从前往后逐条看，哪条之后结果不对，问题就在那里。

手动核对命令（任何时候可用）：

```bash
/data get entity @s SelectedItem             # 手持物品完整数据
/data get entity @s SelectedItem.components  # 只看组件（只读，不受「玩家数据不可修改」限制）
```

---

## 七、已知限制

| 项 | 说明 |
|----|------|
| 只作用于**主手选中**物品 | 副手 / 背包内不处理（扩展槽位参数即可支持） |
| 目标玩家必须**在线** | 玩家数据只存在于内存中 |
| 操作时会短暂生成一个**隐形标记盔甲架** | 命令写入成功时**不生成任何实体**；只有三条命令通道都不行才启用兜底，此时每玩家留一个 `Marker+Invisible+Silent` 盔甲架（不可见、无碰撞、无声），插件卸载时自动清理 |
| 主路径依赖的命令 | `minecraft:item` / `item`（写入）。**热路径不使用 `/kill`、`/summon`** |
| 若服务器接管了 `/item` | 插件**先试 `minecraft:item`**（实测可绕过）；只有命名空间写法也无效时才退回普通写法 |
| 写入结果延迟 1~3 tick | 本服 Arclight 的 Bukkit 物品镜像要等 tick 结束才回灌，所以"写入成功/失败"提示会晚一拍（约 50 ms）；每换一个方案再等一拍，最坏约 150 ms |
| 兜底路径会留下一个隐形实体 | 只有前两条通道都失败时才启用；每玩家一个 `Marker+Invisible+Silent` 盔甲架，**不可见、无碰撞、无声音**，插件卸载时自动清理。主路径不生成任何实体 |
| **不会丢物品** | 兜底的搬回操作前有 `execute if data entity … equipment.mainhand.count` 判断，**绝不会用空手覆盖玩家主手**；万一真丢了还会自动抢救并打印手工恢复命令 |
| 仅 Java 版有效 | 所用命令均为 Java 版专属，基岩版不适用 |
| 极少数情况客户端不刷新 | 已内置 `sync-inventory-after-write: true` 兜底 |
| 数值含义 | `killCount` 会影响部分刀的特效/成长；`proudSoul` 是精炼消耗品；`refine` 影响加成。改前建议先 `/sbkill get` 记下原值 |

---

## 八、回滚

| 改动 | 回滚方式 |
|------|----------|
| 插件 jar | 直接删除 jar + 重启服务端，服务端恢复原样 |
| 刀的数据 | **不可逆**。改之前先 `/sbkill get` 记下原值，或用 `/sbkill set` 写回 |
| config.yml | 删除让插件重新生成默认值 |

> 只读操作（`/sbkill get`）无任何副作用，随便用。

---

## 九、重新编译

```bash
cd "D:/WorkBuddy/我的世界/SlashBladeKillEditor"
./gradlew build
# 产物：build/libs/SlashBladeKillEditor-1.0.0.jar
```

- 需要 JDK 21（本机已装 Temurin 21）
- 依赖仅 `org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT`（compileOnly）
- 源码结构：

```
src/main/java/cn/qzrs/sbkilleditor/
├── SlashBladeKillEditor.java   主类
├── PluginConfig.java           配置与文案
├── BladeAccess.java            组件读写核心（改路径候选看这里）
└── SbkillCommand.java          命令解析、权限、Tab 补全
```

---

## 十、不想装插件？

也可以用原版命令手搓一遍（代价是没有权限控制与上限校验）。完整链路如下，
把 `<你>` 换成你的游戏名、`5000` 换成目标杀敌数：

```mcfunction
# 设 <你> = 你的游戏名，<杀敌数> = 目标值
# ⚠️ 这条链路会把刀先搬离你手上，属于"有风险"版本；能用第 3 行的 item 命令就别用它
# 注意 1：所有含 @e 的命令都要带 `execute at <你> run`，
#         否则执行源（服务端/函数）没有位置与维度，@e 会选不到实体
# 注意 2：清理必须用 `minecraft:kill` —— 裸 `kill` 在装了接管插件时会被当玩家名查，删不掉实体
minecraft:kill @e[type=armor_stand,tag=sbke_man]
execute at <你> run summon armor_stand ~ ~ ~ {Tags:["sbke_man"],Marker:1b,Invisible:1b,Silent:1b,ShowArms:1b}
execute at <你> run item replace entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand from entity <你> weapon.mainhand
execute at <你> run data modify entity @e[type=armor_stand,tag=sbke_man,limit=1] equipment.mainhand.components."slashblade:blade_state".killCount set value <杀敌数>
# 搬回前必须确认中转手上真有东西，否则会把你的主手清空！
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] equipment.mainhand.count run item replace entity <你> weapon.mainhand from entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand
minecraft:kill @e[type=armor_stand,tag=sbke_man]
```

> 若第 4 行报「找不到路径」，把 `equipment.mainhand` 换成 `HandItems[0]`（1.21 之前的老写法）。
> 另外注意：**直接用 `/data modify entity <玩家>` 是行不通的**，Minecraft 明确禁止修改玩家数据。

**更省事的做法（本服实际可用的路径）**：直接给 `/item` 加命名空间就能绕过接管它的插件，
一条命令搞定、不生成任何实体：

```mcfunction
# 用 /sbkill debug 打印出的「写入命令预览(命名空间)」复制粘贴即可，形如：
minecraft:item replace entity <你> weapon.mainhand with slashblade:slashblade[slashblade:blade_state={...,killCount: <杀敌数>,...},slashblade:blade_runtime_state={...}]
```

> 本服实测：**普通写法被插件拦**（`We can't give you this, its not an item`），
> **加 `minecraft:` 就通了**（原版回显 `Replaced a slot on <你> with [...]`）。

**如果命名空间写法也被拦**，才需要走命令版中转（注意清理必须用 `minecraft:kill`，不能裸写 `kill`）：

```mcfunction
execute at <你> run summon armor_stand ~ ~ ~ {Tags:["sbke_man"],Marker:1b,Invisible:1b,Silent:1b,ShowArms:1b}
execute at <你> run item replace entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand from entity <你> weapon.mainhand
execute at <你> run data modify entity @e[type=armor_stand,tag=sbke_man,limit=1] equipment.mainhand.components."slashblade:blade_state".killCount set value <杀敌数>
# 搬回前先确认中转手上真有东西，否则会把你的主手清空！
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] equipment.mainhand.count run item replace entity <你> weapon.mainhand from entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand
minecraft:kill @e[type=armor_stand,tag=sbke_man,limit=1]
```

> ⚠️ 清理那行**必须写 `minecraft:kill`**。本服实测裸 `kill` 被插件接管，会把 `@e[...]` 当玩家名去查，
> 提示 `Can't find player with this name!` / `The player is not online!`，实体根本删不掉。
>
> ⚠️ 命令版中转有个固有风险：第 3 步会把刀**从玩家手上搬走**，万一最后一步失败，刀就卡在盔甲架里了；
> 所以搬回那行一定要带 `execute if data entity … equipment.mainhand.count` 判断，避免把空手换上主手。

插件的价值在于：**权限分级 + 数值上限/负数校验 + 代他人操作 + Tab 补全 + 统一文案 + 写入回读校验**。

---

## 十一、常见报错排查

### 1) `InvalidDescriptionException: Invalid plugin.yml` —— 插件加载不了

```
org.bukkit.plugin.InvalidDescriptionException: Invalid plugin.yml
Caused by: org.yaml.snakeyaml.scanner.ScannerException: mapping values are not allowed here
 in 'reader', line 6, column 47:
     ... 改手持拔刀剑的杀敌数 / 耀魂 / 锻造数（SlashBlade: Resharped 1.21.1）
```

**根因**：YAML 里出现了**未加引号的「冒号 + 空格」**。YAML 把它当作键值分隔符，
于是 `description: ... （SlashBlade: Resharped ...）` 后半段被解析成非法映射。
（v1.0.0 初版就是踩了这个坑，现已修正。）

**修复**：整个值用单引号包起来，或直接去掉冒号。
```yaml
# ✗ 错误
description: 修改……（SlashBlade: Resharped 1.21.1）
# ✓ 正确
description: '修改……（SlashBlade Resharped 1.21.1）'
```

**通用规则**：`plugin.yml` / `config.yml` 里任何值只要含 `:`、`#`、`&`（非颜色码场景）、`{`、`}`，
一律用引号包住。文末有自检命令。

### 2) 插件加载成功，但 `/sbkill` 提示"未知命令"

- 检查是否 OP：非 OP 玩家看不到也用不了（设计如此，见第四节）
- 控制台执行 `/plugins` 确认插件是绿色（启用）状态

### 3) `/sbkill` 提示「手上没有拿着拔刀剑 / 未找到刀刃数据组件」

三种可能，按顺序排除：

1. **手上真没拿刀** → 切换主手选中槽位再试
2. **该物品不是 SlashBlade: Resharped 的刀**（例如附属模组的刀、或 1.20.1 老档遗留物）
3. **组件路径与预期不同** → 执行 `/sbkill debug`，把日志里 `诊断` 那一段发回

### 4) 控制台出现 `We can't give you this, its not an item` / `Can't find player with this name!`

这两条**都不是原版消息**——注意它们在日志里带一个 `§x` 前缀，而原版反馈
（`Summoned new Armor Stand`、`Replaced a slot on ...`、`Armor Stand has the following entity data: ...`）
都没有前缀。这说明服上有插件**接管了原版命令**：

| 现象 | 被接管的命令 | 插件的行为 |
|------|--------------|-----------|
| `We can't give you this, its not an item` | `/item` | 拒绝 `item replace … with <物品>`（防刷物），但放行 `… from entity` |
| `Can't find player with this name!` + `The player is not online!` | `/kill` | 把 `@e[...]` 当**玩家名**去查，于是永远失败 |

**插件已经自动处理**：

- `/item` → **先试 `minecraft:item`**（命名空间强制原版），**这条在本服是通的**；
  只有命名空间写法也无效时才退回普通写法
- `/kill` → 热路径**已经完全不用它**；兜底中转改成「每玩家一个常驻隐形盔甲架 + 懒召唤」

只要控制台能看到这行就说明写进去了（本服 2026-10-11 15:58 实测）：

```
Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]                                  ← 原版反馈
[SlashBladeKillEditor]: [写入成功] QZRS KILL 5000 -> 5555（通道：minecraft:item（命名空间） + 延后复验）
```

**手工验证哪个命令被接管**（安全、无副作用）：

```bash
/minecraft:kill @e[type=armor_stand,tag=不存在的标签]   # 回 "No entity was found" 是原版；回"找不到玩家"是被接管
/data get entity @s SelectedItem                        # 能读出来说明 /data 正常
```

### 5) 提示「写入失败」

按方案顺序排查，每步都以**回读结果**为准：

| 方案 | 失败表现 | 处理 |
|------|----------|------|
| 1 `minecraft:item` | 日志有 `[CMD] 派发失败 /minecraft:item …` | 该服禁用了 `minecraft:` 前缀命令；插件会自动退回普通写法，无需处理 |
| 2 普通 `item` | 控制台 `We can't give you this, its not an item` | 被「防刷物」插件拦；说明命名空间写法也不通，插件会进入中转兜底 |
| 3 中转盔甲架 | `[抢救] … 主手为空…` | 中转搬运未成功，刀可能卡在中转实体里；按日志里给出的手工命令可搬回 |
| 3 中转盔甲架 | 日志里 `data modify` 报路径错误 | 该刀组件结构不匹配，跑 `/sbkill debug` 把日志发回 |

先跑 `/sbkill debug`，重点看这几行：
`组件(getAsComponentString)` 是否有完整 SNBT、`解析 killCount=` 是否读到数、
`写入方案顺序=`、`中转标签=`，再对症处理。

### 6) 改任何 YAML 后的自检命令

```bash
# 需要 pyyaml；没有就：pip install pyyaml
python -c "import yaml,sys;yaml.safe_load(open(sys.argv[1],encoding='utf-8'));print('YAML OK')" plugin.yml
```

更省事的做法：改完 YAML 直接在本地跑一次 `./gradlew build`，
再用 `unzip -p build/libs/SlashBladeKillEditor-1.0.0.jar plugin.yml` 肉眼复核。
