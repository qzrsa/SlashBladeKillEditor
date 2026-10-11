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
    /**
     * 是否把派发出去的原版命令回显到控制台（前缀 {@code [CMD]}）。
     *
     * <p>默认 <b>false</b>：写入成功时只留一条精简的 {@code [写入成功]}，控制台干净。
     * 打开后也不是全量打印 —— 组件 SNBT 会被折叠成 {@code […N 字符…]}，一行约 90 字符。
     * 排障时不必开这个，直接 {@code /sbkill debug} 就能拿到完整写入命令。
     */
    public boolean verboseCommands = false;
    /**
     * 主写入命令超过这个字符数就直接走中转盔甲架，不再先试两条命令通道。
     *
     * <p>经验值：898 / 976 / 958 字符的三把刀走命令通道**成功**；
     * 3099 字符（重度改装刀，带 apotheosis 镶嵌宝石与词缀）那条**三条通道全挂**。
     * 超长时先试命令只会白跑两次派发、白刷两行日志，所以直接绕过。
     *
     * <p>设为 <b>0</b> 可关闭此优化（永远先试命令通道）。
     */
    public int relayFirstAboveLength = 2000;
    /**
     * 是否捕获「服务端对每条派发命令的反馈」（默认 <b>false</b>）。
     *
     * <p>打开后，本插件派发命令时会把服务端回给发送者的话（原版 {@code Replaced a slot on …}
     * 也好、接管 {@code /item} 的第三方插件吐的 {@code §x…} 也好）抄一份下来，
     * 在写入失败时随命令清单一并打到控制台。<b>这是定位「命令为什么不生效」最直接的证据</b>，
     * 但需要临时包一层 {@code CommandSender} 代理，极少数做强转的插件可能受影响，故默认关闭。
     *
     * <p>排障流程：临时改成 true → {@code /sbkill reload} → 复现一次失败 → 把
     * {@code [写入失败]} 那一段发回（这次会带上 {@code ↳ 服务端反馈} 行）。
     */
    public boolean captureCommandFeedback = false;

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
        this.verboseCommands = conf.getBoolean("verbose-commands", false);
        this.relayFirstAboveLength = conf.getInt("relay-first-above-length", 2000);
        this.captureCommandFeedback = conf.getBoolean("capture-command-feedback", false);

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
