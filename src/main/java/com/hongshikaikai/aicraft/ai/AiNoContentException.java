package com.hongshikaikai.aicraft.ai;

/**
 * 「模型只思考、没写正文」专用异常，同样属于<b>不该重试</b>的失败。
 *
 * <p>免费车道的模型是推理模型，思维链和正文共用同一个 {@code max_tokens} 预算。
 * 实测（2026-10-04，{@code mimo-v2.6-flash-free}，一次「黄鹤楼 + 内饰」的建造请求）：
 * 16384 的预算跑了 304 秒、收到 899KB 数据，全部是 {@code delta.reasoning}，
 * 正文一个字都没有，最后被上游直接截断（上游自己还有约 304 秒的硬上限）。</p>
 *
 * <p>这种情况下把同一个请求原封不动重发，只会再想 5 分钟、再烧一次免费额度，
 * 所以 {@code AiClient} 把它当作「不可重试」处理，直接告诉玩家：
 * 这个描述对免费车道来说太重了。</p>
 */
public class AiNoContentException extends AiException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 可直接记日志的原因
     */
    public AiNoContentException(String message) {
        super(message);
    }
}
