package com.hongshikaikai.aicraft.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式思维链转达（{@link AiStreamHook} 在 SSE 解析层的行为）测试。
 *
 * <p>不依赖 Bukkit、也不起 HTTP 服务：直接构造 SSE 行喂给
 * {@link AiClient#feedStream}，验证「哪些帧会转达、哪些会被安静跳过」。</p>
 */
class AiStreamTest {

    /** 记录收到的事件，用来断言顺序与内容。 */
    private static final class Recorder implements AiStreamHook {

        private final StringBuilder reasoning = new StringBuilder();
        private final List<String> events = new ArrayList<>();
        private int doneCount;

        @Override
        public void onReasoning(String text) {
            reasoning.append(text);
            events.add("reasoning");
        }

        @Override
        public void onThinkingDone() {
            doneCount++;
            events.add("done");
        }
    }

    private static String reasoningFrame(String text) {
        return "data: {\"choices\":[{\"delta\":{\"reasoning\":\"" + text + "\"}}]}";
    }

    private static String contentFrame(String text) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    /** 思维链逐帧转达，出现正文时「思考结束」只通知一次，正文增量被攒下来。 */
    @Test
    void relaysReasoningThenSignalsThinkingDoneOnce() {
        Recorder recorder = new Recorder();
        AtomicBoolean done = new AtomicBoolean();
        StringBuilder content = new StringBuilder();

        AiClient.feedStream(recorder, done, reasoningFrame("先是一个椭圆看台"), content);
        AiClient.feedStream(recorder, done, reasoningFrame("，中间铺沙地"), content);
        AiClient.feedStream(recorder, done, contentFrame("fill ~-14 ~0 ~-11"), content);
        AiClient.feedStream(recorder, done, contentFrame(" ~14 ~0 ~11 stone_bricks"), content);

        assertEquals("先是一个椭圆看台，中间铺沙地", recorder.reasoning.toString());
        assertEquals(List.of("reasoning", "reasoning", "done"), recorder.events);
        assertEquals(1, recorder.doneCount);
        assertTrue(done.get());
        assertEquals("fill ~-14 ~0 ~-11 ~14 ~0 ~11 stone_bricks", content.toString());
    }

    /** 结束帧、空行、非 data 行、半个 JSON、错误信封都不能打断解析。 */
    @Test
    void ignoresNoiseWithoutThrowing() {
        Recorder recorder = new Recorder();
        AtomicBoolean done = new AtomicBoolean();
        StringBuilder content = new StringBuilder();

        AiClient.feedStream(recorder, done, "", content);
        AiClient.feedStream(recorder, done, ": keep-alive", content);
        AiClient.feedStream(recorder, done, "event: message", content);
        AiClient.feedStream(recorder, done, "data: [DONE]", content);
        AiClient.feedStream(recorder, done, "data: {\"choices\":[{\"delta\":", content);
        AiClient.feedStream(recorder, done, "data: {\"error\":{\"type\":\"FreeTierError\"}}", content);

        assertTrue(recorder.events.isEmpty(), "不该转达任何内容：" + recorder.events);
        assertFalse(done.get());
        assertTrue(content.isEmpty());
    }

    /** 有的模型把推演直接写在正文里：正文一出现同样要通知「思考结束」。 */
    @Test
    void signalsDoneWhenModelWritesReasoningIntoContent() {
        Recorder recorder = new Recorder();
        AtomicBoolean done = new AtomicBoolean();
        StringBuilder content = new StringBuilder();

        AiClient.feedStream(recorder, done, contentFrame("让我想想……"), content);

        assertEquals(List.of("done"), recorder.events);
        assertTrue(recorder.reasoning.isEmpty());
        assertEquals("让我想想……", content.toString());
    }

    /** 结束时没有正文（只思考、被截断）：请求收尾时也要补一次「思考结束」。 */
    @Test
    void collectingHookStillDeliversReasoningWhenStreamEndsEarly() {
        List<String> collected = new ArrayList<>();
        AiStreamHook hook = AiStreamHook.collecting(collected::add);

        hook.onReasoning("预算全花在思考上了");
        hook.onThinkingDone();

        assertEquals(List.of("预算全花在思考上了"), collected);
    }

    /** 没有思维链时不该给玩家发一条空的思考记录。 */
    @Test
    void collectingHookStaysQuietWithoutReasoning() {
        List<String> collected = new ArrayList<>();
        AiStreamHook hook = AiStreamHook.collecting(collected::add);

        hook.onThinkingDone();
        hook.onThinkingDone();

        assertTrue(collected.isEmpty());
    }

    /**
     * 流式路径与不流式路径必须解析出同一份正文 ——
     * {@code readStreaming} 用「攒下来的正文」重建信封再交给同一个
     * {@link AiClient#extractContent}，这里模拟那条重建路径。
     */
    @Test
    void streamedContentMatchesNonStreamingExtraction() {
        String body = reasoningFrame("想一下")
                + "\n" + contentFrame("fill ~0 ~0 ~0 ")
                + "\n" + contentFrame("~3 ~3 ~3 stone_bricks")
                + "\n\ndata: [DONE]\n\n";

        Recorder recorder = new Recorder();
        AtomicBoolean done = new AtomicBoolean();
        StringBuilder content = new StringBuilder();
        // 按 BodyReader.readLines 的切法喂行（保留空行），复刻真实读取路径
        for (String line : body.split("\n", -1)) {
            AiClient.feedStream(recorder, done, line, content);
        }
        done.set(true);
        recorder.onThinkingDone();

        // 重建信封（与 AiClient.readStreaming 里的做法一致）
        String rebuilt = "{\"choices\":[{\"delta\":{\"content\":"
                + new com.google.gson.Gson().toJson(content.toString()) + "}}]}";

        assertEquals(AiClient.extractContent(body), AiClient.extractContent(rebuilt));
        assertEquals("fill ~0 ~0 ~0 ~3 ~3 ~3 stone_bricks", content.toString());
        assertEquals("想一下", recorder.reasoning.toString());
    }
}
