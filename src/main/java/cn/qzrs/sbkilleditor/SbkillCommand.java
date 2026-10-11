package cn.qzrs.sbkilleditor;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /sbkill 命令实现。
 *
 * <pre>
 *   /sbkill get                 查看手持刀数据
 *   /sbkill set &lt;n&gt;             设置杀敌数
 *   /sbkill add &lt;n&gt;             增加/减少杀敌数
 *   /sbkill reset               杀敌数归零
 *   /sbkill soul &lt;n&gt;            设置耀魂      （需 admin）
 *   /sbkill refine &lt;n&gt;          设置锻造数    （需 admin）
 *   /sbkill reload              重载配置      （需 admin）
 *   /sbkill &lt;玩家&gt; ...          代其他玩家操作（需 others）
 * </pre>
 *
 * <h2>权限模型</h2>
 * 默认 {@code ops-only: true}：<b>只有服务端 OP 能执行</b>，非 OP 既执行不了、
 * Tab 补全里也看不到本命令。若改为 {@code false}，则改用权限节点
 * {@code sbkilleditor.use / others / admin} 控制（全部 default: op）。
 */
public final class SbkillCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS =
            List.of("get", "set", "add", "reset", "soul", "refine", "reload", "debug", "help");

    private final SlashBladeKillEditor plugin;
    private final PluginConfig cfg;
    private final BladeAccess access;

    public SbkillCommand(SlashBladeKillEditor plugin, PluginConfig cfg, BladeAccess access) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.access = access;
    }

    // =====================================================================
    //  权限门禁（所有入口统一走这里）
    // =====================================================================

    /**
     * 是否允许该发送者使用本插件。
     *
     * <ul>
     *   <li>{@code ops-only: true}（默认）→ 必须是服务端 OP；控制台视为 OP。</li>
     *   <li>{@code ops-only: false} → 看 {@code sbkilleditor.use} 权限节点。</li>
     * </ul>
     */
    private boolean allowed(CommandSender sender) {
        if (cfg.opsOnly && !sender.isOp()) {
            return false;
        }
        return sender.hasPermission("sbkilleditor.use");
    }

    // =====================================================================
    //  命令入口
    // =====================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 统一门禁：非 OP（或无权限节点）一律拒绝，连用法都不给
        if (!allowed(sender)) {
            sender.sendMessage(cfg.msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(cfg.msg("usage"));
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        Player target;
        String sub;
        String[] rest;

        if (SUBS.contains(first)) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(cfg.msg("need-player"));
                return true;
            }
            target = self;
            sub = first;
            rest = Arrays.copyOfRange(args, 1, args.length);
        } else {
            // 形如  /sbkill <玩家> <子命令> ...
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(cfg.msg("player-offline", "player", args[0]));
                return true;
            }
            if (!sender.hasPermission("sbkilleditor.others")) {
                sender.sendMessage(cfg.msg("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(cfg.msg("usage"));
                return true;
            }
            sub = args[1].toLowerCase(Locale.ROOT);
            rest = Arrays.copyOfRange(args, 2, args.length);
        }

        switch (sub) {
            case "help" -> sender.sendMessage(cfg.msg("usage"));

            case "debug" -> doDebug(sender, target);

            case "reload" -> {
                if (!sender.hasPermission("sbkilleditor.admin")) {
                    sender.sendMessage(cfg.msg("no-permission"));
                    return true;
                }
                plugin.reloadAll();
                sender.sendMessage(cfg.msg("reloaded"));
            }

            case "get" -> doGet(sender, target);

            case "set" -> doSetKill(sender, target, rest, false);

            case "add" -> doSetKill(sender, target, rest, true);

            case "reset" -> doSetKill(sender, target, new String[]{"0"}, false);

            case "soul" -> doSetField(sender, target, rest, BladeAccess.Field.SOUL);

            case "refine" -> doSetField(sender, target, rest, BladeAccess.Field.REFINE);

            default -> sender.sendMessage(cfg.msg("usage"));
        }
        return true;
    }

    // =====================================================================
    //  子命令实现
    // =====================================================================

    private void doGet(CommandSender sender, Player target) {
        BladeAccess.Snapshot snap = access.snapshot(target);
        if (snap == null || snap.kill == null) {
            tellNotHolding(sender, target);
            return;
        }
        sender.sendMessage(cfg.msg("get-result",
                "player", target.getName(),
                "kill", String.valueOf(snap.kill),
                "soul", String.valueOf(snap.soul),
                "refine", String.valueOf(snap.refine)));
    }

    /**
     * set / add 杀敌数。
     *
     * @param relative true = add（在当前值基础上叠加）
     */
    private void doSetKill(CommandSender sender, Player target, String[] rest, boolean relative) {
        if (rest.length < 1) {
            sender.sendMessage(cfg.msg("usage"));
            return;
        }
        int input = parseInt(sender, rest[0]);
        if (input == Integer.MIN_VALUE) {
            return; // parseInt 已发消息
        }

        Integer current = access.get(target, BladeAccess.Field.KILL);
        if (current == null) {
            tellNotHolding(sender, target);
            return;
        }

        int value = relative ? (int) ((long) current + input) : input;

        if (!checkRange(sender, value, cfg.maxKillCount)) {
            return;
        }

        final int old = current;
        // 写入结果可能延迟 1 tick 才确认（Arclight 的 Bukkit 物品镜像晚一拍回灌）
        access.requestWrite(target, BladeAccess.Field.KILL, value, (ok, stage) -> {
            if (!ok) {
                sender.sendMessage(cfg.msg("write-failed", "reason", stage));
                plugin.getLogger().warning("[写入失败] " + target.getName()
                        + " 杀敌数=" + value + " -> " + stage);
                return;
            }
            plugin.getLogger().info("[写入成功] " + target.getName()
                    + " 杀敌数 " + old + " → " + value + channelSuffix(stage));
            access.sync(target);
            if (relative) {
                sender.sendMessage(cfg.msg("kill-added",
                        "player", target.getName(),
                        "old", String.valueOf(old),
                        "new", String.valueOf(value)));
            } else {
                sender.sendMessage(cfg.msg("kill-set",
                        "player", target.getName(),
                        "value", String.valueOf(value)));
            }
        });
    }

    private void doSetField(CommandSender sender, Player target, String[] rest, BladeAccess.Field field) {
        if (!sender.hasPermission("sbkilleditor.admin")) {
            sender.sendMessage(cfg.msg("no-permission"));
            return;
        }
        if (rest.length < 1) {
            sender.sendMessage(cfg.msg("usage"));
            return;
        }
        int value = parseInt(sender, rest[0]);
        if (value == Integer.MIN_VALUE) {
            return;
        }

        int max = (field == BladeAccess.Field.SOUL) ? cfg.maxProudSoul : cfg.maxRefine;
        if (!checkRange(sender, value, max)) {
            return;
        }

        if (!access.isHoldingBlade(target)) {
            tellNotHolding(sender, target);
            return;
        }

        final int v = value;
        access.requestWrite(target, field, v, (ok, stage) -> {
            if (!ok) {
                sender.sendMessage(cfg.msg("write-failed", "reason", stage));
                plugin.getLogger().warning("[写入失败] " + target.getName()
                        + " " + fieldName(field) + "=" + v + " -> " + stage);
                return;
            }
            plugin.getLogger().info("[写入成功] " + target.getName()
                    + " " + fieldName(field) + " = " + v + channelSuffix(stage));
            access.sync(target);
            sender.sendMessage(cfg.msg(field == BladeAccess.Field.SOUL ? "soul-set" : "refine-set",
                    "player", target.getName(),
                    "value", String.valueOf(v)));
        });
    }

    // =====================================================================
    //  日志工具
    // =====================================================================

    /** 字段的中文名，仅用于控制台日志。 */
    private static String fieldName(BladeAccess.Field f) {
        return switch (f) {
            case KILL -> "杀敌数";
            case SOUL -> "耀魂";
            case REFINE -> "锻造数";
        };
    }

    /**
     * 成功日志里要不要带通道名。
     * 命中首选通道（正常情况）返回空串，其余情况才标出通道 —— 让日常日志尽量短。
     * 注意中转有两种：{@code 中转盔甲架（长命令捷径）} 是**主动**选的，
     * 裸 {@code 中转盔甲架} 才是前两条通道都失败后的兜底。
     */
    private static String channelSuffix(String stage) {
        return BladeAccess.PRIMARY_CHANNEL.equals(stage)
                ? "" : "（通道：" + stage + "）";
    }

    // =====================================================================
    //  工具
    // =====================================================================

    /** 解析整数；失败返回 Integer.MIN_VALUE 并已向 sender 发消息。 */
    private int parseInt(CommandSender sender, String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            sender.sendMessage(cfg.msg("invalid-number", "input", s));
            return Integer.MIN_VALUE;
        }
    }

    /** 上限 / 负值校验。 */
    private boolean checkRange(CommandSender sender, int value, int max) {
        boolean admin = sender.hasPermission("sbkilleditor.admin");
        if (!admin && value > max) {
            sender.sendMessage(cfg.msg("too-large",
                    "value", String.valueOf(value), "max", String.valueOf(max)));
            return false;
        }
        if (value < 0 && !cfg.allowNegative && !admin) {
            sender.sendMessage(cfg.msg("invalid-number", "input", String.valueOf(value)));
            return false;
        }
        return true;
    }

    /** 手持物不是拔刀剑时的统一反馈（附带诊断入口提示）。 */
    private void tellNotHolding(CommandSender sender, Player target) {
        sender.sendMessage(cfg.msg("not-holding", "player", target.getName()));
        if (sender.hasPermission("sbkilleditor.admin")) {
            sender.sendMessage(PluginConfig.color(
                    "&7诊断：执行 &f/sbkill debug &7输出详细探测信息（聊天栏 + 控制台日志）"));
        }
    }

    /**
     * /sbkill debug —— 输出诊断信息，定位「找不到组件」的真实原因。
     * 全量写入服务端日志，聊天栏只给摘要。
     */
    private void doDebug(CommandSender sender, Player target) {
        if (!sender.hasPermission("sbkilleditor.admin")) {
            sender.sendMessage(cfg.msg("no-permission"));
            return;
        }

        List<String> lines = access.debug(target);

        plugin.getLogger().info("========== /sbkill debug by " + sender.getName()
                + " target=" + target.getName() + " ==========");
        for (String line : lines) {
            plugin.getLogger().info("  " + line);
        }
        plugin.getLogger().info("========== /sbkill debug end ==========");

        sender.sendMessage(PluginConfig.color("&8[&c拔刀剑&8] &f诊断结果（完整内容已写入控制台日志）："));
        for (String line : lines) {
            String shown = line.length() > 500 ? line.substring(0, 500) + " ...(已截断)" : line;
            sender.sendMessage(PluginConfig.color("&7" + shown));
        }
        sender.sendMessage(PluginConfig.color(
                "&7请把服务端日志里 &f/sbkill debug&7 之间的内容发回，用于定位。"));
    }

    // =====================================================================
    //  Tab 补全
    // =====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // 无权限者不给任何补全（连命令名都看不到）
        if (!allowed(sender)) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            if (sender.hasPermission("sbkilleditor.others")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(p.getName());
                    }
                }
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("set") || sub.equals("add") || sub.equals("soul") || sub.equals("refine")) {
                out.add("<数量>");
            } else {
                for (String s : SUBS) {
                    if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(s);
                    }
                }
            }
        } else if (args.length == 3) {
            out.add("<数量>");
        }
        return out;
    }
}
