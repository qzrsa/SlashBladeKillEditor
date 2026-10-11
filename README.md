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
    热路径不用 `/kill`；搬回前带 `execute if data entity … equipment.mainhand.count` 判断，**绝不误清主手**
  - **校验延后一拍**：原版反馈即时，但 Bukkit 侧物品镜像要等 tick 结束才回灌 →
    写入后先同步校验，不过则 `runTaskLater(…, 1L)` 在下一 tick 复验，结果通过回调返回

## 构建

```bash
./gradlew build
# 产物：build/libs/SlashBladeKillEditor-1.0.0.jar
```

需要 JDK 21。依赖 `org.spigotmc:spigot-api:1.21.1-R0.1-SNAPSHOT`（compileOnly，不打包进 jar）。

## 命令

```
/sbkill get | set <n> | add <n> | reset | soul <n> | refine <n> | reload
/sbkill <玩家> <子命令>
```

详见仓库外的 `SlashBladeKillEditor教程.md`。

## 源码

| 文件 | 职责 |
|------|------|
| `SlashBladeKillEditor.java` | 主类，注册命令 |
| `PluginConfig.java` | config.yml 读取、消息占位符与颜色 |
| `BladeAccess.java` | 组件读写核心：`getAsComponentString` 解析 / `item`+`minecraft:item` 写入 / 延后一拍校验 / 命令版中转兜底 |
| `SbkillCommand.java` | 命令解析、权限、Tab 补全 |

## 自检

上服后 `手上拿刀` → `/sbkill get`，读到三个数值即成功。
失败时执行 `/sbkill debug`（仅 OP），把 `===== SlashBladeKillEditor 诊断 =====` 那一段日志发回。
