package com.hongshikaikai.aicraft.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiClient#extractContent(String)} 的测试。
 *
 * <p>重点是「推理模型只吐思维链、不写正文」这条路径：它是 2026-10-04 那次建造事故的
 * 直接原因（16384 预算跑了 304 秒、899KB 全是 {@code reasoning}），必须被判成
 * {@link AiNoContentException}（不可重试），而不是普通的结构错误（会被重试 3 次、
 * 每次再等几分钟）。</p>
 */
class AiClientContentTest {

    private static String frame(String content, String reasoning) {
        StringBuilder delta = new StringBuilder();
        if (content != null) {
            delta.append("\"content\":\"").append(content).append('"');
        }
        if (reasoning != null) {
            if (delta.length() > 0) {
                delta.append(',');
            }
            delta.append("\"reasoning\":\"").append(reasoning).append('"');
        }
        return "data: {\"choices\":[{\"index\":0,\"finish_reason\":null,\"delta\":{"
                + delta + "}}]}\n\n";
    }

    @Test
    void returnsVisibleContentFromNormalStream() {
        String raw = frame("黄的", null)
                + frame("楼", "顺便想两句")   // 同一帧里正文和思维链都在：只取正文
                + "data: [DONE]\n\n";
        assertEquals("黄的楼", AiClient.extractContent(raw));
    }

    @Test
    void reasoningOnlyStreamIsNoContentAndNotRetryable() {
        // 思维链有内容、正文始终为空：真实截断流的形状
        String raw = frame("", "用户想要一座黄鹤楼，")
                + frame("", "先考虑台基尺寸……")
                + frame("", "再考虑飞檐层次……");
        AiException error = assertThrows(AiNoContentException.class,
                () -> AiClient.extractContent(raw));
        assertTrue(error instanceof AiNoContentException);
        assertFalse(error instanceof AiTimeoutException, "它不是超时，是另一类不可重试失败");
        assertFalse(error.getMessage().isBlank(), "日志里必须能看到原因");
    }

    /** 连思维链都没有的空流仍然是普通结构错误（保持原有的可重试语义）。 */
    @Test
    void emptyStreamStaysARetryableStructureError() {
        String raw = frame("", null) + "data: [DONE]\n\n";
        AiException error = assertThrows(AiException.class, () -> AiClient.extractContent(raw));
        assertFalse(error instanceof AiNoContentException);
        assertFalse(error instanceof AiTimeoutException);
    }

    /** 非流式响应（网关直接把整段 JSON 回给我们）里的错误信封仍要认出来。 */
    @Test
    void errorEnvelopeIsReported() {
        String raw = "{\"type\":\"error\",\"error\":{\"type\":\"FreeTierError\","
                + "\"message\":\"fingerprint rejected\"}}";
        AiException error = assertThrows(AiException.class, () -> AiClient.extractContent(raw));
        assertTrue(error.getMessage().contains("FreeTierError"), error.getMessage());
    }

    /** 非流式响应里只有思维链、没有正文时同样判成不可重试。 */
    @Test
    void nonStreamingReasoningOnlyIsAlsoNoContent() {
        String raw = "{\"choices\":[{\"message\":{\"content\":\"\",\"reasoning\":\"想很久\"}}]}";
        assertThrows(AiNoContentException.class, () -> AiClient.extractContent(raw));
    }
}
