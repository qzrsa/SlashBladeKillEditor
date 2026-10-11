# SlashBladeKillEditor 教程

> 在线修改手持拔刀剑的 **杀敌数 / 耀魂 / 锻造数**  
> 目标环境：**Arclight NeoForge 1.21.1** + **SlashBlade: Resharped 2.0.x**  
> 产物：`SlashBladeKillEditor-1.0.3.jar`（已编译好，直接丢 plugins）
>
> ✅ **状态：已在本服实机验证通过**（2026-10-11，`resource_world` 下三把不同拔刀剑  
> 各 `/sbkill set 5555` 一次，**3/3 成功**）。本服有插件接管了 `/item` 与 `/kill`，  
> 方案已针对这种情况定型，详见第二节。
>
> 📌 **v1.0.3**：**超长写入命令不再白跑** —— 主命令超过 `relay-first-above-length`（默认 2000）字符  
> 就直接走中转盔甲架。实测 898/976/958 字符的三把刀走命令通道成功，而 **3099 字符**的重度改装刀  
> （镶嵌宝石 + 词缀 + 十来个附魔）**三条通道全挂** —— 长命令本身就是失败信号。见 §二、§五。  
> 同时修正中转残留物处理（`from entity` 是**复制**语义，残留副本必须清除而不是掉在地上），  
> 并新增排障开关 `capture-command-feedback`（把服务端对每条命令的回话打出来）。
>
> 📌 **v1.0.1**：控制台日志降噪 —— `verbose-commands` 默认关闭，写入成功只留一行；  
> 即便打开也把上千字符的组件体折叠成 `[…N 字符…]`。见 §五。

---

## 一、它改的是什么

1.21.1 版的 **SlashBlade: Resharped（拔刀剑:重锋）** 不再把刀刃数据写在根 NBT 里，  
而是改用**模组自定义物品组件**：

| 项目       | 值                                                                 |
| -------- | ----------------------------------------------------------------- |
| 组件 ID    | `slashblade:blade_state`                                          |
| 数据类      | `mods.flammpfeil.slashblade.capability.slashblade.BladeStateData` |
| 杀的实体数    | 字段 `killCount`（int）                                               |
| 耀魂       | 字段 `proudSoul`（int）                                               |
| 锻造数      | 字段 `refine`（int）                                                  |
| 对应原版命令路径 | `SelectedItem.components."slashblade:blade_state".killCount`      |

来源：仓库 `0999312/SlashBlade_Resharped` 分支 `1.21.1`  
（`SlashBladeDataComponents.java` 注册组件，`BladeStateData.java` 定义 `killCount/proudSoul/refine`）。

> ⚠️ 注意：网上流传的 `{killCount:5000, ProudSoul:10000}` 是 **1.20.1 Forge 及更早**的写法，  
> 在 1.21.1 上**不生效**。铭文/SA/模型等仍在同一组件内，本插件只动指定字段，不会破坏其他数据。

---

## 二、原理

### 两条硬约束

| # | 约束                      | 说明                                                                                              |
| - | ----------------------- | ----------------------------------------------------------------------------------------------- |
| 1 | **`/data` 改不了玩家数据**     | Minecraft 规定「**无法修改（包括设置和删除）玩家的 NBT 数据**」，`data modify entity <玩家>` 必定失败；官方给的正道是 **`/item` 命令** |
| 2 | **Bukkit 的类型枚举里没有模组组件** | `DataComponentTypes` 只列原版组件，`slashblade:blade_state` 不在其中——**但这不等于读不到**                         |

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

> 📌 **v1.0.3 新增捷径**：主命令**超过 2000 字符**时直接跳到第三条（中转盔甲架），  
> 不再先试两条注定失败的通道（也少刷两行日志）。阈值可调，见 §五。
>
> 依据是本服实测的两组数据：
>
> | 刀 | 主命令长度 | 命令通道结果 |
> |---|---|---|
> | 三把普通刀（`resource_world` 验证过的那三把） | 898 / 976 / 958 字符 | ✅ 成功 |
> | `弱化的dl『鲨齿』（刽子手）`（apotheosis 镶嵌宝石 + 词缀 + 十来个附魔） | **3099 字符** | ❌ 三条通道全挂 |
>
> 长命令本身就是失败信号 —— 与其白跑两次不如直接走中转（中转那条链路是 `from entity`  
> 复制 + `data modify` 改数字，命令都是短的，完全不受长度影响）。

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

> 本服最终日志（2026-10-11 实测通过）：
>
> ```
> Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]          ← 原版反馈
> [写入成功] QZRS 杀敌数 5000 → 5555
> ```
>
> 就这么两行。注意 `5000 → 5555`：旧值 5000 正是上一轮写入的结果，说明**写入一直是成功的**，  
> 早期版本报的"写入失败"纯属校验误判。
>
> 首选通道命中时**不写通道名**；只有走了别的通道才会标出来，形如  
> `（通道：中转盔甲架（长命令捷径））`（主动选的，见上）或 `（通道：中转盔甲架）`（前两条都失败后的兜底）。  
> `verbose-commands` 默认关闭，所以没有 `[CMD]` 行 —— 见 [§五 配置](#五配置pluginsslashbladekilleditorconfigyml)。

### 兜底：命令版中转盔甲架

前两条都不行时才启用。它不是 Bukkit 造实体，而是**全命令**实现（`<主手>` 见下方路径表）：

```
① execute at <玩家> run execute unless entity @e[type=armor_stand,tag=sbke_<玩家>,limit=1] run summon armor_stand ~ ~ ~ {...}
② execute at <玩家> run item replace entity @e[tag=sbke_<玩家>,limit=1] weapon.mainhand from entity <玩家> weapon.mainhand
③ execute at <玩家> run execute if data entity @e[tag=…,limit=1] <主手>.components."slashblade:blade_state".<字段> run data modify entity @e[tag=…,limit=1] <主手>.components."slashblade:blade_state".<字段> set value <值>
④ execute at <玩家> run execute if data entity @e[tag=…,limit=1] <主手>.count run item replace entity <玩家> weapon.mainhand from entity @e[tag=…,limit=1] weapon.mainhand
```

**`<主手>` 这条路径跨版本变过，写错会静默失效**：

| 服务端版本                             | 主手 NBT 路径            | 说明                                                                      |
| --------------------------------- | -------------------- | ----------------------------------------------------------------------- |
| **1.21.4 及以前**（含本插件目标 **1.21.1**） | **`HandItems[0]`**   | 老字段，插件**优先用这条**                                                         |
| 1.21.5 起                          | `equipment.mainhand` | 官方把 `ArmorItems` / `HandItems` / `body_armor_item` **合并成了 `equipment`** |

> ⚠️ **为什么"写错也不报错"**：`data modify ... set value` 会**自动补齐不存在的中间复合标签**。  
> 在 1.21.1 上对它执行 `... equipment.mainhand.components."slashblade:blade_state".killCount set value 5300`  
> —— 命令回的是 `Modified entity data of Armor Stand`，**看起来成功了**，  
> 但那个 `equipment` 键只是被凭空造在实体根上，真正的主手（`HandItems[0]`）一点没动。
>
> **v1.0.0 / v1.0.1 就栽在这里**：中转通道在 1.21.1 上一直静默失效（改的一直是那个凭空造出来的  
> `equipment` 键）。同时 `moveBack` 的搬回判断也用了同一条错路径（`equipment.mainhand.count` 恒为假），  
> 于是中转里那份副本**搬不回来** —— 玩家手里的数值永远不更新，而中转实体上那份改好的副本  
> 又没人认领。修法见下。

> 🔑 **重要语义：`from entity` 是「复制」，不是「搬移」**  
> Minecraft Wiki（命令 `/item`）对 source 参数的描述是 *"Specifies an entity to **copy** items from"*  
> —— 第 ② 步只是把主手那把刀**复制**进中转实体，**源槽位不会清空**。  
> 所以整条链路里**玩家全程不会失去手里的刀**（这一点比早期想象的安全得多），  
> 代价是中转实体上会留一份副本：第 ④ 步把改好的副本覆盖回主手后，中转手里那份是**重复件**。
>
> 因此：**写入成功后必须清除残留副本再删实体**，绝不能让它掉到地上。  
> v1.0.2 之前的写法是"手上还有东西就先不删"，结果盔甲架带着副本留在场上 ——  
> 插件卸载时一掉就是**凭空多一把刀**。v1.0.3 已修正：

| 时机 | 处理 |
|---|---|
| 写入成功（`finishRelay`） | 取走残留副本 → 记一条 `[清理] … 判定为重复副本并清除` → 删实体。残留物**只可能是重复件**（成功意味着副本已回主手），所以清除是安全的 |
| 全通道失败（`rescueIfLost`） | **只在玩家主手为空时**才动用（此时才真的可能丢），用纯 Bukkit 把刀搬回主手 |
| 插件卸载 / 清理（`recoverRelayItems`） | **先取物品再删实体** —— `Entity#remove()` **不掉落装备**，顺序反了就把卡住的刀一起销毁。取出的物品：主人**在线且主手为空** → 归还；主人**在线但主手有东西** → 判定重复副本，**清除并告警**；主人**不在线** → 原地掉落（无法判断，宁可掉地上也不销毁） |

三条设计要点：

| 设计                                                        | 为什么                                                                   |
| --------------------------------------------------------- | --------------------------------------------------------------------- |
| 每条 `data modify` 前都加 `execute if data entity <中转> <字段路径>` | **让「路径不对」表现为「什么都不做」而不是「假装成功」**。没有这道判断，`set value` 会凭空造路径，命令回成功而实际没改   |
| 第 ④ 步按**两条路径各做一次** `execute if data … <路径>.count`         | 既**绝不用空手覆盖玩家主手**（判断成立才搬），又**不会因猜错路径把刀永久留在中转里**。刀只会存在于其中一条路径上，所以只会命中一次 |
| 清理**先取物品、再删实体**（见上表）                                    | `Entity#remove()` 不掉落装备；顺序反了 = 把还装在中转里的刀直接销毁                                  |
| 抢救走**纯 Bukkit**（`findRelay` + `getEquipment()`），不复用命令     | 上一版抢救也用同一条错路径做判断，于是抢救本身静默失效 —— 抢救不能依赖被抢救的那条路径                         |

### 要点

- **零 NMS 依赖**，不碰模组内部类，Arclight / Spigot / Paper 通用
- 所有通道**以回读结果为准**；单次读取不可信时自动延后一拍复验
- 读取（`/sbkill get`）**完全不执行命令、不生成实体**
- 写入成功只在控制台留**一行** `[写入成功] 玩家 杀敌数 0 → 5555`；失败留一行 `[写入失败] … -> 原因`
- `[CMD]` 命令回显**默认关闭**（`verbose-commands: false`）。即便打开也走**折叠**：  
  上千字符的组件体压成 `[…N 字符…]`（898 → 88 字符），不会刷屏；完整命令用 `/sbkill debug` 拿

---

## 三、安装

1. 把 `SlashBladeKillEditor-1.0.3.jar` 放进服务端 `plugins/` 目录
2. 重启服务端（Arclight）
3. 控制台出现下面这几行即成功（v1.0.3 起会多打两行环境信息，便于事后对账）：
   ```
   [SlashBladeKillEditor] SlashBladeKillEditor 已启用 —— 目标组件路径 slashblade:blade_state
   [SlashBladeKillEditor]   服务端: 1.21.1-R0.1-SNAPSHOT | 中转装备路径候选: HandItems[0] → equipment.mainhand
   [SlashBladeKillEditor]   命令长度阈值: 主命令超过 2000 字符直接走中转盔甲架 | [CMD] 命令回显: 关（默认）
   [SlashBladeKillEditor]   写入失败时会自动打印本次试过的命令；反馈捕获: 关（默认，排障时可开）
   ```
4. 首次启动会生成 `plugins/SlashBladeKillEditor/config.yml`

> 「中转装备路径候选」这行会用 **`→` 标出优先顺序**：1.21.1 上排第一的必须是 `HandItems[0]`。  
> 如果哪天换成 1.21.5+ 的服务端，插件会先试 `HandItems[0]`（不存在 → 什么都不做）再试  
> `equipment.mainhand`（存在 → 生效），**两条都带 `if data` 判断，所以不会误伤**。

> 两台后端（家园 / 资源）**都要放**，否则跨服切换后命令时有时无。

---

## 四、命令

| 命令                    | 说明          | 权限                       |
| --------------------- | ----------- | ------------------------ |
| `/sbkill get`         | 查看手持刀的三个数值  | `sbkilleditor.use`       |
| `/sbkill set <数量>`    | 设置杀敌数       | `sbkilleditor.use`       |
| `/sbkill add <数量>`    | 增减杀敌数（可填负数） | `sbkilleditor.use`       |
| `/sbkill reset`       | 杀敌数归零       | `sbkilleditor.use`       |
| `/sbkill soul <数量>`   | 设置耀魂        | `sbkilleditor.admin`     |
| `/sbkill refine <数量>` | 设置锻造数       | `sbkilleditor.admin`     |
| `/sbkill reload`      | 重载配置        | `sbkilleditor.admin`     |
| `/sbkill <玩家> <子命令>`  | 代其他玩家操作     | 追加 `sbkilleditor.others` |

别名：`/bladekill`、`/sbke`。

### 权限：默认**只有 OP 能执行**

采用**双保险**，非 OP 玩家既执行不了、Tab 补全里也看不到这条命令：

| 层   | 机制                                                                | 效果                                               |
| --- | ----------------------------------------------------------------- | ------------------------------------------------ |
| 命令级 | `plugin.yml` 里 `sbkill.permission: sbkilleditor.use`（default: op） | 无权限者输入 `/sbkill` 直接提示"没有权限"，且**命令名不在 Tab 补全中出现** |
| 代码级 | 入口统一门禁 + `ops-only: true` 硬开关（`sender.isOp()` 判定）                 | 即使权限被误配给非 OP，仍拒绝执行                               |

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
verbose-commands: false     # 是否回显派发出去的命令（[CMD] 前缀）。默认关
relay-first-above-length: 2000   # 主命令超过这个字符数就直接走中转（v1.0.3，0 = 关闭）
capture-command-feedback: false  # 排障：捕获并打印服务端对每条命令的回话（v1.0.3）
messages:                     # 全部文案可改，支持 & 颜色码
  prefix: "&8[&c拔刀剑&8] &r"
```

### `relay-first-above-length`（v1.0.3）

| 值 | 行为 |
|---|---|
| `2000`（默认） | 主写入命令超过 2000 字符 → 跳过两条命令通道，直接走中转盔甲架 |
| `0` 或负数 | 关闭此优化 —— 永远按 `minecraft:item → item → 中转` 的顺序全试一遍 |

调大/调小的判据很简单：**你手上那把刀越"改装"，命令越长**。装了  
apotheosis（镶嵌宝石 + 词缀）、十来个附魔的重度改装刀，组件 SNBT 能撑到 3000+ 字符。  
设为 `0` 只是让插件"多试两条注定失败的命令"，想拿到那两条命令的完整内容时可以用  
`/sbkill debug`（不用改配置）。

### `capture-command-feedback`（v1.0.3，排障用）

**默认 `false`**。打开后，写入失败时会额外打印**服务端对每条命令的回话**：

```log
[写入失败] QZRS 杀敌数=5300 —— 所有写入通道均未生效。本次实际派发了 3 条命令：
  (1) [3099 字符] /minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[…3055 字符…]
      ↳ 服务端反馈: Unknown or incomplete command, see below for error
  (2) [3089 字符] /item replace entity QZRS weapon.mainhand with slashblade:slashblade[…3055 字符…]
      ↳ 服务端反馈: §xWe can't give you this, its not an item
  (3) [231 字符] /execute at QZRS run …
[写入失败] 请把上面这段（连同此前后各几行控制台输出）发回给插件作者。
```

**为什么需要它**：`Bukkit.dispatchCommand` 只把反馈发给**命令发送者**。插件用控制台发送者  
派发时，这些行直接进了服务端日志，**插件侧拿不到** —— 这就是"命令到底为什么不生效"  
一直差最后一块证据的原因。打开后插件会临时包一层发送者代理把 `sendMessage` 抄一份下来。

> ⚠️ 唯一的副作用：某插件若对发送者做 `instanceof ConsoleCommandSender` / `(Player) sender`  
> 强转，收到代理对象会抛 `ClassCastException`（会被捕获并记入 `[CMD] 派发失败`）。  
> 所以**平时保持 `false`**，排障完改回去并 `/sbkill reload`。

> `verbose-commands` 默认 **关**。写入成功只留一条 `[写入成功]`，控制台干净。  
> 打开后也不是全量打印 —— 组件 SNBT 会被**折叠**成 `[…N 字符…]`：
>
> ```
> # false（默认）
> Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]
> [写入成功] QZRS 杀敌数 0 → 5555
>
> # true（同样干净，只是多一行命令骨架）
> [CMD] /minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[…737 字符…]
> Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]
> [写入成功] QZRS 杀敌数 0 → 5555
>
> # v1.0.0 的写法（v1.0.1 起已折叠）：一行 230 字符，看得头疼
> [CMD]/minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[minecraft:enchantments={levels: {"minecraft:unbreaking": 5}},minecraft:damage=239,minecraft:max_damage=240,slashblade:blade_state={adjust: [0. …
> ```
>
> **排障不需要开这个** —— `/sbkill debug` 会把两条完整写入命令原样打出来。

---

## 六、上线后的自检（重要）

**装好后请按顺序验证一次**：

```
① 手上拿一把拔刀剑
② /sbkill get
   成功 → 显示「杀敌数 x | 耀魂 y | 锻造数 z」，收工
③ /sbkill set 5000
   成功 → 聊天栏提示已写入，tooltip 立刻变；控制台能看到（本服实测输出）：
          Replaced a slot on <你> with [『…』]              ← 原版反馈，不带 §x
          [SlashBladeKillEditor]: [写入成功] <你> 杀敌数 0 → 5000
   ↑ 正常情况下只发一条命令；成功这一行会晚约 50 ms（校验延后一拍），属正常
   ↑ 只有走了别的通道才会标出来，形如 [写入成功] <你> 杀敌数 0 → 5000（通道：中转盔甲架（长命令捷径））
   ↑（主命令 > 2000 字符的改装刀就是这条；前两条命令通道都失败时则显示（通道：中转盔甲架））
④ 若提示「手上没有拿着拔刀剑 / 未找到刀刃数据组件」：
   执行 /sbkill debug（仅 OP）
   → 聊天栏与服务端 latest.log 会输出：
     · 手持物命名空间 ID 与两条组件串（getAsComponentString / getAsString）
     · 解析出的 killCount / proudSoul / refine
     · **两条写入命令预览**（命名空间写法 + 普通写法）及其**字符数**
     · **服务端版本**、**中转装备路径候选**（带优先顺序）、**中转实体现状**
     · 写入方案顺序、中转标签名、[CMD] 回显与反馈捕获开关状态
   把日志里 ===== SlashBladeKillEditor 诊断 ===== 那一段发我即可定位
⑤ 若提示「写入失败」：
   → 控制台会自动打出本次实际派发的每条命令（折叠 + 字符数），**不用提前开任何开关**
   → 想把「服务端对这些命令的回话」也拿到，把 config.yml 的
     capture-command-feedback 改成 true，/sbkill reload 后复现一次即可（见 §五）
```

> 💡 `写入命令长度: 普通 N 字符 / 命名空间 N 字符` 这行现在很关键 ——  
> **N 超过 2000 就说明这把刀会走中转盔甲架**（v1.0.3 的捷径），超过时 debug 输出里还会  
> 紧跟一行 `↑ 已超过 relay-first-above-length=2000 …` 明确告诉你。

**怎么区分"是原版在回话"还是"插件在回话"**：原版反馈**不带** `§x` 前缀，  
插件/第三方的消息**都带 `§x`**。例：

| 日志行                                                     | 谁  | 含义                                  |
| ------------------------------------------------------- | -- | ----------------------------------- |
| `Replaced a slot on QZRS with [...]`                    | 原版 | `item replace` 成功                   |
| `Modified entity data of Armor Stand`                   | 原版 | `data modify` 成功                    |
| `§xWe can't give you this, its not an item`             | 插件 | 普通 `/item` 被拦（已被 `minecraft:` 写法绕过） |
| `§xCan't find player with this name! … (kill -> @e[…])` | 插件 | 裸 `/kill` 被接管（插件已不再用它）              |

**排错时最有用的一招**：直接 `/sbkill debug`。它会把插件打算执行的两条完整写入命令  
（命名空间写法 + 普通写法）原样打印到控制台和聊天栏，**不需要开 `verbose-commands`**、  
也不会被折叠。若确实想看运行期实际派发了什么，再把 `verbose-commands` 临时改成 `true`，  
控制台里所有 `[CMD] /…` 就是插件实际执行的命令（组件体已折叠，不算刷屏），  
从前往后逐条看，哪条之后结果不对，问题就在那里。

手动核对命令（任何时候可用）：

```bash
/data get entity @s SelectedItem             # 手持物品完整数据
/data get entity @s SelectedItem.components  # 只看组件（只读，不受「玩家数据不可修改」限制）
```

---

## 七、已知限制

| 项                     | 说明                                                                                                     |
| --------------------- | ------------------------------------------------------------------------------------------------------ |
| 只作用于**主手选中**物品        | 副手 / 背包内不处理（扩展槽位参数即可支持）                                                                                |
| 目标玩家必须**在线**          | 玩家数据只存在于内存中                                                                                            |
| 操作时会短暂生成一个**隐形标记盔甲架** | 命令写入成功时**不生成任何实体**；只有当①主命令超过 2000 字符（v1.0.3 捷径）或②前两条命令通道都失败时，才启用兜底 —— 此时每玩家留一个 `Marker+Invisible+Silent` 盔甲架（不可见、无碰撞、无声）。**写入成功后会立刻把它删掉**，只有出问题时才会残留 |
| 主路径依赖的命令              | `minecraft:item` / `item`（写入）。**热路径不使用 `/kill`、`/summon`**                                             |
| **超长命令直接走中转**           | 主命令 > `relay-first-above-length`（默认 2000）字符时跳过两条命令通道 —— 本服实测 3099 字符的重度改装刀（镶嵌+词缀）三条通道全挂，不如直接走中转。设 `0` 可关掉此捷径 |
| 若服务器接管了 `/item`       | 插件**先试 `minecraft:item`**（实测可绕过）；只有命名空间写法也无效时才退回普通写法                                                   |
| 写入结果延迟 1~3 tick       | 本服 Arclight 的 Bukkit 物品镜像要等 tick 结束才回灌，所以"写入成功/失败"提示会晚一拍（约 50 ms）；每换一个方案再等一拍，最坏约 150 ms                |
| **不会丢物品**             | ① `item replace … from entity` 是**复制**语义，玩家手里的刀全程不会被拿走；② 搬回操作前有 `execute if data entity … <路径>.count` 判断（两条路径各一次），**绝不会用空手覆盖玩家主手**；③ 清理是**先取物品再删实体**（`remove()` 不掉落装备）；④ 万一真丢了还会用纯 Bukkit 自动抢救并告警 |
| **不会凭空多一把刀**           | 复制语义会让中转实体留一份重复副本 → 写入成功后 `finishRelay()` 清除它并删实体（**不掉落**）。清理时若主人主手已有物品，同样判定为重复副本清除而非掉落 |
| 仅 Java 版有效            | 所用命令均为 Java 版专属，基岩版不适用                                                                                 |
| 极少数情况客户端不刷新           | 已内置 `sync-inventory-after-write: true` 兜底                                                              |
| 数值含义                  | `killCount` 会影响部分刀的特效/成长；`proudSoul` 是精炼消耗品；`refine` 影响加成。改前建议先 `/sbkill get` 记下原值                     |


## 九、重新编译

```bash
cd "D:/WorkBuddy/我的世界/SlashBladeKillEditor"
./gradlew build
# 产物：build/libs/SlashBladeKillEditor-1.0.3.jar
```

- 需要 JDK 21（本机已装 Temurin 21）
- 依赖仅 `org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT`（compileOnly）
- 源码结构：

```
src/main/java/cn/qzrs/sbkilleditor/
├── SlashBladeKillEditor.java   主类，启动时打印服务端版本 / 中转路径候选 / 命令长度阈值
├── PluginConfig.java           配置与文案
├── BladeAccess.java            组件读写核心（改路径候选、改长度阈值逻辑看这里）
├── FeedbackCapture.java        排障用命令反馈捕获器（动态代理抄 sendMessage）
└── SbkillCommand.java          命令解析、权限、Tab 补全
```

### 本地自检（改代码后跑一遍）

改完 `BladeAccess.java` 后，这些是纯反射离线测试，**不需要起服务端**：

| 测试 | 覆盖 |
|---|---|
| `TestRelayLen` | 写入方案选择 `planFor()`（阈值/边界/关闭）+ `FeedbackCapture` 全部行为 |
| `TestRelay` | 双装备路径顺序、`if data` 前置判断、搬回覆盖两条路径 |
| `TestCompact` | 日志折叠 `compact()`（不误伤非 item 命令、嵌套方括号、长度） |
| `CheckYml` | 用服务端同款 snakeyaml 校验 `plugin.yml` / `config.yml` 关键键值 |

> ⚠️ **顺序坑**：这些测试走的是 `build/classes`，所以**每次改完源码必须先**  
> `./gradlew compileJava` **再跑测试** —— 否则会拿旧字节码得出错误结论  
> （本项目就踩过一次：测试报"消息没转发"，实为忘了重新编译）。
>
> 另外 classpath 里**不能有残留的旧 class 目录**（曾有个 `cn/` 拷贝把新类遮住了），  
> 且 `;` 分隔的 classpath 必须在 **PowerShell** 里跑（git bash 会重写分号）。

---

## 十、不想装插件？

也可以用原版命令手搓一遍（代价是没有权限控制与上限校验）。完整链路如下，  
把 `<你>` 换成你的游戏名、`5000` 换成目标杀敌数：

```mcfunction
# 设 <你> = 你的游戏名，<杀敌数> = 目标值
# ⚠️ 能用第 3 行的 item 命令就别用这条链路；这条是"命令通道也被拦"时的最后手段
# 注意 1：所有含 @e 的命令都要带 `execute at <你> run`，
#         否则执行源（服务端/函数）没有位置与维度，@e 会选不到实体
# 注意 2：清理必须用 `minecraft:kill` —— 裸 `kill` 在装了接管插件时会被当玩家名查，删不掉实体
# 注意 3：主手路径在 ≤1.21.4 是 HandItems[0]，1.21.5+ 才是 equipment.mainhand（本服 1.21.1 → 用前者）
minecraft:kill @e[type=armor_stand,tag=sbke_man]
execute at <你> run summon armor_stand ~ ~ ~ {Tags:["sbke_man"],Marker:1b,Invisible:1b,Silent:1b,ShowArms:1b}
execute at <你> run item replace entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand from entity <你> weapon.mainhand
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].components."slashblade:blade_state".killCount run data modify entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].components."slashblade:blade_state".killCount set value <杀敌数>
# 搬回前必须确认中转手上真有东西，否则会把你的主手清空！
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].count run item replace entity <你> weapon.mainhand from entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand
minecraft:kill @e[type=armor_stand,tag=sbke_man]
```

> ⚠️ **两个容易踩的点**：
>
> 1. **路径方向别搞反**：`HandItems[0]` 是 **≤1.21.4（含 1.21.1）** 的写法，  
>    `equipment.mainhand` 是 **1.21.5 起**才有（官方把 `ArmorItems`/`HandItems` 合并成了 `equipment`）。  
>    在 1.21.1 上写 `equipment.mainhand` **不会报错**，只会静默无效 ——  
>    `data modify … set value` 会自动补齐不存在的中间复合标签，命令回 `Modified entity data of Armor Stand`，  
>    看起来成功，实际只是凭空造了个没用的 `equipment` 键。所以上面每条都带 `execute if data` 前置判断。
> 2. **`item replace … from entity` 是「复制」不是「搬移」**（Minecraft Wiki：source 参数  
>    "Specifies an entity to **copy** items from"）—— 所以第 3 行执行完**你手上仍然有刀**，  
>    不会丢；改完副本后第 5 行再把它复制回主手覆盖。最后中转实体手里会留一份**重复副本**，  
>    所以第 6 行删实体前**不要**把它的手清空后掉落出去（那会凭空多一把刀），直接用  
>    `minecraft:kill` 连实体带副本一起删掉即可。
>
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
# 主手路径同样用 HandItems[0]（1.21.1）；1.21.5+ 才换成 equipment.mainhand
execute at <你> run summon armor_stand ~ ~ ~ {Tags:["sbke_man"],Marker:1b,Invisible:1b,Silent:1b,ShowArms:1b}
execute at <你> run item replace entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand from entity <你> weapon.mainhand
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].components."slashblade:blade_state".killCount run data modify entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].components."slashblade:blade_state".killCount set value <杀敌数>
# 搬回前先确认中转手上真有东西，否则会把你的主手清空！
execute at <你> run execute if data entity @e[type=armor_stand,tag=sbke_man,limit=1] HandItems[0].count run item replace entity <你> weapon.mainhand from entity @e[type=armor_stand,tag=sbke_man,limit=1] weapon.mainhand
minecraft:kill @e[type=armor_stand,tag=sbke_man,limit=1]
```

> ⚠️ 清理那行**必须写 `minecraft:kill`**。本服实测裸 `kill` 被插件接管，会把 `@e[...]` 当玩家名去查，  
> 提示 `Can't find player with this name!` / `The player is not online!`，实体根本删不掉。
>
> ⚠️ 第 3 行改的只是**副本**（`from entity` 是复制），你手上那把刀全程没被动过，  
> 所以即使最后一步失败也不会丢 —— 最坏情况只是场上多留一个隐形盔甲架，  
> 手动 `minecraft:kill @e[type=armor_stand,tag=sbke_man]` 清掉即可。  
> 搬回那行仍要带 `execute if data … HandItems[0].count` 判断，避免用空手覆盖主手。

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

| 现象                                                                | 被接管的命令  | 插件的行为                                                  |
| ----------------------------------------------------------------- | ------- | ------------------------------------------------------ |
| `We can't give you this, its not an item`                         | `/item` | 拒绝 `item replace … with <物品>`（防刷物），但放行 `… from entity` |
| `Can't find player with this name!` + `The player is not online!` | `/kill` | 把 `@e[...]` 当**玩家名**去查，于是永远失败                          |

**插件已经自动处理**：

- `/item` → **先试 `minecraft:item`**（命名空间强制原版），**这条在本服是通的**；  
  只有命名空间写法也无效时才退回普通写法
- `/kill` → 热路径**已经完全不用它**；兜底中转改成「每玩家一个常驻隐形盔甲架 + 懒召唤」

只要控制台能看到这行就说明写进去了（本服 2026-10-11 15:58 实测）：

```
Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]                                  ← 原版反馈
[SlashBladeKillEditor]: [写入成功] QZRS 杀敌数 5000 → 5555
```

**手工验证哪个命令被接管**（安全、无副作用）：

```bash
/minecraft:kill @e[type=armor_stand,tag=不存在的标签]   # 回 "No entity was found" 是原版；回"找不到玩家"是被接管
/data get entity @s SelectedItem                        # 能读出来说明 /data 正常
```

### 5) 提示「写入失败」

**先看控制台** —— v1.0.2 起，写入彻底失败时会**自动**把本次实际派发的每条命令打出来  
（折叠 + 字符数），不需要提前开任何开关：

```log
[写入失败] QZRS 杀敌数=5300 -> 所有写入通道均失败
[写入失败] QZRS 杀敌数=5300 —— 所有写入通道均未生效。本次实际派发了 3 条命令：
  (1) [3099 字符] /minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[…3055 字符…]
  (2) [3089 字符] /item replace entity QZRS weapon.mainhand with slashblade:slashblade[…3055 字符…]
  (3) [231 字符] /execute at QZRS run execute unless entity @e[type=armor_stand,tag=sbke_QZRS,limit=1] run summon armor_stand ~ ~ ~ {Tags:["sbke_QZRS"],Marker:1b,Invisible:1b,NoGravity:1b,Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b}
[写入失败] 主命令完整长度 3099 字符；完整内容请执行 /sbkill debug 查看。
[写入失败] 请把上面这段（连同此前后各几行控制台输出）发回给插件作者。
```

按方案顺序对照：

| 方案 | 失败表现 | 处理 |
|---|---|---|
| **长度捷径** | 日志里先出现 `[写入] 主命令 3099 字符 > 阈值 2000（relay-first-above-length），直接走中转盔甲架。` | 这不是错误，是 v1.0.3 的设计行为 —— 长命令不再白跑两条通道。若想让它按老顺序全试一遍，把 `relay-first-above-length` 设为 `0` |
| 1 `minecraft:item` | 日志有 `[CMD] 派发失败 /minecraft:item …` | 该服禁用了 `minecraft:` 前缀命令；插件会自动退回普通写法，无需处理 |
| 2 普通 `item` | 控制台 `We can't give you this, its not an item` | 被「防刷物」插件拦；说明命名空间写法也不通，插件会进入中转兜底 |
| 3 中转盔甲架 | `[抢救] … 主手为空…` | 中转搬运未成功，刀可能卡在中转实体里；日志里会给出实体标签，按 `minecraft:kill @e[type=armor_stand,tag=sbke_<你>]` 清理 |
| 3 中转盔甲架 | `[清理] 中转实体手上残留「…」…判定为重复副本并清除` | 正常收尾信息（复制语义导致中转留一份副本），不是错误 |
| 3 中转盔甲架 | 日志里 `data modify` 报路径错误 | 该刀组件结构不匹配，跑 `/sbkill debug` 把日志发回 |
| 任意 | **想知道服务端到底回了什么** | 把 `capture-command-feedback` 改成 `true` → `/sbkill reload` → 复现一次。失败时每条命令下面会多一行 `↳ 服务端反馈: …`（原版报错 / 接管插件的 `§x…` 文案都能看到）。**这是定位根因最有效的一招** |

再跑 `/sbkill debug`（仅 OP），重点看这几行：  
`组件(getAsComponentString)` 是否有完整 SNBT、`解析 killCount=` 是否读到数、  
**`写入命令长度`**（> 2000 就会走中转）、**`服务端版本`**（决定 `HandItems[0]` 还是  
`equipment.mainhand`）、**`中转装备路径候选`**、**`中转实体(sbke_xxx)`**（是否存在、手上有没有东西），  
再对症处理。

### 6) 改任何 YAML 后的自检命令

```bash
# 需要 pyyaml；没有就：pip install pyyaml
python -c "import yaml,sys;yaml.safe_load(open(sys.argv[1],encoding='utf-8'));print('YAML OK')" plugin.yml
```

更省事的做法：改完 YAML 直接在本地跑一次 `./gradlew build`，  
再用 `unzip -p build/libs/SlashBladeKillEditor-1.0.3.jar plugin.yml` 肉眼复核。
