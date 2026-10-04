package com.hongshikaikai.aicraft.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 带看门狗的响应体读取器：把「读整个 body」这件事变成
 * <b>闲置超时 + 总时长超时</b> 两条独立红线。
 *
 * <p>对外两个入口，共用同一套看门狗：{@link #readAll} 一次读干净（合成 / 需要全文的场合），
 * {@link #readLines} 按行回调（建造：推理模型的思维链要实时转达给玩家，不必等全文）。</p>
 *
 * <h2>为什么不是 {@code orTimeout}</h2>
 * <p>旧实现是 {@code sendAsync(...).orTimeout(90 + 15)}——一个从发请求那一刻开始计的
 * 总时长哨兵。它有两个毛病：</p>
 * <ol>
 *   <li><b>惩罚正常的慢流</b>：免费车道实测只有 ~1KB/s，8192 token 的建造请求
 *       160 秒都吐不完，可它一直在正常出数据，不该被判死；</li>
 *   <li><b>报错看不懂</b>：{@code orTimeout} 抛的是 {@code TimeoutException}，
 *       它 <b>message 为 null</b>，日志里只会留下「AI 请求第 1 次失败（null）」。</li>
 * </ol>
 *
 * <p>现在的判定是：只要 <b>还在持续收到数据</b> 就让它继续读（闲置红线），
 * 同时保留一条绝对上限兜底（总时长红线）。两条红线都抛
 * {@link AiTimeoutException}，消息里写清楚超了哪条、超了多久。</p>
 *
 * <h2>怎么打断一个阻塞中的 read</h2>
 * <p>看门狗线程关闭 {@link InputStream}：{@code java.net.http} 的响应流被 close 时
 * 会让阻塞中的 {@code read} 立刻抛 {@code IOException}（实测 10ms 内返回），
 * 顺带取消这次 HTTP exchange、释放连接。因此不需要额外的读线程或轮询。</p>
 *
 * <p>本类不依赖 Bukkit，只依赖 {@link AiException} / {@link AiTimeoutException}，
 * 方便单元测试用假的慢流直接验证。</p>
 */
final class BodyReader {

    /** 单次读取的字节缓冲。 */
    private static final int BUFFER_SIZE = 8192;

    /** 看门狗检查间隔。越小越准时，越大越省 CPU；200ms 对秒级超时足够。 */
    private static final long WATCHDOG_TICK_MILLIS = 200L;

    /** 兜底下限：配置写成 0 或负数时至少给 1 秒，避免请求一发出去就被判超时。 */
    private static final long MIN_TIMEOUT_MILLIS = 1000L;

    /**
     * 共享的看门狗线程池。
     *
     * <p>单线程、守护线程：插件卸载/服务端退出时不会拦住 JVM；一次请求最多占用它几毫秒。</p>
     */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AICraft-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private BodyReader() {
    }

    /**
     * 读干净整个响应体，途中按两条红线判超时。
     *
     * @param in                 响应体流（{@code BodyHandlers.ofInputStream()} 拿到的那个）
     * @param idleTimeoutMillis  闲置红线：连续这么久没有收到任何新字节就判超时
     * @param totalTimeoutMillis 总时长红线：整次读取的绝对上限
     * @return 响应体全文（UTF-8）
     * @throws AiTimeoutException 撞到闲置或总时长红线
     * @throws IOException        流本身出错（连接被重置等）
     */
    static String readAll(InputStream in, long idleTimeoutMillis, long totalTimeoutMillis) throws IOException {
        long idleLimit = Math.max(MIN_TIMEOUT_MILLIS, idleTimeoutMillis);
        long totalLimit = Math.max(MIN_TIMEOUT_MILLIS, totalTimeoutMillis);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalLimit);
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        AtomicReference<String> abortReason = new AtomicReference<>();

        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            if (now >= deadline) {
                abort(abortReason, in, "总时长超过 " + (totalLimit / 1000L) + " 秒");
                return;
            }
            long idleMillis = TimeUnit.NANOSECONDS.toMillis(now - lastProgress.get());
            if (idleMillis >= idleLimit) {
                abort(abortReason, in, "已 " + Math.round(idleMillis / 1000.0D) + " 秒没有收到新数据");
            }
        }, WATCHDOG_TICK_MILLIS, WATCHDOG_TICK_MILLIS, TimeUnit.MILLISECONDS);

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[BUFFER_SIZE];
            int read;
            // 读干净才返回：SSE 的正文是最后一次读之后才能解析的，这里不做提前退出。
            // 中途被看门狗 close 掉时，read 会抛 IOException，由下面的 catch 翻译成超时。
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                lastProgress.set(System.nanoTime());
            }
            return buffer.toString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            String reason = abortReason.get();
            if (reason != null) {
                throw new AiTimeoutException("AI 响应超时：" + reason, ex);
            }
            throw ex;
        } finally {
            watchdog.cancel(false);
        }
    }

    /**
     * 按行流式读取响应体：每凑齐一整行就交给 {@code onLine}，不再等整个 body 读完。
     *
     * <p>这是给推理模型用的：免费车道的模型会先吐几分钟思维链（SSE 里的
     * {@code delta.reasoning}），调用方拿它可以把「AI 正在想什么」实时转达给玩家，
     * 而不是让玩家对着「AI 正在设计建筑…」干等三五分钟。</p>
     *
     * <p>两条红线与 {@link #readAll} 完全一致；区别只是不再在内存里攒全文。
     * 回调在读取线程上执行，<b>必须尽量轻</b>（解析一行 SSE 可以，访问 Bukkit 不行）。</p>
     *
     * @param in                 响应体流
     * @param idleTimeoutMillis  闲置红线：连续这么久没有收到任何新字节就判超时
     * @param totalTimeoutMillis 总时长红线：整次读取的绝对上限
     * @param onLine             每读到一整行（已去掉行尾换行）就回调一次
     * @throws AiTimeoutException 撞到闲置或总时长红线
     * @throws IOException        流本身出错（连接被重置等）
     */
    static void readLines(InputStream in, long idleTimeoutMillis, long totalTimeoutMillis,
                          Consumer<String> onLine) throws IOException {
        long idleLimit = Math.max(MIN_TIMEOUT_MILLIS, idleTimeoutMillis);
        long totalLimit = Math.max(MIN_TIMEOUT_MILLIS, totalTimeoutMillis);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalLimit);
        AtomicLong lastProgress = new AtomicLong(System.nanoTime());
        AtomicReference<String> abortReason = new AtomicReference<>();

        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            if (now >= deadline) {
                abort(abortReason, in, "总时长超过 " + (totalLimit / 1000L) + " 秒");
                return;
            }
            long idleMillis = TimeUnit.NANOSECONDS.toMillis(now - lastProgress.get());
            if (idleMillis >= idleLimit) {
                abort(abortReason, in, "已 " + Math.round(idleMillis / 1000.0D) + " 秒没有收到新数据");
            }
        }, WATCHDOG_TICK_MILLIS, WATCHDOG_TICK_MILLIS, TimeUnit.MILLISECONDS);

        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                lastProgress.set(System.nanoTime());
                int start = 0;
                for (int i = 0; i < read; i++) {
                    if (chunk[i] != '\n') {
                        continue;
                    }
                    pending.write(chunk, start, i - start);
                    start = i + 1;
                    onLine.accept(stripCr(pending.toString(StandardCharsets.UTF_8)));
                    pending.reset();
                }
                if (start < read) {
                    pending.write(chunk, start, read - start);
                }
            }
            // 流结束但没有换行符收尾：最后一段残缺内容也算一行
            if (pending.size() > 0) {
                onLine.accept(stripCr(pending.toString(StandardCharsets.UTF_8)));
            }
        } catch (IOException ex) {
            String reason = abortReason.get();
            if (reason != null) {
                throw new AiTimeoutException("AI 响应超时：" + reason, ex);
            }
            throw ex;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * 记录超时原因（只记第一次）并关掉流，让阻塞中的 read 立刻返回。
     *
     * @param reason  超时原因，只在第一次写入
     * @param in      响应体流
     * @param message 人类可读的原因描述
     */
    private static void abort(AtomicReference<String> reason, InputStream in, String message) {
        if (!reason.compareAndSet(null, message)) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 关不掉也不影响超时判定：read 返回后照样按 abortReason 抛 AiTimeoutException
        }
    }
}
