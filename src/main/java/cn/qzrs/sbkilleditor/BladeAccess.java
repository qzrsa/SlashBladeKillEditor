package cn.qzrs.sbkilleditor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拔刀剑刀刃数据的读写核心。
 *
 * <h2>读取：纯 Bukkit，零命令</h2>
 * {@code ItemMeta#getAsComponentString()} 返回**完整组件 SNBT**，连模组自定义组件都在里面：
 * <pre>
 * [slashblade:blade_state={..., killCount: 0, proudSoul: 0, refine: 0, ...},
 *  slashblade:blade_runtime_state={...}]
 * </pre>
 * 读取 = 反射调用该方法 + 解析 {@code slashblade:blade_state} 段里的字段，不碰任何命令。
 *
 * <h2>写入：一次一条命令，校验延后一拍</h2>
 * 方案按「{@code minecraft:item} → {@code item}（普通）→ 命令版中转盔甲架」依次尝试，
 * <b>每换一个方案前先等一拍复验</b>，因此正常情况下<strong>只发一条命令</strong>：
 * <ol>
 *   <li><b>{@code minecraft:item replace entity <玩家> weapon.mainhand with <物品>[<组件SNBT>]}</b>
 *       —— 加命名空间强制走原版，绕过服上插件对 {@code /item} 的接管。
 *       <b>本服实测的成功路径</b>（普通写法会被拦，回 {@code We can't give you this, its not an item}）。</li>
 *   <li><b>{@code item replace …}</b> —— 标准写法。仅当命名空间写法确实无效时才走到这里
 *       （例如极少数把 {@code minecraft:} 前缀命令禁掉的服）。</li>
 *   <li><b>命令版中转盔甲架</b> —— 前两条都不行才用。每玩家一个常驻隐形 Marker 盔甲架，
 *       <b>懒召唤</b>（{@code execute unless entity … run summon}），所以热路径完全不需要
 *       {@code /kill} —— 本服 {@code /kill} 被插件接管、裸 {@code kill @e[…] } 必失败。
 *       所有 {@code from entity} 搬回都先做 {@code if data} 存在性判断，
 *       避免把空手当物品搬、误清玩家主手。</li>
 * </ol>
 *
 * <h2>为什么校验要「延后一拍」</h2>
 * 本服实测：命令反馈是<strong>即时</strong>的（原版 {@code Replaced a slot on …} 立刻出现），
 * 但 {@code player.getInventory().getItemInMainHand()} 在<strong>同一 tick 内</strong>
 * 读到的仍是旧值（Bukkit 侧镜像要等 tick 结束才回灌）。
 * 于是「写完立刻回读」会误判失败 —— 这正是早期版本"命令成功却报写入失败"的根因。
 * 所以：先同步校验（常规服务端立刻命中、零延迟），不过则
 * {@code runTaskLater(…, 1L)} 在下一 tick 复验，通过回调把结果交回命令层。
 */
public final class BladeAccess {

    /** 需要操作的三个字段。 */
    public enum Field { KILL, SOUL, REFINE }

    /** 写入结果回调（可能在当前 tick 或下一 tick 被调用，均在服务端主线程）。 */
    public interface WriteCallback {
        /**
         * @param ok    是否确实写进去了（以回读为准）
         * @param stage 成功的通道名，或失败原因
         */
        void done(boolean ok, String stage);
    }

    /** 三个字段的一次性快照。 */
    public static final class Snapshot {
        public final Integer kill;
        public final Integer soul;
        public final Integer refine;

        Snapshot(Integer kill, Integer soul, Integer refine) {
            this.kill = kill;
            this.soul = soul;
            this.refine = refine;
        }
    }

    /** 组件 ID。 */
    private static final String COMP_BLADE_STATE = "slashblade:blade_state";
    /** 存放刀刃状态的容器路径（相对物品 NBT）。 */
    private static final String PATH_BLADE_STATE = "components.\"" + COMP_BLADE_STATE + "\"";

    private static final Map<Field, String> FIELD_NAME = new EnumMap<>(Field.class);

    static {
        FIELD_NAME.put(Field.KILL, "killCount");
        FIELD_NAME.put(Field.SOUL, "proudSoul");
        FIELD_NAME.put(Field.REFINE, "refine");
    }

    /** 中转实体标签前缀（后接玩家名，保证每个玩家一个常驻实体）。 */
    private static final String RELAY_TAG_PREFIX = "sbke_";

    private final SlashBladeKillEditor plugin;
    private final PluginConfig cfg;
    private final CommandSender consoleSender;

    private String lastDispatchError;
    private String lastError;

    public BladeAccess(SlashBladeKillEditor plugin, PluginConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.consoleSender = Bukkit.getConsoleSender();
    }

    // =====================================================================
    //  对外 API
    // =====================================================================

    public boolean hasItem(Player p) {
        return p.getInventory().getItemInMainHand().getType() != Material.AIR;
    }

    /** 主手是否拿着拔刀剑（以「组件里能读到 killCount」为准）。 */
    public boolean isHoldingBlade(Player p) {
        return get(p, Field.KILL) != null;
    }

    /** 一次性读出三个字段；返回 null 表示不是拔刀剑 / 读取失败。 */
    public Snapshot snapshot(Player p) {
        String comps = componentString(p);
        if (comps == null) {
            return null;
        }
        Integer k = parseField(comps, FIELD_NAME.get(Field.KILL));
        if (k == null) {
            return null;
        }
        return new Snapshot(k,
                parseField(comps, FIELD_NAME.get(Field.SOUL)),
                parseField(comps, FIELD_NAME.get(Field.REFINE)));
    }

    /** 读单个字段；null 表示该物品上没有这个字段。 */
    public Integer get(Player p, Field f) {
        String comps = componentString(p);
        return (comps == null) ? null : parseField(comps, FIELD_NAME.get(f));
    }

    /**
     * 请求写入并回读校验。按「minecraft:item → item（普通）→ 命令版中转盔甲架」
     * 依次尝试，**每换一个方案先等一拍复验**，因此正常情况下只发一条命令。
     * 结果通过 {@link WriteCallback} 返回（可能延迟 1~3 tick 才回调）。
     */
    public void requestWrite(Player p, Field f, int value, WriteCallback cb) {
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType() == Material.AIR) {
            lastError = "主手为空";
            cb.done(false, lastError);
            return;
        }

        String comps = componentString(it);
        if (comps == null) {
            lastError = "拿不到组件字符串（getAsComponentString 不可用）";
            cb.done(false, lastError);
            return;
        }

        String newComps = withField(comps, FIELD_NAME.get(f), value);
        if (newComps == null) {
            lastError = "组件 " + COMP_BLADE_STATE + " 里找不到字段 " + FIELD_NAME.get(f);
            cb.done(false, lastError);
            return;
        }

        String id = it.getType().getKey().toString();
        String tail = "item replace entity " + p.getName()
                + " weapon.mainhand with " + id + newComps;

        // 方案表：命令优先（命名空间写法最不容易被插件拦），最后是 null = 中转盔甲架
        String[] plan = {"minecraft:" + tail, tail, null};
        runPlan(p, f, value, plan, 0, cb);
    }

    /**
     * 顺序执行写入方案。每个方案先做一次同步校验（常规服务端立刻命中、零延迟），
     * 不过则等下一 tick 再复验（Bukkit 物品镜像晚一拍回灌），仍不过才换下一个方案。
     */
    private void runPlan(Player p, Field f, int value, String[] plan, int idx, WriteCallback cb) {
        if (idx >= plan.length) {
            rescueIfLost(p);
            cb.done(false, lastError == null ? "所有写入通道均失败" : lastError);
            return;
        }

        String cmd = plan[idx];
        if (cmd != null) {
            dispatch(consoleSender, cmd);
            if (verify(p, f, value)) {
                lastError = null;
                cb.done(true, channelLabel(cmd));
                return;
            }
        } else {
            plugin.getLogger().info("[写入] 命令写入未获确认，改用中转盔甲架…");
            relayWrite(p, f, value);
            if (verify(p, f, value)) {
                lastError = null;
                cb.done(true, "中转盔甲架");
                return;
            }
        }

        final int next = idx + 1;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (verify(p, f, value)) {
                lastError = null;
                cb.done(true, label(cmd) + " + 延后复验");
                return;
            }
            runPlan(p, f, value, plan, next, cb);
        }, 1L);
    }

    /** 方案表中某一步的可读名字。 */
    private static String label(String cmd) {
        return (cmd == null) ? "中转盔甲架" : channelLabel(cmd);
    }

    /** 把完整命令折算成短标签，避免日志被上千字符的组件 SNBT 刷屏。 */
    private static String channelLabel(String cmd) {
        if (cmd == null) {
            return "未知通道";
        }
        if (cmd.startsWith("minecraft:")) {
            return "minecraft:item（命名空间）";
        }
        if (cmd.startsWith("item ")) {
            return "item（普通写法）";
        }
        return cmd;
    }

    // =====================================================================
    //  通道 3：命令版中转盔甲架（每玩家一个常驻隐形 Marker，懒召唤）
    // =====================================================================

    private static String relayTag(Player p) {
        return RELAY_TAG_PREFIX + p.getName();
    }

    private static String relaySelector(Player p) {
        return "@e[type=armor_stand,tag=" + relayTag(p) + ",limit=1]";
    }

    /**
     * 全命令实现的中转链路。热路径<strong>不含 {@code /kill}</strong>：
     * 中转实体按需召唤、之后一直留着复用，插件卸载时统一清理。
     */
    private void relayWrite(Player p, Field f, int value) {
        String name = p.getName();
        String sel = relaySelector(p);
        String ctx = "execute at " + name + " run ";

        // ① 懒召唤：不存在才建（不用被接管的 /kill 做清理）
        dispatch(consoleSender, ctx + "execute unless entity " + sel + " run summon armor_stand ~ ~ ~ "
                + "{Tags:[\"" + relayTag(p) + "\"],Marker:1b,Invisible:1b,NoGravity:1b,"
                + "Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b}");

        // ② 把玩家手里的刀搬进中转
        dispatch(consoleSender, ctx + "item replace entity " + sel
                + " weapon.mainhand from entity " + name + " weapon.mainhand");

        // ③ 改数值
        dispatch(consoleSender, ctx + "data modify entity " + sel
                + " equipment.mainhand." + PATH_BLADE_STATE + "." + FIELD_NAME.get(f)
                + " set value " + value);

        // ④ 搬回玩家主手 —— 必须先确认中转手上真有东西，否则会把玩家主手清空
        moveBack(sel, name);
    }

    /** 从中转实体搬回玩家主手；带存在性判断，绝不用空手覆盖玩家主手。 */
    private void moveBack(String sel, String playerName) {
        dispatch(consoleSender, "execute at " + playerName + " run execute if data entity " + sel
                + " equipment.mainhand.count run item replace entity " + playerName
                + " weapon.mainhand from entity " + sel + " weapon.mainhand");
    }

    /**
     * 抢救：若玩家主手空了，说明第 ④ 步没成功、刀还留在中转实体里 —— 再搬一次。
     * 同样带存在性判断，所以即使主手只是「镜像显示为空」也不会误清。
     */
    private void rescueIfLost(Player p) {
        if (p.getInventory().getItemInMainHand().getType() != Material.AIR) {
            return;
        }
        String sel = relaySelector(p);
        plugin.getLogger().warning("[抢救] " + p.getName() + " 主手为空，尝试从中转实体把刀搬回…");
        moveBack(sel, p.getName());
        plugin.getLogger().warning("[抢救] 若仍未搬回，请手工执行："
                + " /execute at " + p.getName() + " run item replace entity " + p.getName()
                + " weapon.mainhand from entity " + sel + " weapon.mainhand");
    }

    // =====================================================================
    //  校验
    // =====================================================================

    /** 回读目标字段是否等于期望值（纯 Bukkit 读）。 */
    private boolean verify(Player p, Field f, int value) {
        Integer back = get(p, f);
        return back != null && back == value;
    }

    /** 修改后强制刷新玩家背包，让客户端 tooltip 立即更新。 */
    public void sync(Player p) {
        if (!cfg.syncInventory) {
            return;
        }
        try {
            p.updateInventory();
        } catch (Throwable ignored) {
            // 个别实现可能不提供，忽略即可
        }
    }

    /** 插件卸载时清理所有中转实体（纯 Bukkit，不用 /kill）。 */
    public void shutdown() {
        try {
            for (World w : Bukkit.getWorlds()) {
                for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                    for (String tag : as.getScoreboardTags()) {
                        if (tag.startsWith(RELAY_TAG_PREFIX)) {
                            as.remove();
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    // =====================================================================
    //  纯 Bukkit 读取
    // =====================================================================

    private String componentString(Player p) {
        return componentString(p.getInventory().getItemInMainHand());
    }

    /** 反射取组件 SNBT；优先 getAsComponentString（可直接用于 /item 命令）。 */
    private String componentString(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) {
            return null;
        }
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                return null;
            }
            for (String m : new String[]{"getAsComponentString", "getAsString"}) {
                try {
                    Object r = meta.getClass().getMethod(m).invoke(meta);
                    if (r instanceof String s && !s.isEmpty()) {
                        return s;
                    }
                } catch (Throwable ignored) {
                    // 该方法不存在或不可用，试下一个
                }
            }
        } catch (Throwable t) {
            lastDispatchError = "组件序列化失败: " + t;
        }
        return null;
    }

    /** 截出 slashblade:blade_state={ ... } 这一段（含花括号）。 */
    private static String bladeStateSegment(String comps) {
        if (comps == null) {
            return null;
        }
        int key = comps.indexOf(COMP_BLADE_STATE);
        if (key < 0) {
            return null;
        }
        int open = comps.indexOf('{', key);
        if (open < 0) {
            return null;
        }
        int close = matchBrace(comps, open);
        return (close < 0) ? null : comps.substring(open, close + 1);
    }

    private static Integer parseField(String comps, String field) {
        String seg = bladeStateSegment(comps);
        if (seg == null || field == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?<![\\w:])" + Pattern.quote(field) + ":\\s*(-?\\d+)").matcher(seg);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 返回把目标字段替换为 value 后的完整组件字符串；找不到字段返回 null。 */
    private static String withField(String comps, String field, int value) {
        if (comps == null || field == null) {
            return null;
        }
        int key = comps.indexOf(COMP_BLADE_STATE);
        if (key < 0) {
            return null;
        }
        int open = comps.indexOf('{', key);
        if (open < 0) {
            return null;
        }
        int end = matchBrace(comps, open);
        if (end < 0) {
            return null;
        }

        String seg = comps.substring(open, end + 1);
        String replaced = seg.replaceFirst(
                "(?<![\\w:])" + Pattern.quote(field) + ":\\s*-?\\d+",
                Matcher.quoteReplacement(field + ": " + value));
        if (replaced.equals(seg)) {
            return null; // 没找到该字段
        }
        return comps.substring(0, open) + replaced + comps.substring(end + 1);
    }

    /** 从 openIdx 处的 '{' 起做括号配平（跳过字符串与转义），返回配对 '}' 的下标。 */
    private static int matchBrace(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '{') {
            return -1;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // =====================================================================
    //  诊断
    // =====================================================================

    public List<String> debug(Player p) {
        List<String> out = new ArrayList<>();
        out.add("===== SlashBladeKillEditor 诊断 =====");
        out.add("opsOnly=" + cfg.opsOnly
                + "  写入方案顺序=minecraft:item → item → 中转盔甲架"
                + "  中转标签=" + relayTag(p));

        ItemStack main = p.getInventory().getItemInMainHand();
        out.add("主手: " + main.getType() + " x" + main.getAmount());
        out.add("副手: " + p.getInventory().getItemInOffHand().getType());
        out.add("物品命名空间ID: " + safeKey(main));

        String comps = componentString(main);
        out.add("组件(getAsComponentString): " + comps);
        out.add("组件(getAsString): " + rawString(main));

        if (comps == null) {
            out.add("!! 拿不到组件字符串，无法读写");
        } else {
            out.add("解析 killCount=" + parseField(comps, "killCount")
                    + "  proudSoul=" + parseField(comps, "proudSoul")
                    + "  refine=" + parseField(comps, "refine"));
            String preview = withField(comps, "killCount", 12345);
            if (preview == null) {
                out.add("写入命令预览: <字段替换失败>");
            } else {
                out.add("写入命令预览(普通): item replace entity " + p.getName()
                        + " weapon.mainhand with " + safeKey(main) + preview);
                out.add("写入命令预览(命名空间): minecraft:item replace entity " + p.getName()
                        + " weapon.mainhand with " + safeKey(main) + preview);
            }
        }

        if (lastError != null) {
            out.add("最近一次业务错误: " + lastError);
        }
        if (lastDispatchError != null) {
            out.add("最近一次派发异常: " + lastDispatchError);
        }
        return out;
    }

    private static String safeKey(ItemStack it) {
        try {
            return String.valueOf(it.getType().getKey());
        } catch (Throwable t) {
            return "<取不到: " + t + ">";
        }
    }

    private static String rawString(ItemStack it) {
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta == null) {
                return "<无 ItemMeta>";
            }
            Object r = meta.getClass().getMethod("getAsString").invoke(meta);
            return String.valueOf(r);
        } catch (Throwable t) {
            return "<不可用: " + t.getClass().getSimpleName() + ">";
        }
    }

    // =====================================================================
    //  命令派发
    // =====================================================================

    private void dispatch(CommandSender s, String commandLine) {
        if (s == null || commandLine == null) {
            return;
        }
        if (cfg.verboseCommands) {
            plugin.getLogger().info("[CMD] /" + brief(commandLine));
        }
        try {
            Bukkit.dispatchCommand(s, commandLine);
        } catch (Throwable t) {
            this.lastDispatchError = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().warning("[CMD] 派发失败 /" + brief(commandLine)
                    + " -> " + lastDispatchError);
        }
    }

    /** 日志用短命令：超长（组件 SNBT 动辄上千字符）时截断并标注总长度。 */
    private static String brief(String commandLine) {
        final int max = 220;
        if (commandLine.length() <= max) {
            return commandLine;
        }
        return commandLine.substring(0, max) + " …(共 " + commandLine.length() + " 字符)";
    }
}
