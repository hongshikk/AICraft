package com.hongshikaikai.aicraft.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.config.PluginConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 免费 AI 接口客户端（OpenCode Zen 免密车道）。
 *
 * <h2>这个类是怎么来的</h2>
 * <p>请求方式逐条对照项目内的 {@code dsh-our-free-model} 插件（{@code src/upstream.js}、
 * {@code src/http.js}、{@code src/adapter.js}）逆向得出，并在 2026-10 用真实请求验证：</p>
 *
 * <ul>
 *   <li><b>地址</b>：{@code POST https://opencode.ai/zen/v1/chat/completions}（OpenAI Chat 形状）</li>
 *   <li><b>鉴权</b>：{@code Authorization: Bearer public} —— 这条车道本就是公开免密额度，
 *       没有属于用户的私钥；换成别的 OpenAI 兼容服务时在 config.yml 里填自己的 Key 即可。</li>
 *   <li><b>客户端指纹</b>：{@code User-Agent: opencode/1.18.31}（网关要求版本号 ≥ 1.17）+
 *       {@code x-opencode-client / -session / -request / -project} 四个头。</li>
 *   <li><b>工具指纹闸门</b>：请求体必须声明 {@code bash/glob/grep/read} 四个小写工具名，
 *       否则网关回 {@code 403 FreeTierError}；由于本插件不需要工具调用，
 *       这四个工具以「不可用」描述占位，并把 {@code tool_choice} 设为 {@code none}。</li>
 *   <li><b>只能流式</b>：{@code stream} 必须为 {@code true}，{@code stream:false} 同样会被
 *       指纹闸门拒绝。因此这里按 SSE（{@code data: {...}} 帧）逐行解析，
 *       只累加 {@code choices[].delta.content}。</li>
 *   <li><b>会话 ID</b>：免费额度按会话计量，所以同一玩家的会话 ID 是稳定的
 *       （由 UUID 做 SHA-256 派生），格式必须是 {@code ses_<12位小写hex><14位base62>}；
 *       请求 ID 每次尝试重新生成，格式 {@code msg_<12位hex><14位base62>}。</li>
 * </ul>
 *
 * <h2>线程模型</h2>
 * <p>全部走 {@link HttpClient#sendAsync} + 自己的守护线程池，<b>不会阻塞主线程</b>；
 * 构建物品、操作背包一律由调用方（{@code AICraft}）在回调里用
 * {@code Bukkit.getScheduler().runTask(...)} 切回主线程执行。</p>
 *
 * <h2>超时</h2>
 * <p>分两条红线（见 {@link BodyReader}）：<b>闲置红线</b> {@code api.idle-timeout-seconds}
 * 只惩罚「真的卡住了」——只要还在持续出数据就一直读；<b>总时长红线</b>是绝对兜底，
 * 合成用 {@code api.timeout-seconds}、建造用 {@code build.timeout-seconds}（更宽，
 * 因为推理模型吐完思维链才写正文）。</p>
 *
 * <p>超时抛 {@link AiTimeoutException}，消息里带人类可读的原因，
 * 不再是 {@code orTimeout} 那个 message 为 null 的 {@code TimeoutException}。</p>
 *
 * <h2>重试</h2>
 * <p>请求失败或响应结构错误都会自动重试，最多 {@code api.max-attempts} 次
 * （默认 3，含首次），每次重试的退避时间为 {@code api.retry-backoff-ms * 第几次}。
 * <b>超时是例外</b>：{@link AiTimeoutException} 不再重试——同一份超大请求重发一遍
 * 只会再等一个超时周期，白白拖长玩家的等待并烧掉免费额度。</p>
 */
public final class AiClient {

    /** base62 字母表，用于拼出网关要求的会话 / 请求 ID。 */
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    /** 免费车道指纹闸门要求声明的小写工具名。 */
    private static final String[] FINGERPRINT_TOOLS = {"bash", "glob", "grep", "read"};

    /** SSE 流的结束帧，解析时直接跳过。 */
    private static final String DONE_FRAME = "[DONE]";

    private static final Gson GSON = new Gson();

    private final AICraft plugin;
    private final PluginConfig config;
    private final String systemPrompt;
    private final HttpClient httpClient;
    private final ExecutorService executor;

    /**
     * @param plugin       插件实例（用于日志）
     * @param config       类型化配置
     * @param systemPrompt 系统提示词（来自 resources/prompt.txt）
     */
    public AiClient(AICraft plugin, PluginConfig config, String systemPrompt) {
        this.plugin = plugin;
        this.config = config;
        this.systemPrompt = systemPrompt;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("AICraft-AI"));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 异步请求一次 AI 合成。
     *
     * @param playerId       发起合成的玩家（决定会话 ID，从而决定免费额度的计量单元）
     * @param userPayload    user message 的内容，形如 {@code {"materials":[...]}}
     * @return 解析完成的 future；失败时异常类型为 {@link AiException}
     */
    public CompletableFuture<AiCraftResult> requestCraft(UUID playerId, JsonObject userPayload) {
        return request(playerId, config.apiModel(), systemPrompt, userPayload, config.apiMaxTokens(),
                config.apiTimeoutSeconds(), AiClient::parseCraftResult, null);
    }

    /**
     * 异步请求一次模型正文（不做 JSON 解析）。
     *
     * <p>供 {@code /aibuild} 使用：即使模型没有严格输出 JSON，调用方也能拿到正文
     * 做降级解析（例如按行读建造指令）。</p>
     *
     * <p>总时长红线用 {@code build.timeout-seconds} 而不是 {@code api.timeout-seconds}：
     * 建造的正文远长于合成，且免费车道的推理模型会先输出一大段思维链。
     * 模型用 {@code build.model}（留空则等于 {@code api.model}）。</p>
     *
     * @param playerId    发起请求的玩家（决定会话 ID）
     * @param prompt      本次使用的系统提示词
     * @param userPayload user message 的内容
     * @param maxTokens   本次生成的最大 token 数
     * @return 模型正文；失败时异常类型为 {@link AiException}
     */
    public CompletableFuture<String> requestText(UUID playerId, String prompt,
                                                 JsonObject userPayload, int maxTokens) {
        return request(playerId, config.buildModel(), prompt, userPayload, maxTokens,
                config.buildTimeoutSeconds(), AiClient::extractContent, null);
    }

    /**
     * 异步请求一次模型正文，并把思维链实时回调给调用方。
     *
     * <p>与 {@link #requestText} 只有一点不同：响应体是<b>逐行流式</b>读的，
     * 每读到一个 SSE 帧就把 {@code delta.reasoning}（思维链）交给
     * {@code listenerFactory} 造出来的 {@link AiStreamHook}。这样 {@code /aibuild}
     * 就能把「AI 正在想什么」转达给玩家，而不是让玩家对着「正在设计建筑」干等。</p>
     *
     * <p>正文的拼接与返回与不流式的那条路径完全一致（同一个 {@link #extractContent}），
     * 所以两条路径不会解析出不同的结果。重试时每次都会重新造一个钩子，
     * 上一次失败尝试的思考内容不会混进这一次。</p>
     *
     * @param playerId        发起请求的玩家
     * @param prompt          系统提示词
     * @param userPayload     user message
     * @param maxTokens       生成上限
     * @param listenerFactory 每次尝试开始时调用一次，返回本次的钩子
     * @return 模型正文；失败时异常类型为 {@link AiException}
     */
    public CompletableFuture<String> requestStreamingText(UUID playerId, String prompt,
                                                          JsonObject userPayload, int maxTokens,
                                                          Supplier<AiStreamHook> listenerFactory) {
        return request(playerId, config.buildModel(), prompt, userPayload, maxTokens,
                config.buildTimeoutSeconds(), AiClient::extractContent, listenerFactory);
    }

    /** 关闭后台线程池（插件卸载时调用）。 */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 重试编排
    // ------------------------------------------------------------------

    /**
     * 通用请求入口：构造请求体 -> 发一次 -> 用 {@code parser} 解析 -> 失败则按配置重试。
     *
     * @param playerId            玩家（会话 ID）
     * @param model               本次使用的模型
     * @param prompt              系统提示词
     * @param userPayload         user message
     * @param maxTokens           生成上限
     * @param totalTimeoutSeconds 本次请求的总时长红线（秒）
     * @param parser              把模型正文转成目标类型的解析器
     * @param <T>                 目标类型
     * @return 解析完成的 future
     */
    private <T> CompletableFuture<T> request(UUID playerId, String model, String prompt,
                                             JsonObject userPayload, int maxTokens,
                                             int totalTimeoutSeconds, Function<String, T> parser,
                                             Supplier<AiStreamHook> listenerFactory) {
        JsonObject body = buildBody(model, prompt, userPayload, maxTokens);
        String session = sessionFor(playerId);
        return attempt(body, session, totalTimeoutSeconds, 1, parser, listenerFactory);
    }

    private <T> CompletableFuture<T> attempt(JsonObject body, String session, int totalTimeoutSeconds,
                                             int attemptNo, Function<String, T> parser,
                                             Supplier<AiStreamHook> listenerFactory) {
        return callOnce(body, session, totalTimeoutSeconds, listenerFactory)
                .thenApply(parser)
                .handle((result, error) -> {
                    if (error == null && result != null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    Throwable cause = unwrap(error == null ? new AiException("AI 没有返回结果") : error);
                    // 两类失败不重试：超时（再等一个周期也一样）和「只思考没正文」
                    // （重发同一份请求只会再想几分钟、再烧一次免费额度）。
                    boolean retryable = !(cause instanceof AiTimeoutException)
                            && !(cause instanceof AiNoContentException);
                    if (!retryable || attemptNo >= config.apiMaxAttempts()) {
                        plugin.getLogger().warning("AI 请求最终失败（共尝试 " + attemptNo + " 次）："
                                + describe(cause));
                        String message = retryable
                                ? "已尝试 " + attemptNo + " 次仍失败：" + describe(cause)
                                : describe(cause);
                        return CompletableFuture.<T>failedFuture(new AiException(message, cause));
                    }
                    long delay = config.apiRetryBackoffMs() * attemptNo;
                    plugin.getLogger().warning("AI 请求第 " + attemptNo + " 次失败（" + describe(cause)
                            + "），" + delay + "ms 后重试");
                    Executor delayed = CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executor);
                    return CompletableFuture.supplyAsync(() -> null, delayed)
                            .thenCompose(ignored -> attempt(body, session, totalTimeoutSeconds,
                                    attemptNo + 1, parser, listenerFactory));
                })
                .thenCompose(future -> future);
    }

    // ------------------------------------------------------------------
    // 单次请求
    // ------------------------------------------------------------------

    /** 发一次请求并读回未经解析的响应体。 */
    private CompletableFuture<String> callOnce(JsonObject body, String session, int totalTimeoutSeconds,
                                               Supplier<AiStreamHook> listenerFactory) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(config.apiBaseUrl() + config.apiPath()))
                    .timeout(Duration.ofSeconds(totalTimeoutSeconds))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + config.apiKey())
                    .header("user-agent", config.apiUserAgent())
                    .header("x-opencode-client", config.apiClient())
                    .header("x-opencode-session", session)
                    .header("x-opencode-request", newRequestId())
                    .header("x-opencode-project", config.apiProject())
                    .header("accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(new AiException("构造 HTTP 请求失败：" + describe(ex), ex));
        }

        // 两段时间分开管：
        //   1) 等响应头 —— 由 HttpRequest.timeout 兜底（期间没有任何字节可观测）；
        //   2) 读响应体 —— 由 BodyReader 的「闲置 + 总时长」看门狗兜底，慢流不再被误杀。
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .handle((response, error) -> {
                    if (error != null) {
                        throw new CompletionException(mapRequestFailure(unwrap(error), totalTimeoutSeconds));
                    }
                    return response;
                })
                .thenApplyAsync(response -> readBody(response, totalTimeoutSeconds, listenerFactory), executor);
    }

    /**
     * 把「发请求阶段」的失败翻译成带可读消息的 {@link AiException}。
     *
     * @param cause               底层异常
     * @param totalTimeoutSeconds 本次配置的总时长红线
     * @return 可直接记日志的异常
     */
    private static Throwable mapRequestFailure(Throwable cause, int totalTimeoutSeconds) {
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new AiTimeoutException("AI 响应超时：请求发出后 " + totalTimeoutSeconds
                    + " 秒内没有返回响应头（上游排队或网络不通）", cause);
        }
        if (cause instanceof AiException) {
            return cause;
        }
        return new AiException("请求 AI 接口失败：" + describe(cause), cause);
    }

    /**
     * 读取原始响应体，并做 HTTP 状态码检查。
     *
     * <p>给了 {@code listenerFactory} 时走逐行流式读取：每读到一个 SSE 帧就把思维链
     * （{@code delta.reasoning}）实时交给钩子，正文本地一边读一边拼；
     * 没给时（合成）仍然一次读干净，行为与以前完全一致。</p>
     *
     * @param response            HTTP 响应
     * @param totalTimeoutSeconds 本次请求的总时长红线（秒）
     * @param listenerFactory     每次尝试的思维链钩子工厂；为 null 表示不需要流式
     * @return 原始字符串（SSE 帧流 或 一段 JSON）
     */
    private String readBody(HttpResponse<InputStream> response, int totalTimeoutSeconds,
                            Supplier<AiStreamHook> listenerFactory) {
        String raw;
        try (InputStream in = response.body()) {
            if (listenerFactory == null) {
                raw = BodyReader.readAll(in,
                        TimeUnit.SECONDS.toMillis(config.apiIdleTimeoutSeconds()),
                        TimeUnit.SECONDS.toMillis(totalTimeoutSeconds));
            } else {
                raw = readStreaming(in, totalTimeoutSeconds, listenerFactory);
            }
        } catch (IOException ex) {
            throw new AiException("读取 AI 响应失败：" + describe(ex), ex);
        }
        int status = response.statusCode();
        if (config.debug()) {
            plugin.getLogger().info("[debug] AI HTTP " + status + " -> " + abbreviate(raw, 2000));
        }
        if (status < 200 || status >= 300) {
            throw new AiException("AI 接口返回 HTTP " + status + "：" + describeErrorBody(raw));
        }
        return raw;
    }

    /**
     * 流式读取：逐行解析 SSE，实时回调思维链，顺便只把「正文」攒下来。
     *
     * <p>推理模型的原始流可能有几 MB（思维链占绝大部分），所以这里<strong>不</strong>
     * 把原文留在内存里：正文增量就地拼进 buffer，末尾再重建一个最小的
     * {@code {"choices":[{"delta":{"content":…}}]}} 信封，交给与不流式路径完全相同的
     * {@link #extractContent} 解析。这样两条路径的解析结果一致，又不会为了转达思考链
     * 多占几 MB 内存。</p>
     *
     * @param in                 响应体流
     * @param totalTimeoutSeconds 总时长红线（秒）
     * @param listenerFactory     钩子工厂
     * @return 重建出来的响应体文本（只含正文）
     * @throws IOException 读取失败 / 超时
     */
    private String readStreaming(InputStream in, int totalTimeoutSeconds,
                                 Supplier<AiStreamHook> listenerFactory) throws IOException {
        AiStreamHook hook = listenerFactory.get();
        if (hook == null) {
            hook = AiStreamHook.NONE;
        }
        AiStreamHook listener = hook;
        StringBuilder content = new StringBuilder();
        AtomicBoolean thinkingDone = new AtomicBoolean();
        BodyReader.readLines(in,
                TimeUnit.SECONDS.toMillis(config.apiIdleTimeoutSeconds()),
                TimeUnit.SECONDS.toMillis(totalTimeoutSeconds),
                line -> feedStream(listener, thinkingDone, line, content));
        // 正文写完 / 请求收尾：保证「思考结束」一定会通知一次（有的模型把推演写在正文里）
        thinkingDone.set(true);
        listener.onThinkingDone();

        // 重建一段等价于「正常 SSE 流」的响应体，正文完全没出现时返回错误信封，
        // 让 extractContent 给出与不流式路径一模一样的报错（含「只思考没正文」的判定）。
        JsonObject delta = new JsonObject();
        if (content.length() > 0) {
            delta.addProperty("content", content.toString());
        } else {
            delta.addProperty("reasoning", "");
        }
        JsonObject choice = new JsonObject();
        choice.add("delta", delta);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject envelope = new JsonObject();
        envelope.add("choices", choices);
        return GSON.toJson(envelope);
    }

    /**
     * 解析一行 SSE，把它转达给钩子、并把正文增量攒进 {@code content}。
     *
     * <p>读不出内容（空行、非 {@code data:} 行、半个 JSON 帧）就安静跳过：
     * 流式读取时每一行都可能是不完整的分片，这里绝不能抛异常打断请求。</p>
     *
     * <p>包内可见是为了让单测能直接喂行、不必起 HTTP 服务。</p>
     */
    static void feedStream(AiStreamHook listener, AtomicBoolean thinkingDone, String line,
                           StringBuilder content) {
        String stripped = line == null ? "" : line.strip();
        if (!stripped.startsWith("data:")) {
            return;
        }
        String data = stripped.substring("data:".length()).strip();
        if (data.isEmpty() || DONE_FRAME.equals(data)) {
            return;
        }
        JsonObject frame = tryParseObject(data);
        if (frame == null || isErrorFrame(frame)) {
            return;
        }
        String reasoning = collectReasoning(frame);
        if (!reasoning.isEmpty()) {
            listener.onReasoning(reasoning);
        }
        String delta = collectFromChoices(frame);
        if (!delta.isEmpty()) {
            content.append(delta);
            if (thinkingDone.compareAndSet(false, true)) {
                listener.onThinkingDone();
            }
        }
    }

    /** 从原始响应里取出模型正文，再解析成合成结果。 */
    private static AiCraftResult parseCraftResult(String raw) {
        return AiCraftResult.parse(parseJsonObject(raw));
    }

    /** 从原始响应里取出模型正文，再解析成 JSON 对象。 */
    private static JsonObject parseJsonObject(String raw) {
        String content = extractContent(raw);
        JsonObject json = extractJsonObject(content);
        if (json == null) {
            throw new AiException("AI 返回的正文里找不到合法 JSON：" + abbreviate(content, 200));
        }
        return json;
    }    // ------------------------------------------------------------------
    // SSE / JSON 解析
    // ------------------------------------------------------------------

    /**
     * 兼容两种响应体：
     * <ol>
     *   <li>正常的 SSE 帧流 —— 累加 {@code choices[].delta.content}；</li>
     *   <li>网关直接把错误信封 / 非流式补全当 JSON 回 —— 分别报错或取
     *       {@code choices[].message.content}。</li>
     * </ol>
     *
     * @param raw 响应体
     * @return 模型输出的原始文本
     */
    static String extractContent(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            throw new AiException("AI 返回了空响应");
        }

        if (!looksLikeSse(trimmed)) {
            JsonObject whole = tryParseObject(trimmed);
            if (whole == null) {
                throw new AiException("无法解析 AI 响应：" + abbreviate(trimmed, 300));
            }
            if (isErrorFrame(whole)) {
                throw new AiException(describeErrorFrame(whole));
            }
            String text = collectFromChoices(whole);
            if (!text.isEmpty()) {
                return text;
            }
            if (!collectReasoning(whole).isEmpty()) {
                throw new AiNoContentException("AI 只返回了思考内容，没有正文");
            }
            throw new AiException("AI 返回体不符合预期：" + abbreviate(trimmed, 300));
        }

        StringBuilder content = new StringBuilder();
        boolean sawReasoning = false;
        for (String line : raw.split("\r?\n")) {
            String stripped = line.strip();
            if (!stripped.startsWith("data:")) {
                continue;
            }
            String data = stripped.substring("data:".length()).strip();
            if (data.isEmpty() || DONE_FRAME.equals(data)) {
                continue;
            }
            JsonObject frame = tryParseObject(data);
            if (frame == null) {
                continue;
            }
            if (isErrorFrame(frame)) {
                throw new AiException(describeErrorFrame(frame));
            }
            content.append(collectFromChoices(frame));
            if (!sawReasoning && !collectReasoning(frame).isEmpty()) {
                sawReasoning = true;
            }
        }

        if (content.length() == 0) {
            if (sawReasoning) {
                // 推理模型把整个生成预算花在思维链上、正文一个字没写：重发同一份请求
                // 只会再想几分钟，所以标记成不可重试。
                throw new AiNoContentException("AI 把生成预算全花在了思考上，没有写出正文"
                        + "（可把请求描述得更简单，或换 api.model）");
            }
            throw new AiException("AI 返回的流里没有正文内容");
        }
        return content.toString();
    }

    /** 从一帧（或一个完整响应）里取出正文：优先 delta.content，其次 message.content。 */
    private static String collectFromChoices(JsonObject object) {
        StringBuilder builder = new StringBuilder();
        JsonArray choices = optArray(object, "choices");
        if (choices == null) {
            return "";
        }
        for (JsonElement element : choices) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject choice = element.getAsJsonObject();
            JsonObject delta = optObject(choice, "delta");
            if (delta != null) {
                builder.append(optText(delta, "content"));
            }
            JsonObject message = optObject(choice, "message");
            if (message != null) {
                builder.append(optText(message, "content"));
            }
            builder.append(optText(choice, "text"));
        }
        return builder.toString();
    }

    /**
     * 从一帧里取出「思考内容」（推理模型的 {@code delta.reasoning}）。
     *
     * <p>本插件不使用它，只用来判断「模型是不是只在思考、正文没写」——
     * 那种情况下重试毫无意义。同时它也是排查时最有用的信号。</p>
     *
     * @param object 一帧或一个完整响应
     * @return 思考内容，没有则返回空串
     */
    private static String collectReasoning(JsonObject object) {
        StringBuilder builder = new StringBuilder();
        JsonArray choices = optArray(object, "choices");
        if (choices == null) {
            return "";
        }
        for (JsonElement element : choices) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject choice = element.getAsJsonObject();
            JsonObject delta = optObject(choice, "delta");
            if (delta != null) {
                builder.append(optText(delta, "reasoning"));
            }
            JsonObject message = optObject(choice, "message");
            if (message != null) {
                builder.append(optText(message, "reasoning"));
            }
        }
        return builder.toString();
    }

    /**
     * 从模型正文里抠出 JSON 对象。
     *
     * <p>容错：整段就是 JSON 时直接用；被 Markdown 代码块包住、或前后有解释性文字时，
     * 取第一个 {@code &#123;} 到最后一个 {@code &#125;} 之间的部分。</p>
     *
     * @param content 模型正文
     * @return JSON 对象，找不到返回 null
     */
    public static JsonObject extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        JsonObject direct = tryParseObject(text);
        if (direct != null) {
            return direct;
        }

        // 去掉 ```json ... ``` 围栏
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.strip();
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return tryParseObject(text.substring(start, end + 1));
    }

    private static boolean looksLikeSse(String text) {
        if (text.startsWith("{") || text.startsWith("[")) {
            return false;
        }
        return text.startsWith("data:") || text.startsWith(":") || text.contains("\ndata:");
    }

    private static boolean isErrorFrame(JsonObject object) {
        JsonElement error = object.get("error");
        if (error != null && !error.isJsonNull()) {
            return true;
        }
        JsonElement type = object.get("type");
        return type != null && type.isJsonPrimitive() && "error".equalsIgnoreCase(type.getAsString());
    }

    private static String describeErrorFrame(JsonObject object) {
        JsonElement error = object.get("error");
        if (error != null && error.isJsonObject()) {
            JsonObject inner = error.getAsJsonObject();
            String type = optText(inner, "type");
            String message = optText(inner, "message");
            String combined = (type.isEmpty() ? "" : "[" + type + "] ") + message;
            if (!combined.isBlank()) {
                return combined.trim();
            }
        }
        if (error != null && error.isJsonPrimitive()) {
            return error.getAsString();
        }
        String message = optText(object, "message");
        if (!message.isEmpty()) {
            return message;
        }
        return abbreviate(object.toString(), 300);
    }

    private static String describeErrorBody(String raw) {
        JsonObject object = tryParseObject(raw == null ? "" : raw.strip());
        if (object != null && isErrorFrame(object)) {
            return describeErrorFrame(object);
        }
        return abbreviate(raw == null ? "" : raw.strip(), 300);
    }

    private static JsonObject tryParseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JsonObject optObject(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String optText(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // 请求体与会话 ID
    // ------------------------------------------------------------------

    /**
     * 构造请求体。
     *
     * <p>形状与 {@code dsh-our-free-model} 的 {@code buildPayload('chat', ...)} 一致：
     * OpenAI Chat Completions + {@code stream:true}，外加免费车道要求的工具指纹。</p>
     *
     * @param model       本次使用的模型（合成 {@code api.model} / 建造 {@code build.model}）
     * @param prompt      本次使用的系统提示词
     * @param userPayload user message 内容
     * @param maxTokens   本次生成的最大 token 数
     * @return 请求体
     */
    private JsonObject buildBody(String model, String prompt, JsonObject userPayload, int maxTokens) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", prompt);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        // 规格 4.3：把材料序列化成 JSON 字符串作为 user message
        user.addProperty("content", GSON.toJson(userPayload));
        messages.add(user);

        body.add("messages", messages);
        body.addProperty("stream", true);
        body.addProperty("max_tokens", Math.max(64, maxTokens));
        body.addProperty("temperature", config.apiTemperature());

        if (config.apiToolFingerprint()) {
            JsonArray tools = new JsonArray();
            for (String name : FINGERPRINT_TOOLS) {
                JsonObject parameters = new JsonObject();
                parameters.addProperty("type", "object");
                parameters.add("properties", new JsonObject());

                JsonObject function = new JsonObject();
                function.addProperty("name", name);
                function.addProperty("description", "This tool is currently unavailable and must not be used.");
                function.add("parameters", parameters);

                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("tool_choice", "none");
        }
        return body;
    }

    /**
     * 由玩家 UUID 派生一个稳定且格式合法的会话 ID。
     *
     * <p>格式必须匹配 {@code ^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$}：免费额度按会话计量，
     * 而且网关会校验这个形状（格式不对会被指纹闸门当成非 OpenCode 客户端，
     * 直接回 403 FreeTierError）。</p>
     *
     * @param playerId 玩家 UUID
     * @return 会话 ID
     */
    private static String sessionFor(UUID playerId) {
        byte[] hash = sha256(("aicraft-session\0" + playerId).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            hex.append(String.format("%02x", hash[i]));
        }
        StringBuilder tail = new StringBuilder(14);
        for (int i = 6; i < 20; i++) {
            tail.append(BASE62.charAt(Math.floorMod((int) hash[i], 62)));
        }
        return "ses_" + hex + tail;
    }

    /**
     * 生成一次请求 ID，格式 {@code ^msg_[0-9a-f]{12}[0-9A-Za-z]{14}$}。
     *
     * @return 请求 ID
     */
    private static String newRequestId() {
        long timestamp = System.currentTimeMillis();
        StringBuilder hex = new StringBuilder(12);
        for (int shift = 40; shift >= 0; shift -= 8) {
            hex.append(String.format("%02x", (timestamp >> shift) & 0xffL));
        }
        byte[] random = new byte[14];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(random);
        StringBuilder tail = new StringBuilder(14);
        for (byte value : random) {
            tail.append(BASE62.charAt(Math.floorMod((int) value, 62)));
        }
        return "msg_" + hex + tail;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256", ex);
        }
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 剥掉 {@link CompletionException} / {@link ExecutionException} 的包装，拿到真正的失败原因。
     *
     * <p>公开出来是给调用方（{@code BuildService}、{@code AICraft}）记日志用的：
     * 不剥壳的话日志里只会看到 {@code java.util.concurrent.CompletionException: ...}。</p>
     *
     * @param error 回调里拿到的异常
     * @return 最内层原因；error 为 null 时返回一个兜底的 {@link AiException}
     */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? new AiException("未知错误") : current;
    }

    /**
     * 取一段「一定能看懂」的异常描述。
     *
     * <p>{@code TimeoutException} 这类异常的 {@code getMessage()} 是 null，
     * 旧代码直接拼 {@code getMessage()}，日志里就成了「AI 请求第 1 次失败（null）」。
     * 这里退化成类名，保证日志永远有内容。</p>
     *
     * @param error 异常
     * @return 异常消息；为空时返回类名
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return error.getClass().getSimpleName();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    /** 守护线程工厂：插件卸载后不阻止服务端退出。 */
    private static final class DaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();
        private final String prefix;

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
