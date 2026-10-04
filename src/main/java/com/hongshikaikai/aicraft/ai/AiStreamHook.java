package com.hongshikaikai.aicraft.ai;

/**
 * 流式回调：把模型「正在想什么」实时暴露给调用方。
 *
 * <p>免费车道上的模型是推理模型，建造请求经常会先吐几分钟思维链
 * （SSE 帧里的 {@code delta.reasoning}）才写正文。没有这个钩子时，玩家只能对着
 * 「AI 正在设计建筑，请稍候…」干等，无法判断模型是在认真设计还是已经跑偏
 * —— 实测中它甚至会把用户没要求的内容（例如给角斗场插一块写着「黄鹤楼」的告示牌）
 * 写进方案，让思考链可见是排查这类问题最直接的手段。</p>
 *
 * <h2>线程约定</h2>
 * <p>两个回调都在 {@link AiClient} 的守护线程上触发（HTTP 响应体读取线程），
 * <b>绝不能直接碰 Bukkit / 玩家 / 世界</b>。实现方要自己
 * {@code Bukkit.getScheduler().runTask(...)} 切回主线程，并自行限流
 * （模型每秒可能吐几百个 token，逐条转发会把玩家聊天框刷爆）。</p>
 *
 * <h2>重试约定</h2>
 * <p>每次尝试都会由 {@code listenerFactory} 造一个全新的钩子，因此重试不会把上一次
 * 半途而废的思考内容混进来。历史尝试已发出去的内容无法收回，
 * 实现方如果要「整段只显示一次」，请参考 {@code BuildService} 的做法。</p>
 */
public interface AiStreamHook {

    /** 模型没有给出思维链、或调用方不关心时的空实现。 */
    AiStreamHook NONE = new AiStreamHook() {
        @Override
        public void onReasoning(String text) {
            // 什么都不做
        }

        @Override
        public void onThinkingDone() {
            // 什么都不做
        }
    };

    /**
     * 收到一段思维链增量。
     *
     * @param text 本次增量（可能只有一两个字，也可能是一整段）；不会为 null
     */
    void onReasoning(String text);

    /**
     * 思维链结束、开始写正文时触发一次。
     *
     * <p>推理模型会输出 {@code delta.reasoning} 再输出 {@code delta.content}；
     * 也有的模型（例如 {@code nemotron-3.5-lightning-free}）把所有推演都写在正文里，
     * 那种情况下本回调在正文结束、请求成功时才触发。</p>
     */
    void onThinkingDone();

    /**
     * 造一个把思维链累积起来、结束时一次性交给 {@code consumer} 的钩子。
     *
     * <p>给「不想实时刷屏、只想在收到结果时看到 AI 想了什么」的调用方用。
     * 内容为空时不会回调（不会给玩家发一条空的思考记录）。</p>
     *
     * @param consumer 结束时接收完整思维链的回调
     * @return 钩子
     */
    static AiStreamHook collecting(java.util.function.Consumer<String> consumer) {
        return new AiStreamHook() {
            private final StringBuilder builder = new StringBuilder();
            private boolean done;

            @Override
            public void onReasoning(String text) {
                if (text != null && !text.isEmpty()) {
                    builder.append(text);
                }
            }

            @Override
            public void onThinkingDone() {
                if (done) {
                    return;
                }
                done = true;
                String text = builder.toString().strip();
                if (!text.isEmpty()) {
                    consumer.accept(text);
                }
            }
        };
    }
}
