# SlashBladeKillEditor

在线修改手持拔刀剑的**杀敌数 / 耀魂 / 锻造数**。

- 目标环境：Arclight NeoForge **1.21.1** + **SlashBlade: Resharped 2.0.x**
- 状态：**已在本服（Arclight + 插件接管 `/item`、`/kill`）实机验证通过**（2026-10-11）
- 数据位置：物品组件 `slashblade:blade_state` 的 `killCount` / `proudSoul` / `refine`
- 实现方式：**零 NMS 依赖**，Spigot / Paper / Arclight 通用
  - **读**：反射 `ItemMeta#getAsComponentString()` 拿完整组件 SNBT（含模组组件），正则 + 花括号配平解析字段
  - **写**：方案按「`minecraft:item` → `item`（普通）→ 命令版中转盔甲架」依次尝试，
    **每换一个方案前先等一拍复验**，所以正常情况只发一条命令。
    `minecraft:` 命名空间写法可绕过接管 `/item` 的插件（**本服实测的成功路径**）
  - **兜底**：全命令中转盔甲架 —— 每玩家一个常驻隐形 Marker + `unless entity` 懒召唤，
    热路径不用 `/kill`；每条 `data modify` 前加 `execute if data` 存在性判断
    （`set value` 会凭空补齐不存在的路径，不判断则"写错路径"会伪装成成功）；
    搬回按**两条装备路径**各判断一次（`HandItems[0]` / `equipment.mainhand`），**绝不误清主手**
  - **超长命令直接走中转**：主写入命令超过 `relay-first-above-length`（默认 **2000**）字符时，
    跳过两条命令通道直接走中转。实测 898/976/958 字符的刀走命令通道成功，
    而 **3099 字符**的重度改装刀（镶嵌宝石 + 词缀 + 十来个附魔）三条通道全挂 —— 长命令本身就是失败信号
  - **中转是"复制"不是"搬移"**：`item replace … from entity …` 复制源槽位（不清空），
    所以整条链路玩家全程不会失去手里的刀；中转实体上留的那份副本写入成功后由
    `finishRelay()` 清除，**绝不放它掉到地上**（那会凭空多一把刀）
  - **抢救走纯 Bukkit**：刀若卡在中转实体里，用 `findRelay()` + `getEquipment()` 直接搬回主手，
    不复用命令 —— 否则抢救会依赖出问题的那条路径而一起静默失效；
    插件卸载时 `recoverRelayItems()` **先取物品再删实体**（`Entity#remove()` 不掉落装备）
  - **校验延后一拍**：原版反馈即时，但 Bukkit 侧物品镜像要等 tick 结束才回灌 →
    写入后先同步校验，不过则 `runTaskLater(…, 1L)` 在下一 tick 复验，结果通过回调返回
- **日志克制**：写入成功只留一行 `[写入成功] 玩家 杀敌数 0 → 5555`。
  `[CMD]` 命令回显默认关闭；打开也会把组件体折叠成 `[…N 字符…]`（898 → 88 字符）。
  完整命令用 `/sbkill debug` 拿。
- **失败会自己说话**：写入彻底失败时自动把本次实际派发的每条命令（折叠 + 字符数）打进控制台，
  不用提前开 `verbose-commands`；并且失败时会自动抢救卡在中转实体里的刀。
- **排障黑盒**：把 `capture-command-feedback` 改成 `true`，失败时会额外打印
  **服务端对每条命令的回话**（原版成功文案 / 原版报错 / 接管插件的 `§x…` 文案）。
  `dispatchCommand` 只把反馈发给发送者，插件原本看不到 —— 这是判断"命令为什么不生效"最直接的证据。
- **版本适配**：中转实体的主手 NBT 路径跨版本变过 —— `HandItems[0]`（≤1.21.4）/ `equipment.mainhand`（1.21.5+，
  官方把 `HandItems`/`ArmorItems` 合并进了 `equipment`）。插件两条都试、且每条都带存在性判断。

```log
# 一次成功的写入，控制台就这么两行
Replaced a slot on QZRS with [『Uesugi Erii?Sakura』]   # 原版反馈
[写入成功] QZRS 杀敌数 0 → 5555
```

## 构建

```bash
./gradlew build
# 产物：build/libs/SlashBladeKillEditor-1.0.3.jar
```

需要 JDK 21。依赖 `org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT`（compileOnly，不打包进 jar）。

## 命令

```
/sbkill get | set <n> | add <n> | reset | soul <n> | refine <n> | reload
/sbkill <玩家> <子命令>
```

完整原理、排障流程与失败通道对照表见仓库内 **[SlashBladeKillEditor教程.md](SlashBladeKillEditor教程.md)**。

## 更新日志

| 版本 | 变更 |
|------|------|
| **1.0.3** | **超长命令不再白跑**：新增 `relay-first-above-length`（默认 2000）—— 主写入命令超过阈值直接走中转盔甲架（3099 字符的重度改装刀就是这种情况：三条命令通道全挂）。**中转残留物处理修正**：`from entity` 是复制语义，中转实体上会留一份副本，此前"手上还有东西就不删实体"会把副本留在地上（凭空多一把刀）→ 写入成功后改为清除副本并删实体；卸载清理也从"一律掉落"改成"主手为空才归还，否则判定为重复副本清除，主人不在线才掉落"。**新增排障开关** `capture-command-feedback`：打开后写入失败会打印服务端对每条命令的回话 |
| 1.0.2 | **修两个真 bug**：① 中转兜底在 1.21.1 上永久静默失效（`equipment.mainhand` 是 1.21.5+ 才有的字段，1.21.1 应为 `HandItems[0]`；且 `data modify … set value` 会凭空补齐不存在的路径，"写错"伪装成"成功"）→ 改为两条路径都试 + 每条都带 `execute if data` 判断；② 刀卡在中转实体里搬不回来（搬回判断用了同一条错路径，恒为假）→ 两条路径各判断一次，抢救改走纯 Bukkit。另：失败时自动打印本次试过的命令；`/sbkill debug` 新增服务端版本、写入命令长度、中转实体现状 |
| 1.0.1 | 日志降噪：`verbose-commands` 默认 `false`；组件回显折叠（898 → 88 字符）；成功日志精简（首选通道不写通道名，仅降级标注） |
| 1.0.0 | 首个可用版本：组件读写 + `minecraft:item` 写入 + 延后一拍校验 + 中转盔甲架兜底 |

## 配置

| 键 | 默认 | 说明 |
|----|------|------|
| `ops-only` | `true` | 只有服务端 OP 能执行（关闭则改用 `sbkilleditor.*` 权限节点） |
| `limits.max-kill-count` 等 | `1000000` | 数值上限；`sbkilleditor.admin` 无视 |
| `allow-negative` | `false` | 是否允许负数 |
| `sync-inventory-after-write` | `true` | 写完强制刷新背包（让客户端 tooltip 立即更新） |
| `verbose-commands` | `false` | 把派发的原版命令回显到控制台（组件体会被折叠，不会刷屏） |
| `relay-first-above-length` | `2000` | 主写入命令超过这个字符数就直接走中转，不再试注定失败的两次派发；`0` = 关闭此优化 |
| `capture-command-feedback` | `false` | 排障用：捕获并打印服务端对每条命令的回话。需要临时包一层命令发送者代理，极少数做强转的插件可能受影响，故默认关闭 |

## 源码

| 文件 | 职责 |
|------|------|
| `SlashBladeKillEditor.java` | 主类，注册命令，启动时打印服务端版本、中转路径候选、命令长度阈值 |
| `PluginConfig.java` | config.yml 读取、消息占位符与颜色 |
| `BladeAccess.java` | 组件读写核心：`getAsComponentString` 解析 / `item`+`minecraft:item` 写入 / 写入方案选择 `planFor()` / 延后一拍校验 / 中转兜底（双装备路径 + `if data` 判断）/ 残留副本清理 / 纯 Bukkit 抢救 / 日志折叠 `compact()` |
| `FeedbackCapture.java` | 排障用命令反馈捕获器（动态代理包一层 `CommandSender`，抄 `sendMessage` 后照常转发） |
| `SbkillCommand.java` | 命令解析、权限、Tab 补全 |

## 自检

上服后 `手上拿刀` → `/sbkill get`，读到三个数值即成功。
失败时执行 `/sbkill debug`（仅 OP），把 `===== SlashBladeKillEditor 诊断 =====` 那一段日志发回。
