package cn.qzrs.sbkilleditor;

import org.bukkit.command.CommandSender;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令反馈捕获器：把 {@code Bukkit.dispatchCommand} 回给发送者的消息<strong>抄一份</strong>。
 *
 * <h2>为什么需要它</h2>
 * 「命令派发出去了，但数据没变」这件事，唯一的直接证据是<strong>服务端自己回的那句话</strong>：
 * <ul>
 *   <li>原版成功 → {@code Replaced a slot on QZRS with […]}</li>
 *   <li>原版解析失败 → {@code Unknown or incomplete command} / 参数不匹配之类</li>
 *   <li>被第三方插件接管 → 带 {@code §x} 前缀的自定义文案（本服 {@code /item}、{@code /kill} 都被接管过）</li>
 * </ul>
 * 这些行只发给「命令发送者」。插件用控制台发送者派发时，它们直接进了控制台日志，
 * 插件侧拿不到 —— 这就是「写入为什么没生效」一直差最后一块证据的原因。
 *
 * <h2>怎么截</h2>
 * 用 {@link Proxy} 动态代理包一层 {@code CommandSender}：<b>只截 {@code sendMessage}</b>，
 * 其余方法原样转发给真正的控制台发送者，因此权限、{@code isOp}、{@code getServer} 等行为不变。
 *
 * <h2>为什么默认关闭</h2>
 * 唯一的行为差异：某插件若对发送者做 {@code instanceof ConsoleCommandSender} 或
 * {@code (Player) sender} 强转，收到代理对象会 {@code ClassCastException}。
 * 所以本功能由 {@code capture-command-feedback} 控制，<b>默认 false</b>，
 * 只在需要定位「命令为什么不生效」时打开。
 */
final class FeedbackCapture {

    /** 单批最多留几条反馈（超出丢最早的）。 */
    private static final int LIMIT = 24;
    /** 单条反馈最长留多少字符（超出截断）。 */
    private static final int MAX_LEN = 300;

    private final List<String> buffer = new ArrayList<>();

    /** 包装真实发送者；返回的代理会把消息抄进本对象，同时仍转发给真实发送者。 */
    CommandSender wrap(final CommandSender target) {
        if (target == null) {
            return null;
        }
        return (CommandSender) Proxy.newProxyInstance(
                FeedbackCapture.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        // 抄一份（只认 String / String[] 两个重载，其余重载在 capture 内部被忽略），
                        // 并且**照常转发**给真身 —— 不能把调用吞掉。
                        if (isSendMessage(method) && args != null && args.length > 0) {
                            capture(args[0]);
                        }
                        try {
                            return method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            // 还原真实异常，别让代理把插件内部异常包成 UndeclaredThrowable
                            throw e.getCause() == null ? e : e.getCause();
                        }
                    }
                });
    }

    private static boolean isSendMessage(Method m) {
        return "sendMessage".equals(m.getName()) && m.getParameterCount() >= 1;
    }

    private void capture(Object first) {
        if (first instanceof String s) {
            add(s);
        } else if (first instanceof String[] arr) {
            for (String s : arr) {
                add(s);
            }
        }
        // 其余重载（sendMessage(UUID, String) 等）首参不是 String，自然被忽略
    }

    private void add(String s) {
        if (s == null) {
            return;
        }
        String line = (s.length() > MAX_LEN) ? s.substring(0, MAX_LEN) + " …" : s;
        buffer.add(line);
        while (buffer.size() > LIMIT) {
            buffer.remove(0);
        }
    }

    /** 取走自上次 {@link #drain()} 之后收到的全部反馈，并清空。 */
    List<String> drain() {
        if (buffer.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<>(buffer);
        buffer.clear();
        return copy;
    }

    void clear() {
        buffer.clear();
    }
}
