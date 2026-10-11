package cn.qzrs.sbkilleditor;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SlashBladeKillEditor 主类。
 *
 * <p>为 1.21.1 的 SlashBlade: Resharped（NeoForge，运行于 Arclight）提供
 * 手持拔刀剑的杀敌数 / 耀魂 / 锻造数在线编辑能力。
 */
public final class SlashBladeKillEditor extends JavaPlugin {

    private PluginConfig cfg;
    private BladeAccess access;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.cfg = new PluginConfig(this);
        this.access = new BladeAccess(this, cfg);

        SbkillCommand handler = new SbkillCommand(this, cfg, access);

        PluginCommand command = getCommand("sbkill");
        if (command == null) {
            getLogger().severe("plugin.yml 缺少 sbkill 命令定义，插件无法工作。");
            return;
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info("SlashBladeKillEditor 已启用 —— 目标组件路径 slashblade:blade_state");
        getLogger().info("  服务端: " + safeVersion()
                + " | 中转装备路径候选: " + String.join(" → ", BladeAccess.EQUIP_PATHS));
        getLogger().info("  [CMD] 命令回显: " + (cfg.verboseCommands ? "开" : "关（默认）")
                + " | 写入失败时会自动打印本次试过的命令，无需提前开启");
    }

    private static String safeVersion() {
        try {
            return Bukkit.getBukkitVersion();
        } catch (Throwable t) {
            return "<未知>";
        }
    }

    @Override
    public void onDisable() {
        if (access != null) {
            access.shutdown();
        }
    }

    /** /sbkill reload 调用。 */
    public void reloadAll() {
        reloadConfig();
        if (cfg != null) {
            cfg.load();
        }
    }
}
