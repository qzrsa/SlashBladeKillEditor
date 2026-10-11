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
  - **抢救走纯 Bukkit**：刀若卡在中转实体里，用 `findRelay()` + `getEquipment()` 直接搬回主手，
    不复用命令 —— 否则抢救会依赖出问题的那条路径而一起静默失效
  - **校验延后一拍**：原版反馈即时，但 Bukkit 侧物品镜像要等 tick 结束才回灌 →
    写入后先同步校验，不过则 `runTaskLater(…, 1L)` 在下一 tick 复验，结果通过回调返回
- **日志克制**：写入成功只留一行 `[写入成功] 玩家 杀敌数 0 → 5555`。
  `[CMD]` 命令回显默认关闭；打开也会把组件体折叠成 `[…N 字符…]`（898 → 88 字符）。
  完整命令用 `/sbkill debug` 拿。
- **失败会自己说话**：写入彻底失败时自动把本次实际派发的每条命令（折叠 + 字符数）打进控制台，
  不用提前开 `verbose-commands`；并且失败时会自动抢救卡在中转实体里的刀。
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
# 产物：build/libs/SlashBladeKillEditor-1.0.2.jar
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
| **1.0.2** | **修两个真 bug**：① 中转兜底在 1.21.1 上永久静默失效（`equipment.mainhand` 是 1.21.5+ 才有的字段，1.21.1 应为 `HandItems[0]`；且 `data modify … set value` 会凭空补齐不存在的路径，"写错"伪装成"成功"）→ 改为两条路径都试 + 每条都带 `execute if data` 判断；② 刀卡在中转实体里搬不回来（搬回判断用了同一条错路径，恒为假）→ 两条路径各判断一次，抢救改走纯 Bukkit。另：失败时自动打印本次试过的命令；`/sbkill debug` 新增服务端版本、写入命令长度、中转实体现状 |
| 1.0.1 | 日志降噪：`verbose-commands` 默认 `false`；组件回显折叠（898 → 88 字符）；成功日志精简（首选通道不写通道名，仅降级标注） |
| 1.0.0 | 首个可用版本：组件读写 + `minecraft:item` 写入 + 延后一拍校验 + 中转盔甲架兜底 |

## 源码

| 文件 | 职责 |
|------|------|
| `SlashBladeKillEditor.java` | 主类，注册命令 |
| `PluginConfig.java` | config.yml 读取、消息占位符与颜色 |
| `BladeAccess.java` | 组件读写核心：`getAsComponentString` 解析 / `item`+`minecraft:item` 写入 / 延后一拍校验 / 中转兜底（双装备路径 + `if data` 判断）/ 纯 Bukkit 抢救 / 日志折叠 `compact()` |
| `SlashBladeKillEditor.java` | 主类，注册命令，启动时打印服务端版本与中转路径候选 |
| `SbkillCommand.java` | 命令解析、权限、Tab 补全 |

## 自检

上服后 `手上拿刀` → `/sbkill get`，读到三个数值即成功。
失败时执行 `/sbkill debug`（仅 OP），把 `===== SlashBladeKillEditor 诊断 =====` 那一段日志发回。
