package com.hongshikaikai.aicraft.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BodyReader} 的行为测试。
 *
 * <p>覆盖三件事：正常流读干净、卡死的流按闲置红线判死、慢但活着的流不被误杀
 * （最后一条就是这次线上事故的回归测试：8192 token 的建造流会连续几分钟慢慢吐字）。</p>
 */
class BodyReaderTest {

    private static final String SSE_BODY = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
            + "data: [DONE]\n\n";

    /** 正常流：一次读完，返回值与输入逐字节一致。 */
    @Test
    void readsWholeBodyWhenStreamIsHealthy() throws IOException {
        InputStream in = new ByteArrayInputStream(SSE_BODY.getBytes(StandardCharsets.UTF_8));
        assertEquals(SSE_BODY, BodyReader.readAll(in, 5_000L, 10_000L));
    }

    /** 慢但一直在出数据：不该被杀 —— 这是旧 {@code orTimeout} 犯的错。 */
    @Test
    void keepsSlowButAliveStreamAlive() throws IOException {
        byte[] payload = "data: 慢流\n\n".getBytes(StandardCharsets.UTF_8);
        // 每 200ms 一个字节，空闲红线 1 秒、总时长 10 秒：永远不该撞闲置红线
        InputStream in = new DripStream(payload, 200L, false);
        assertEquals(new String(payload, StandardCharsets.UTF_8), BodyReader.readAll(in, 1_000L, 10_000L));
    }

    /** 卡死的流：撞闲置红线，异常消息写清楚原因，且必须很快返回。 */
    @Test
    void abortsWhenStreamStalls() {
        StallingStream in = new StallingStream("data: {\"choices\":[");
        long start = System.nanoTime();
        AiTimeoutException ex = assertThrows(AiTimeoutException.class,
                () -> BodyReader.readAll(in, 1_000L, 60_000L));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(ex.getMessage().contains("没有收到新数据"), ex.getMessage());
        assertTrue(elapsedMillis < 5_000L, "闲置超时应秒级返回，实际 " + elapsedMillis + "ms");
        assertTrue(in.isClosed(), "看门狗应该把流关掉，好让 blocked read 立刻返回");
    }

    /** 总时长红线：即使一直在出数据，超过绝对上限也要收手。 */
    @Test
    void abortsWhenTotalBudgetExceeded() {
        // 每 100ms 一个字节、永不结束：闲置红线给 60 秒（不会触发），只有总时长红线能收手
        DripStream in = new DripStream(new byte[] {'x'}, 100L, true);
        long start = System.nanoTime();
        AiTimeoutException ex = assertThrows(AiTimeoutException.class,
                () -> BodyReader.readAll(in, 60_000L, 1_000L));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(ex.getMessage().contains("总时长"), ex.getMessage());
        assertTrue(elapsedMillis < 5_000L, "总时长超时应秒级返回，实际 " + elapsedMillis + "ms");
    }

    /** 超时是 AiException 的子类，调用方原有的兜底逻辑仍然接得住。 */
    @Test
    void timeoutExceptionIsAnAiException() {
        assertTrue(new AiTimeoutException("x", null) instanceof AiException);
    }

    /** 流式读取：每凑齐一整行就回调一次，末尾没有换行的残行也要交出去。 */
    @Test
    void readLinesCallbacksPerLine() throws IOException {
        InputStream in = new ByteArrayInputStream(
                "data: 第一行\n\ndata: 第二行\r\ndata: 没有换行的结尾".getBytes(StandardCharsets.UTF_8));
        java.util.List<String> lines = new java.util.ArrayList<>();
        BodyReader.readLines(in, 5_000L, 10_000L, lines::add);
        assertEquals(java.util.List.of("data: 第一行", "", "data: 第二行", "data: 没有换行的结尾"), lines);
    }

    /** 流式读取同样受闲置红线约束：卡住就抛超时，不会挂死。 */
    @Test
    void readLinesAlsoHonoursIdleWatchdog() {
        StallingStream in = new StallingStream("data: {\"choices\":");
        assertThrows(AiTimeoutException.class,
                () -> BodyReader.readLines(in, 1_000L, 60_000L, line -> {
                    // 什么都不做
                }));
    }

    /** 发完就卡住（不 close 就永远不返回）的流，模拟「响应头来了但正文不再推进」。 */
    private static final class StallingStream extends InputStream {

        private final byte[] head;
        private final byte[] single = new byte[1];
        private int position;
        private boolean closed;

        private StallingStream(String head) {
            this.head = head.getBytes(StandardCharsets.UTF_8);
        }

        synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            if (position < head.length) {
                int count = Math.min(length, head.length - position);
                System.arraycopy(head, position, buffer, offset, count);
                position += count;
                return count;
            }
            while (!closed) {
                try {
                    wait(50L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ex);
                }
            }
            throw new IOException("closed");
        }

        @Override
        public int read() throws IOException {
            int count = read(single, 0, 1);
            return count == -1 ? -1 : single[0] & 0xff;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }

    /** 每 {@code intervalMillis} 吐一个字节；{@code endless} 为 true 时循环播放、永不 EOF。 */
    private static final class DripStream extends InputStream {

        private final byte[] payload;
        private final long intervalMillis;
        private final boolean endless;
        private final byte[] single = new byte[1];
        private int position;
        private boolean closed;

        private DripStream(byte[] payload, long intervalMillis, boolean endless) {
            this.payload = payload;
            this.intervalMillis = intervalMillis;
            this.endless = endless;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position >= payload.length) {
                if (!endless) {
                    return -1;
                }
                position = 0;
            }
            if (intervalMillis > 0) {
                try {
                    wait(intervalMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ex);
                }
            }
            if (closed) {
                throw new IOException("closed");
            }
            buffer[offset] = payload[position++];
            return 1;
        }

        @Override
        public int read() throws IOException {
            int count = read(single, 0, 1);
            return count == -1 ? -1 : single[0] & 0xff;
        }

        @Override
        public synchronized void close() {
            closed = true;
            notifyAll();
        }
    }
}
