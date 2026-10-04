package com.hongshikaikai.aicraft.ai;

/**
 * 超时专用的 {@link AiException}。
 *
 * <p>存在的意义只有一个：让 {@code AiClient} 能区分「这次失败值得重试」和
 * 「重试也是白等」。免费车道上的模型是推理模型，一次建造请求可能先在思维链上
 * 耗掉几分钟，这时把同一个超大请求原封不动再发两遍毫无意义，只会让玩家多等
 * 两个超时周期、白烧免费额度。</p>
 */
public class AiTimeoutException extends AiException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message 已经可以直接展示给管理员/日志的原因（例如「AI 响应超时：已 32 秒没有收到新数据」）
     * @param cause   底层异常，可为 null
     */
    public AiTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
