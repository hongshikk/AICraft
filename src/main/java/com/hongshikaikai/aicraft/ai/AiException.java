package com.hongshikaikai.aicraft.ai;

/**
 * AI 调用或响应解析过程中的错误。
 *
 * <p>既用于「网络请求失败」，也用于「响应结构错误」——两者都会触发重试，
 * 重试次数用尽后由插件返还材料并提示玩家。</p>
 */
public class AiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiException(String message) {
        super(message);
    }

    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
