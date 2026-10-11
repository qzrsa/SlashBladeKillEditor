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
import java.util.Collections;
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
 *       避免把空手当物品搬、误清玩家主手。<br>
 *       <b>注意语义</b>：{@code from entity} 是<b>复制</b>（源槽位不清空），所以整条链路玩家
 *       全程不会失去手里的刀，代价是中转实体上会留一份副本 —— 写入成功后由
 *       {@link #finishRelay} 清除，绝不放它掉到地上（那会凭空多一把刀）。</li>
 * </ol>
 *
 * <p>另外：<b>主命令超过 {@code relay-first-above-length}（默认 2000）字符时直接跳到最后一条</b>，
 * 不再先试两条注定失败的长命令通道。实测 898/976/958 字符的刀走命令通道成功，
 * 而 3099 字符的重度改装刀（带镶嵌宝石与词缀）三条通道全挂 —— 长命令本身就是失败信号。
 *
 * <h2>为什么校验要「延后一拍」</h2>
 * 本服实测：命令反馈是<strong>即时</strong>的（原版 {@code Replaced a slot on …} 立刻出现），
 * 但 {@code player.getInventory().getItemInMainHand()} 在<strong>同一 tick 内</strong>
 * 读到的仍是旧值（Bukkit 侧镜像要等 tick 结束才回灌）。
 * 于是「写完立刻回读」会误判失败 —— 这正是早期版本"命令成功却报写入失败"的根因。
 * 所以：先同步校验（常规服务端立刻命中、零延迟），不过则
 * {@code runTaskLater(…, 1L)} 在下一 tick 复验，通过回调把结果交回命令层。
 *
 * <h2>日志策略：成功时一条就够</h2>
 * 写入成功在控制台只留一条 {@code [写入成功] 玩家 杀敌数 0 → 5555}（首选通道命中时不写通道名，
 * 只有降级才标注）。{@code [CMD]} 命令回显默认<strong>关闭</strong>；
 * 打开时也走 {@link #compact} <b>折叠</b>组件体（898 字符 → 88 字符），不会刷屏。
 * 需要完整命令请用 {@code /sbkill debug}。
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

    /**
     * 中转实体「主手」的 NBT 路径候选。<b>顺序很重要</b>，且这条路径跨版本变过：
     *
     * <ul>
     *   <li>{@code HandItems[0]} —— <b>1.21.4 及以前</b>（含本插件目标 1.21.1）。</li>
     *   <li>{@code equipment.mainhand} —— <b>1.21.5 起</b>：官方把
     *       {@code ArmorItems}/{@code HandItems}/{@code body_armor_item} 合并成了 {@code equipment}
     *       字段（见 Minecraft Wiki: Java 版 1.21.5）。</li>
     * </ul>
     *
     * <p><b>⚠️ 写错路径不会报错，只会静默失效</b>：{@code data modify ... set value} 会自动
     * 补齐不存在的中间复合标签，于是命令照样回 {@code Modified entity data}，
     * 但实体上真正的东西一点没动，还凭空多出一个垃圾键。
     * 历史 bug 就是这里 —— 本插件曾用 {@code equipment.mainhand}，在 1.21.1 上永久静默失效。
     * 所以：<b>每条 {@code data modify} 前面都必须加 {@code execute if data} 存在性判断</b>，
     * 让「路径不对」变成「什么都不做」而不是「假装成功」。
     */
    static final String[] EQUIP_PATHS = {"HandItems[0]", "equipment.mainhand"};

    /**
     * 首选写入通道的名字。命中它 = 一切正常，日志可以最精简
     * （命令层据此决定 {@code [写入成功]} 里要不要标出通道）。
     */
    public static final String PRIMARY_CHANNEL = "minecraft:item";

    /** 中转盔甲架通道的日志标签（前两条命令通道都失败时的兜底）。 */
    public static final String RELAY_LABEL = "中转盔甲架";

    /**
     * 「长命令捷径」的日志标签：主命令超过 {@code relay-first-above-length} 时**主动**选择中转，
     * 不是兜底降级，所以日志上要能区分开。
     */
    public static final String RELAY_SHORTCUT_LABEL = "中转盔甲架（长命令捷径）";

    /**
     * 日志回显单行上限。<b>只是个兜底</b>：折叠组件体后行长度约 90 字符，
     * 中转用的 {@code summon} / 搬回命令约 230 字符 —— 两者都远在阈值内，会完整打印。
     * 只有出现意料之外的长命令时才截断。
     */
    private static final int MAX_ECHO = 400;

    private final SlashBladeKillEditor plugin;
    private final PluginConfig cfg;
    /** 真正用于派发命令的发送者：默认是控制台；开了反馈捕获则是它的代理。 */
    private final CommandSender dispatchSender;
    /** 反馈捕获器；未开启时为 {@code null}。 */
    private final FeedbackCapture capture;

    private String lastDispatchError;
    private String lastError;

    /**
     * 本次写入请求实际派发过的命令（含服务端对该命令的反馈，若开了捕获）。
     * 成功时不留痕，失败时整体打出来复盘。
     */
    private final List<Attempt> attemptLog = new ArrayList<>();

    /** 一条派发记录：命令全文 + 服务端对它的反馈行。 */
    private static final class Attempt {
        final String command;
        final List<String> feedback;

        Attempt(String command, List<String> feedback) {
            this.command = command;
            this.feedback = feedback;
        }
    }

    public BladeAccess(SlashBladeKillEditor plugin, PluginConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        CommandSender console = Bukkit.getConsoleSender();
        if (cfg.captureCommandFeedback) {
            this.capture = new FeedbackCapture();
            this.dispatchSender = capture.wrap(console);
        } else {
            this.capture = null;
            this.dispatchSender = console;
        }
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
        String primary = "minecraft:" + tail;

        // 超长命令直接走中转，省掉两次注定失败的派发（也少刷两行日志）。
        // 经验值见 config.yml 的 relay-first-above-length。
        String[] plan = planFor(primary, cfg.relayFirstAboveLength);
        if (plan.length == 1) {
            plugin.getLogger().info("[写入] 主命令 " + primary.length() + " 字符 > 阈值 "
                    + cfg.relayFirstAboveLength + "（relay-first-above-length），直接走中转盔甲架。");
        }
        attemptLog.clear();
        runPlan(p, f, value, plan, 0, cb);
    }

    /**
     * 决定本次写入的方案表。<b>纯函数，便于离线测试</b>。
     *
     * @param primary       首选命令（带 {@code minecraft:} 命名空间的完整写入命令）
     * @param threshold     主命令长度阈值；{@code <= 0} 表示不启用该优化
     * @return 方案表：正常为 {@code [primary, 普通写法, null(中转)]}；
     *         主命令超长时为 {@code [null]} —— 直接走中转，不做注定失败的尝试。
     */
    static String[] planFor(String primary, int threshold) {
        if (threshold > 0 && primary != null && primary.length() > threshold) {
            return new String[]{null};
        }
        String tail = (primary != null && primary.startsWith("minecraft:"))
                ? primary.substring("minecraft:".length())
                : primary;
        return new String[]{primary, tail, null};
    }

    /**
     * 顺序执行写入方案。每个方案先做一次同步校验（常规服务端立刻命中、零延迟），
     * 不过则等下一 tick 再复验（Bukkit 物品镜像晚一拍回灌），仍不过才换下一个方案。
     */
    private void runPlan(Player p, Field f, int value, String[] plan, int idx, WriteCallback cb) {
        if (idx >= plan.length) {
            rescueIfLost(p);
            dumpAttempts(p, f, value, plan);
            cb.done(false, lastError == null ? "所有写入通道均失败" : lastError);
            return;
        }

        String cmd = plan[idx];
        final boolean shortcut = (cmd == null && plan.length == 1);
        if (cmd != null) {
            dispatch(cmd);
            if (verify(p, f, value)) {
                lastError = null;
                cb.done(true, channelLabel(cmd));
                return;
            }
        } else {
            plugin.getLogger().info(shortcut
                    ? "[写入] 走中转盔甲架（长命令捷径）…"
                    : "[写入] 两条命令通道均未生效，改用中转盔甲架…");
            relayWrite(p, f, value);
            if (verify(p, f, value)) {
                lastError = null;
                finishRelay(p);
                cb.done(true, shortcut ? RELAY_SHORTCUT_LABEL : RELAY_LABEL);
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
                if (cmd == null) {
                    finishRelay(p);
                }
                cb.done(true, (cmd == null)
                        ? (shortcut ? RELAY_SHORTCUT_LABEL : RELAY_LABEL)
                        : channelLabel(cmd));
                return;
            }
            runPlan(p, f, value, plan, next, cb);
        }, 1L);
    }

    /** 把完整命令折算成短标签，避免日志被上千字符的组件 SNBT 刷屏。 */
    private static String channelLabel(String cmd) {
        if (cmd == null) {
            return RELAY_LABEL;
        }
        if (cmd.startsWith("minecraft:")) {
            return PRIMARY_CHANNEL;
        }
        if (cmd.startsWith("item ")) {
            return "item";
        }
        return "未知通道";
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
     * 中转实体按需召唤、之后一直留着复用，写入成功后立即清理。
     *
     * <p>关键语义（决定这段代码正确性）：{@code item replace … from entity …} 是
     * <b>复制</b>，不是搬移 —— 源槽位不清空。所以②之后玩家手里仍然有刀（安全），
     * ④把改好的副本覆盖回主手，而中转实体上那份副本由 {@link #finishRelay} 清除。
     */
    private void relayWrite(Player p, Field f, int value) {
        String name = p.getName();
        String sel = relaySelector(p);
        String ctx = "execute at " + name + " run ";

        // ① 懒召唤：不存在才建（不用被接管的 /kill 做清理）
        dispatch(ctx + "execute unless entity " + sel + " run summon armor_stand ~ ~ ~ "
                + "{Tags:[\"" + relayTag(p) + "\"],Marker:1b,Invisible:1b,NoGravity:1b,"
                + "Silent:1b,Invulnerable:1b,PersistenceRequired:1b,ShowArms:1b}");

        // ② 把玩家手里的刀**复制**进中转（源槽位不清空 → 玩家全程不会失去刀）
        dispatch(ctx + "item replace entity " + sel
                + " weapon.mainhand from entity " + name + " weapon.mainhand");

        // ③ 改数值。逐条候选路径都试，但**每条都必须先 if data 判断字段真实存在** ——
        //    否则 data modify ... set value 会把不存在的路径凭空造出来，命令回「成功」而实际没改。
        for (String equip : EQUIP_PATHS) {
            dispatch(buildModifyCmd(ctx, sel, equip, f, value));
        }

        // ④ 把改好的副本复制回玩家主手
        moveBack(sel, name);
    }

    /**
     * 构造「把中转实体主手上的刀刃字段改成 value」的命令。
     *
     * <p>关键的 {@code execute if data entity <中转> <路径>} 前置判断：
     * 路径不存在时整条命令<b>什么都不做</b>，而不是让 {@code set value} 把
     * 不存在的路径凭空造出来（那样命令会回「成功」却静默无效 —— 本插件踩过的坑）。
     */
    static String buildModifyCmd(String ctx, String sel, String equip, Field f, int value) {
        String fieldPath = equip + "." + PATH_BLADE_STATE + "." + FIELD_NAME.get(f);
        return ctx + "execute if data entity " + sel + " " + fieldPath
                + " run data modify entity " + sel + " " + fieldPath + " set value " + value;
    }

    /** 构造「把中转实体主手的东西搬回玩家主手」的命令（同样带 {@code if data} 前置判断）。 */
    static String buildMoveBackCmd(String playerName, String sel, String equip) {
        return "execute at " + playerName + " run execute if data entity " + sel
                + " " + equip + ".count run item replace entity " + playerName
                + " weapon.mainhand from entity " + sel + " weapon.mainhand";
    }

    /**
     * 从中转实体搬回玩家主手。<b>两条装备路径都试</b>，各自带 {@code if data} 存在性判断：
     * 判断成立说明那条路径上真有东西，才搬 —— 既不会用空手覆盖玩家主手，
     * 也不会因为猜错路径把刀永久留在中转实体里。
     */
    private void moveBack(String sel, String playerName) {
        for (String equip : EQUIP_PATHS) {
            dispatch(buildMoveBackCmd(playerName, sel, equip));
        }
    }

    /** 用纯 Bukkit 找出该玩家的中转实体（不执行任何命令）。 */
    private ArmorStand findRelay(Player p) {
        String tag = relayTag(p);
        try {
            for (World w : Bukkit.getWorlds()) {
                for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                    if (as.getScoreboardTags().contains(tag)) {
                        return as;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略
        }
        return null;
    }

    /**
     * 把中转实体主手的东西取出来（取出后它手上就空了）；没有东西返回 {@code null}。
     *
     * <p>先读、再清空，然后才把物品交出去 —— 顺序不能反，否则清空失败就会**凭空多出一份**。
     */
    private static ItemStack takeFromRelay(ArmorStand as) {
        try {
            if (as.getEquipment() == null) {
                return null;
            }
            ItemStack held = as.getEquipment().getItemInMainHand();
            if (held == null || held.getType() == Material.AIR) {
                return null;
            }
            as.getEquipment().setItemInMainHand(null);
            return held;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 成功写入后收尾：删掉中转实体，免得场上留一堆隐形盔甲架。
     *
     * <p><b>为什么残留物要清掉而不是留着/掉落</b>：{@code item replace <目标> <槽位> from entity …}
     * 的语义是<strong>复制</strong>源槽位（Minecraft Wiki 命令 {@code /item}：source 参数
     * "Specifies an entity to <b>copy</b> items from"），源槽位<strong>不会</strong>被清空。
     * 所以走完「复制进去 → 改数值 → 复制回来」之后，中转实体手上必然还留着那份（已改好的）
     * 副本 —— 那是重复件，落到地上就是凭空多一把刀。写入既然已经回读校验通过，
     * 此刻它只能是重复件，直接随实体一起清除。
     */
    private void finishRelay(Player p) {
        ArmorStand relay = findRelay(p);
        if (relay == null) {
            return;
        }
        ItemStack residue = takeFromRelay(relay);
        if (residue != null) {
            plugin.getLogger().info("[清理] 中转实体手上残留「" + residue.getType() + " x"
                    + residue.getAmount() + "」，写入已确认成功，故判定为重复副本并清除。");
        }
        relay.remove();
    }

    /**
     * 抢救：玩家主手空了，说明刀还卡在中转实体里。
     *
     * <p>这里<strong>故意不走命令</strong>，而是用纯 Bukkit 读中转实体、直接搬回 ——
     * 上一版的教训：抢救本身也用同一条（写错的）NBT 路径做判断，于是判断恒为假、
     * 抢救静默失效，刀就永久留在中转实体里了。纯 Bukkit 不依赖任何路径猜测。
     * 搬回顺序为「先清空中转、再放进主手」，中间不会出现两份。
     */
    private void rescueIfLost(Player p) {
        if (p.getInventory().getItemInMainHand().getType() != Material.AIR) {
            return;
        }
        ArmorStand relay = findRelay(p);
        if (relay == null) {
            plugin.getLogger().warning("[抢救] " + p.getName()
                    + " 主手为空，但没找到中转实体 —— 刀应该没丢。");
            return;
        }
        ItemStack held = takeFromRelay(relay);
        if (held == null) {
            plugin.getLogger().warning("[抢救] " + p.getName()
                    + " 主手为空，中转实体手上也是空的 —— 没有可抢救的物品。");
            return;
        }

        plugin.getLogger().warning("[抢救] 检测到「" + held.getType() + " x" + held.getAmount()
                + "」还卡在中转实体 " + relayTag(p) + " 里，用纯 Bukkit 直接搬回…");
        try {
            p.getInventory().setItemInMainHand(held);
            p.updateInventory();
            plugin.getLogger().warning("[抢救] 已搬回主手。");
        } catch (Throwable t) {
            // 搬回失败就把东西放回中转实体，绝不凭空吞掉
            try {
                relay.getEquipment().setItemInMainHand(held);
            } catch (Throwable ignored) {
                // 忽略
            }
            plugin.getLogger().severe("[抢救] 搬回失败（" + t + "），物品已退回中转实体，未丢失。");
        }
    }

    /**
     * 卸载前收尾：把所有中转实体手上还留着的物品**先取出来**，再删实体。
     *
     * <p><b>顺序至关重要</b>：{@code Entity#remove()} <b>不会掉落装备</b>，
     * 直接删一个还装着刀的中转实体 = 把刀销毁。所以必须先取物品
     * （在线玩家优先放回主手，否则原地掉落），最后才删实体。
     */
    private void recoverRelayItems() {
        for (World w : Bukkit.getWorlds()) {
            for (ArmorStand as : w.getEntitiesByClass(ArmorStand.class)) {
                String owner = null;
                for (String tag : as.getScoreboardTags()) {
                    if (tag.startsWith(RELAY_TAG_PREFIX)) {
                        owner = tag.substring(RELAY_TAG_PREFIX.length());
                        break;
                    }
                }
                if (owner == null) {
                    continue;
                }
                try {
                    ItemStack held = takeFromRelay(as);
                    if (held != null) {
                        Player p = Bukkit.getPlayerExact(owner);
                        if (p != null && p.isOnline()
                                && p.getInventory().getItemInMainHand().getType() == Material.AIR) {
                            // 主手空的 = 真正需要归还（刀确实被搬出去了这种情况）
                            p.getInventory().setItemInMainHand(held);
                            p.updateInventory();
                            plugin.getLogger().warning("[清理] 中转实体里的「" + held.getType()
                                    + "」已还给 " + owner + "。");
                        } else if (p != null && p.isOnline()) {
                            // 主手有东西 = 刀已经回来了，中转里的是复制语义留下的重复副本。
                            // 掉落会凭空多一把刀，所以清除。
                            plugin.getLogger().warning("[清理] 中转实体里残留「" + held.getType()
                                    + " x" + held.getAmount() + "」，而 " + owner
                                    + " 主手已有物品 —— 判定为重复副本，已清除（未掉落）。");
                        } else {
                            // 主人不在线，无法判断，宁可掉在地上也不销毁
                            w.dropItemNaturally(as.getLocation(), held);
                            plugin.getLogger().warning("[清理] 中转实体里的「" + held.getType()
                                    + "」已掉落在 " + w.getName() + " "
                                    + as.getLocation().getBlockX() + ","
                                    + as.getLocation().getBlockY() + ","
                                    + as.getLocation().getBlockZ() + "（" + owner + " 不在线）。");
                        }
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning("[清理] 处理中转实体时出错：" + t);
                }
                as.remove();
            }
        }
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

    /**
     * 插件卸载时清理中转实体（纯 Bukkit，不用 {@code /kill}）。
     *
     * <p>先 {@link #recoverRelayItems()} 把还留在实体手里的物品取出来，再删实体 ——
     * {@code remove()} 不掉落装备，反序会把卡住的刀一起销毁。
     */
    public void shutdown() {
        try {
            recoverRelayItems();
        } catch (Throwable t) {
            plugin.getLogger().warning("[清理] 中转实体清理失败：" + t);
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
        return matchPair(s, openIdx, '{', '}');
    }

    /**
     * 通用括号配平：从 {@code openIdx} 处的 {@code open} 起扫，返回配对闭括号的下标；
     * 找不到返回 -1。<b>会跳过字符串字面量与其内的转义</b>，
     * 所以 SNBT 里的 {@code "texture": "slashblade:model/{wood}.png"} 不会误计数。
     */
    private static int matchPair(String s, int openIdx, char open, char close) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != open) {
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
            } else if (c == open) {
                depth++;
            } else if (c == close) {
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
        out.add("[CMD]回显=" + cfg.verboseCommands + "（false 时不会在控制台刷命令）"
                + "  反馈捕获=" + cfg.captureCommandFeedback
                + "（true 时写入失败会附带服务端对每条命令的回话）");

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
                String plain = "item replace entity " + p.getName()
                        + " weapon.mainhand with " + safeKey(main) + preview;
                String ns = "minecraft:" + plain;
                out.add("写入命令预览(普通): " + plain);
                out.add("写入命令预览(命名空间): " + ns);
                out.add("写入命令长度: 普通 " + plain.length() + " 字符 / 命名空间 "
                        + ns.length() + " 字符");
                if (cfg.relayFirstAboveLength > 0 && ns.length() > cfg.relayFirstAboveLength) {
                    out.add("  ↑ 已超过 relay-first-above-length=" + cfg.relayFirstAboveLength
                            + " —— 本次写入会直接走中转盔甲架，不再派发这条长命令。");
                }
            }
        }

        // 服务端版本 —— 决定中转实体该用哪条装备路径（1.21.5 起 HandItems → equipment）
        out.add("服务端版本: " + safeBukkitVersion());
        out.add("中转装备路径候选: " + String.join(" / ", EQUIP_PATHS)
                + "（按序尝试，写错路径只会静默失效，所以每条都带 if data 判断）");

        // 中转实体现状（纯 Bukkit 读）—— 一眼看出刀有没有卡在里面
        ArmorStand relay = findRelay(p);
        if (relay == null) {
            out.add("中转实体(" + relayTag(p) + "): 不存在");
        } else {
            ItemStack rh = (relay.getEquipment() == null)
                    ? new ItemStack(Material.AIR)
                    : relay.getEquipment().getItemInMainHand();
            out.add("中转实体(" + relayTag(p) + "): 存在，世界=" + relay.getWorld().getName()
                    + "，主手=" + rh.getType() + " x" + rh.getAmount()
                    + (rh.getType() == Material.AIR ? "（空）" : "  ← 玩家手上若没刀，刀就在这里"));
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

    /** 形如 {@code 1.21.1-R0.1-SNAPSHOT}；取不到不影响使用。 */
    private static String safeBukkitVersion() {
        try {
            return Bukkit.getBukkitVersion();
        } catch (Throwable t) {
            return "<取不到>";
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

    /**
     * 派发一条原版命令。
     *
     * <p>日志策略：默认<strong>不回显</strong>（{@code verbose-commands: false}）——
     * 写入成功只需一条 {@code [写入成功]}。即便打开回显，也是走 {@link #compact} 的
     * <b>折叠</b>版本而非全量，所以控制台不会被上千字符的组件 SNBT 淹没。
     * 需要完整命令时用 {@code /sbkill debug}。
     *
     * <p>失败时另有一条兜底：本次写入若最终全军覆没，{@link #dumpAttempts} 会把
     * 试过的命令一次性打出来，<b>不用提前开 verbose 也能复盘</b>。
     *
     * <p>若开了 {@code capture-command-feedback}，每条命令派发前后会清空/取走反馈缓冲区，
     * 于是能把它和服务端对它的回话一一对上（失败时打印）。
     */
    private void dispatch(String commandLine) {
        if (commandLine == null) {
            return;
        }
        if (capture != null) {
            capture.clear();
        }
        if (cfg.verboseCommands) {
            plugin.getLogger().info("[CMD] /" + compact(commandLine));
        }
        try {
            // dispatchCommand 返回 false = 服务端压根没认这条命令（另一种失败，和"执行了但没生效"不同）
            boolean handled = Bukkit.dispatchCommand(dispatchSender, commandLine);
            if (!handled) {
                this.lastDispatchError = "服务端未识别该命令（dispatchCommand 返回 false）";
                plugin.getLogger().warning("[CMD] 未被识别 /" + compact(commandLine));
            }
        } catch (Throwable t) {
            this.lastDispatchError = t.getClass().getSimpleName() + ": " + t.getMessage();
            plugin.getLogger().warning("[CMD] 派发失败 /" + compact(commandLine)
                    + " -> " + lastDispatchError);
        }
        attemptLog.add(new Attempt(commandLine,
                (capture == null) ? Collections.emptyList() : capture.drain()));
    }

    /** 写入彻底失败时，把本次试过的命令一次性打到控制台，便于直接拷给作者复盘。 */
    private void dumpAttempts(Player p, Field f, int value, String[] plan) {
        plugin.getLogger().warning("[写入失败] " + p.getName() + " " + FIELD_NAME.get(f) + "=" + value
                + " —— 所有写入通道均未生效。本次实际派发了 " + attemptLog.size() + " 条命令：");
        int n = 1;
        for (Attempt a : attemptLog) {
            plugin.getLogger().warning("  (" + n++ + ") [" + a.command.length() + " 字符] /"
                    + compact(a.command));
            for (String fb : a.feedback) {
                plugin.getLogger().warning("      ↳ 服务端反馈: " + fb);
            }
        }
        if (capture == null) {
            plugin.getLogger().warning("[写入失败] 想看服务端对每条命令的回话？"
                    + "把 config.yml 的 capture-command-feedback 改成 true，"
                    + "/sbkill reload 后复现一次即可（会多出「↳ 服务端反馈」行）。");
        }
        if (plan.length > 0 && plan[0] != null) {
            plugin.getLogger().warning("[写入失败] 主命令完整长度 " + plan[0].length()
                    + " 字符；完整内容请执行 /sbkill debug 查看。");
        } else {
            plugin.getLogger().warning("[写入失败] 本次主命令超长，已跳过长命令通道直接走中转（见 relay-first-above-length）。");
        }
        plugin.getLogger().warning("[写入失败] 请把上面这段（连同此前后各几行控制台输出）发回给插件作者。");
    }

    /**
     * 日志用的命令回显：把组件体折叠掉，只留可读骨架。
     *
     * <pre>
     * 折叠前：minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[minecraft:enchantments={…},slashblade:blade_state={…}]   ← 898 字符
     * 折叠后：minecraft:item replace entity QZRS weapon.mainhand with slashblade:slashblade[…743 字符…]                                        ←  88 字符
     * </pre>
     *
     * <p>只折叠 {@code with <物品ID>[ … ]} 里那对<b>最外层方括号</b>，其余命令
     * （{@code summon} / {@code data modify} / 中转搬运用命令）原样保留。
     */
    private static String compact(String cmd) {
        int open = componentBracket(cmd);
        if (open < 0) {
            return brief(cmd, MAX_ECHO);
        }
        int close = matchPair(cmd, open, '[', ']');
        if (close < 0) {
            return brief(cmd, MAX_ECHO);
        }
        int payload = close - open - 1;
        return brief(cmd.substring(0, open) + "[…" + payload + " 字符…]" + cmd.substring(close + 1),
                MAX_ECHO);
    }

    /** 定位 {@code with <物品ID>[ … ]} 里那个 '['；不是带组件体的 item 命令则返回 -1。 */
    private static int componentBracket(String cmd) {
        int w = cmd.indexOf(" with ");
        return (w < 0) ? -1 : cmd.indexOf('[', w + " with ".length());
    }

    /** 日志用短命令：超长时截断并标注总长度。 */
    private static String brief(String commandLine, int max) {
        if (commandLine.length() <= max) {
            return commandLine;
        }
        return commandLine.substring(0, max) + " …(共 " + commandLine.length() + " 字符)";
    }
}
