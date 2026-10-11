package cn.qzrs.sbkilleditor;

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
