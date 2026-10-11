package cn.qzrs.sbkilleditor;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置读取与消息格式化。
 *
 * <p>消息模板存放在 config.yml 的 messages 段，支持 {@code {key}} 占位符与 {@code &} 颜色代码。
 */
public final class PluginConfig {

    private final JavaPlugin plugin;
    private FileConfiguration conf;

    // ---- limits ----
    public int maxKillCount = 1_000_000;
    public int maxProudSoul = 1_000_000;
    public int maxRefine = 1_000_000;
    public boolean allowNegative = false;

    // ---- behaviour ----
    /** 仅允许服务端 OP 执行（默认严格模式）。 */
    public boolean opsOnly = true;
    public boolean syncInventory = true;
    /** 把每条派发出去的原版命令打到控制台，便于排错（默认开）。 */
    public boolean verboseCommands = true;

    private String prefix = "&8[&c拔刀剑&8] &r";

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** 重新读取配置（不会重新 saveDefaultConfig）。 */
    public void load() {
        plugin.reloadConfig();
        this.conf = plugin.getConfig();

        this.maxKillCount = conf.getInt("limits.max-kill-count", 1_000_000);
        this.maxProudSoul = conf.getInt("limits.max-proud-soul", 1_000_000);
        this.maxRefine = conf.getInt("limits.max-refine", 1_000_000);
        this.allowNegative = conf.getBoolean("limits.allow-negative", false);

        this.opsOnly = conf.getBoolean("ops-only", true);
        this.syncInventory = conf.getBoolean("sync-inventory-after-write", true);
        this.verboseCommands = conf.getBoolean("verbose-commands", true);

        this.prefix = conf.getString("messages.prefix", this.prefix);
    }

    /**
     * 取一条已着色的完整消息（含前缀）。
     *
     * @param key 消息键（不含 messages. 前缀）
     * @param kv  占位符键值对，例如 "player", "Steve", "value", "500"
     */
    public String msg(String key, String... kv) {
        String raw = conf.getString("messages." + key, "&c[缺失消息: " + key + "]");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return color(prefix + raw);
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
