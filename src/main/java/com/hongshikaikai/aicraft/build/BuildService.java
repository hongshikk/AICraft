package com.hongshikaikai.aicraft.build;

import com.google.gson.JsonObject;
import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.ai.AiClient;
import com.hongshikaikai.aicraft.ai.AiException;
import com.hongshikaikai.aicraft.ai.AiNoContentException;
import com.hongshikaikai.aicraft.ai.AiStreamHook;
import com.hongshikaikai.aicraft.ai.AiTimeoutException;
import com.hongshikaikai.aicraft.config.PluginConfig;
import com.hongshikaikai.aicraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code /aibuild} 的编排中心。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>把玩家的一句话请求 + 建造原点信息发给 AI（异步，不阻塞主线程）；</li>
 *   <li>回调切回主线程后解析 {@link BuildPlan}，按 tick 分片施工（{@link BuildSession}）；</li>
 *   <li>维护每位玩家的建造冷却、进行中的建造、以及可撤销的历史快照。</li>
 * </ol>
 *
 * <p>与合成流程一致：所有涉及世界的操作都在主线程执行，网络请求走
 * {@link com.hongshikaikai.aicraft.ai.AiClient} 的守护线程池。</p>
 */
public final class BuildService {

    /**
     * 建造原点。
     *
     * @param world 世界
     * @param x     地基坐标
     * @param y     地基坐标（AI 的相对坐标 y=0 就是这一层）
     * @param z     地基坐标
     */
    public record BuildPos(World world, int x, int y, int z) {

        /** @return 供 {@link BuildOp} 使用的坐标 */
        public BuildOp.Pos toOpPos() {
            return new BuildOp.Pos(x, y, z);
        }
    }

    /** resources/build-prompt.txt 缺失时的兜底提示词（精简版）。 */
    private static final String FALLBACK_PROMPT = """
            你是一个 Minecraft 建筑工程师，负责把玩家的一句话需求变成一个 JSON 建造方案。

            只输出一个严格 JSON 对象（不要思考过程、不要解释、不要 Markdown 代码围栏）：
            {
              "success": true,
              "name": "建筑名",
              "summary": "一句话说明",
              "ops": [
                { "op": "fill",     "from": [-4, 0, -4], "to": [4, 0, 4], "block": "stone_bricks" },
                { "op": "walls",    "from": [-4, 1, -4], "to": [4, 3, 4], "block": "oak_planks" },
                { "op": "setblock", "at": [0, 3, 0], "block": "oak_planks" }
              ]
            }
            无法完成时输出：{ "success": false, "reason": "原因" }

            坐标是相对建造原点的三个整数 [x, y, z]，不要写 ~：
              [0,0,0] 是地基那层；+X 向东、+Z 向南、+Y 向上；允许负数。
            可用的 op：
              fill     from, to, block                长方体实心填充
              hollow   from, to, block                长方体六面外壳
              walls    from, to, block                长方体四面竖墙
              line     from, to, block                两点之间的直线
              setblock at, block                      单个方块
              sphere   at, radius, block[, hollow]    球体
              cylinder at, radius, height, block[, hollow] 圆柱（高度沿 Y 轴）
              replace  from, to, block[, filter]      区域内替换（filter 默认 any）
              sign     at, text                       告示牌（text 最多 4 行）

            方块名用小写原版 ID，可带方块状态（如 oak_stairs[facing=north,half=top]）。

            必须遵守：
            1. 建筑必须与玩家 request 里写的对象一致；不要添加没被要求的元素
               （尤其是告示牌：玩家没要求就不要放，更不要写无关的名字）。
            2. name、summary 只能是对本次 request 的概括，不能出现 request 里没有的建筑名。
            3. ops 至少一条、最多 60 条；大体量用 fill / hollow / walls 一条顶一片。
            4. ops 里不要放字符串指令、不要写注释、不要加尾逗号。
            """;

    private final AICraft plugin;

    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, BuildSession> active = new ConcurrentHashMap<>();
    private final Set<RestoreSession> restores = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Deque<UndoSnapshot>> undoHistory = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingAi = ConcurrentHashMap.newKeySet();
    /** 每位玩家「思考链转达」的限流状态（只在建造请求期间使用）。 */
    private final Map<UUID, ThinkingStreamState> thinking = new ConcurrentHashMap<>();

    private String prompt;

    /**
     * @param plugin 插件实例
     */
    public BuildService(AICraft plugin) {
        this.plugin = plugin;
        this.prompt = loadPrompt();
    }

    /** 重载配置后重建提示词与冷却。 */
    public void reload() {
        this.prompt = loadPrompt();
        this.cooldowns.clear();
    }

    // ------------------------------------------------------------------
    // 提交建造
    // ------------------------------------------------------------------

    /**
     * 把玩家的一句话请求交给 AI 设计并施工。
     *
     * @param player  玩家
     * @param request 玩家描述，例如「帮我在这里建造一个黄鹤楼」
     */
    public void submit(Player player, String request) {
        PluginConfig config = plugin.pluginConfig();
        if (!config.buildEnabled()) {
            player.sendMessage(config.message("build-disabled"));
            return;
        }
        UUID id = player.getUniqueId();
        if (active.containsKey(id) || awaitingAi.contains(id)) {
            player.sendMessage(config.message("build-busy"));
            return;
        }
        long remaining = remainingCooldownSeconds(id);
        if (remaining > 0) {
            player.sendMessage(config.message("build-cooldown", "seconds", Long.toString(remaining)));
            return;
        }

        BuildPos origin = resolveOrigin(player);
        awaitingAi.add(id);
        player.sendMessage(config.message("build-thinking"));

        JsonObject payload = buildPayload(player, request, origin);
        String playerName = player.getName();
        long startedNanos = System.nanoTime();
        boolean streamThinking = config.buildStreamThinking();
        if (streamThinking) {
            thinking.put(id, new ThinkingStreamState(startedNanos));
        }

        CompletableFuture<String> pending = streamThinking
                ? plugin.aiClient().requestStreamingText(id, prompt, payload, config.buildMaxTokens(),
                        () -> newThinkingHook(id, playerName, startedNanos))
                : plugin.aiClient().requestText(id, prompt, payload, config.buildMaxTokens());

        pending.whenComplete((content, error) -> {
            ThinkingStreamState state = streamThinking ? thinking.get(id) : null;
            if (state != null && state.sent()) {
                Runnable done = () -> send(Bukkit.getPlayer(id),
                        config.message("build-thinking-done"));
                runOnMainThread(done);
            }
            // HTTP 回调线程：先切回主线程再碰世界 / 玩家
            Runnable task = () -> handleAiResult(id, origin, content, error);
            runOnMainThread(task);
        });
    }

    /**
     * 把一段可发送的任务切回主线程；插件已卸载时直接放弃。
     *
     * @param task 需要主线程执行的任务
     */
    private void runOnMainThread(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("无法把 AI 回调切回主线程：" + ex.getMessage());
        }
    }

    /** AI 回调（已切回主线程）。 */
    private void handleAiResult(UUID id, BuildPos origin, String content, Throwable error) {
        awaitingAi.remove(id);
        thinking.remove(id);
        if (!plugin.isEnabled()) {
            return;
        }
        PluginConfig config = plugin.pluginConfig();
        Player player = Bukkit.getPlayer(id);

        if (error != null) {
            Throwable cause = AiClient.unwrap(error);
            plugin.getLogger().warning("AI 建造请求失败（" + id + "）：" + AiClient.describe(cause));
            if (config.debug()) {
                cause.printStackTrace();
            }
            startCooldown(id);
            if (cause instanceof AiTimeoutException) {
                // 超时不是「网络抖动」：重试也是白等，直接告诉玩家真实原因和可调项。
                send(player, config.message("build-timeout",
                        "seconds", Integer.toString(config.buildTimeoutSeconds())));
            } else if (cause instanceof AiNoContentException) {
                // 推理模型把预算全花在思维链上：同样不该重试，让玩家知道要简化描述。
                send(player, config.message("build-no-content"));
            } else {
                send(player, config.message("build-failed", "reason", "AI 无响应，请稍后重试"));
            }
            return;
        }

        BuildPlan.Limits limits = new BuildPlan.Limits(
                config.buildMaxOperations(), config.buildMaxBlocks(), config.buildMaxVolume());
        // 优先按约定的 JSON 解析；模型没给 JSON 时退化成「一行一条指令」
        JsonObject json = AiClient.extractJsonObject(content);
        BuildPlan plan = json != null
                ? BuildPlan.parse(json, origin.toOpPos(), limits)
                : BuildPlan.parseText(content, origin.toOpPos(), limits);
        if (!plan.success() && json != null) {
            // JSON 抠出来了但一条指令都解析不出来（数组被写坏、字段名不对……）：
            // 整段正文再按行扫一遍，往往还能救回真正的指令。
            BuildPlan fallback = BuildPlan.parseText(content, origin.toOpPos(), limits);
            if (fallback.success()) {
                plugin.getLogger().info("AI 返回的 JSON 无法使用（" + plan.failureReason()
                        + "），已按行降级解析出 " + fallback.ops().size() + " 条指令");
                plan = fallback;
            }
        }
        if (!plan.success()) {
            startCooldown(id);
            reportUnparsable(config, player, plan);
            send(player, config.message("build-failed", "reason", plan.failureReason()));
            return;
        }

        startCooldown(id);

        if (player == null || !player.isOnline()) {
            plugin.getLogger().info("玩家 " + id + " 已离线，AI 建筑「" + plan.name() + "」未施工");
            return;
        }

        player.sendMessage(config.message("build-planned",
                "name", plan.name(),
                "ops", Integer.toString(plan.ops().size()),
                "blocks", Long.toString(plan.estimatedBlocks())));
        if (plan.skipped() > 0) {
            player.sendMessage(config.message("build-skipped", "count", Integer.toString(plan.skipped())));
        }
        if (plan.truncated()) {
            player.sendMessage(config.message("build-truncated", "limit", Long.toString(config.buildMaxBlocks())));
        }
        if (config.debug()) {
            for (BuildOp op : plan.ops()) {
                plugin.getLogger().info("[debug] build op: " + op.describe());
            }
        }

        BuildSession session = new BuildSession(plugin, origin.world(), plan,
                finished -> onBuildFinished(id, finished));
        active.put(id, session);
        session.start();
    }

    /**
     * 一条指令都没解析出来时，把「模型到底写了什么」留下来。
     *
     * <p>玩家看到的是一句笼统的「指令全部无法解析」，而真正的原因（模型写成了散文、
     * 用了不存在的方块、JSON 被截断……）只有拿到原文才能判断，所以这里在
     * {@code debug: true} 时把原文与逐行失败原因打进控制台。</p>
     *
     * @param config 配置
     * @param player 玩家（可能为 null）
     * @param plan   失败方案
     */
    private void reportUnparsable(PluginConfig config, Player player, BuildPlan plan) {
        if (!config.debug()) {
            return;
        }
        plugin.getLogger().warning("[debug] 建造指令全部无法解析，模型返回的正文如下：");
        for (String line : plan.rawLines()) {
            plugin.getLogger().warning("[debug]   原文: " + abbreviate(line, 200));
        }
        for (String detail : plan.skippedDetails()) {
            plugin.getLogger().warning("[debug]   丢弃: " + detail);
        }
        if (player != null) {
            player.sendMessage(config.message("build-unparsable-hint"));
        }
    }

    // ------------------------------------------------------------------
    // 思考链转达
    // ------------------------------------------------------------------

    /** 把模型的思维链实时转达给玩家时的限流状态。 */
    private static final class ThinkingStreamState {

        private final AtomicLong lastSendNanos = new AtomicLong();
        private final AtomicInteger chunks = new AtomicInteger();
        private final AtomicBoolean sent = new AtomicBoolean();

        private ThinkingStreamState(long startedNanos) {
            this.lastSendNanos.set(startedNanos);
        }

        /** @return 已经转达过至少一条思考内容 */
        private boolean sent() {
            return sent.get();
        }

        /** @return 已经转达了多少条 */
        private int chunks() {
            return chunks.get();
        }
    }

    /**
     * 造一个把思考链转达给玩家的钩子。
     *
     * <p>回调发生在 AI 的读取线程上，所以这里只做两件事：更新限流状态、把
     * 「格式化 + 发送」用 {@code runTask} 丢回主线程。加了间隔与长度两道限制，
     * 模型按 token 吐字也不会把聊天框刷爆。</p>
     *
     * @param playerId      玩家 UUID
     * @param playerName    玩家名（日志用）
     * @param startedNanos  请求开始时间（算「思考了多少秒」）
     * @return 钩子
     */
    private AiStreamHook newThinkingHook(UUID playerId, String playerName, long startedNanos) {
        return new AiStreamHook() {

            private final StringBuilder reasoning = new StringBuilder();

            @Override
            public void onReasoning(String text) {
                if (text == null || text.isEmpty()) {
                    return;
                }
                reasoning.append(text);
                ThinkingStreamState state = thinking.get(playerId);
                if (state == null) {
                    return;
                }
                PluginConfig config = plugin.pluginConfig();
                long now = System.nanoTime();
                long intervalNanos = config.buildThinkingIntervalMs() * 1_000_000L;
                if (now - state.lastSendNanos.get() < intervalNanos) {
                    return;
                }
                // 这里可能被同一线程的后续回调再次命中：先占位再发送，最多多丢一条，不会刷屏。
                state.lastSendNanos.set(now);
                String chunk = tail(reasoning.toString().replace('\n', ' ').strip(),
                        config.buildThinkingChunkChars());
                if (chunk.isEmpty()) {
                    return;
                }
                int seconds = (int) ((now - startedNanos) / 1_000_000_000L);
                int count = state.chunks.incrementAndGet();
                state.sent.set(true);
                if (config.debug()) {
                    // 控制台也留一份：模型「设计跑偏」时，管理员不必进游戏看聊天框
                    plugin.getLogger().info("[debug] 思考链（" + playerName + " " + seconds + "s）：" + chunk);
                }
                runOnMainThread(() -> send(Bukkit.getPlayer(playerId),
                        thinkingMessage(config, seconds, count, chunk)));
            }

            @Override
            public void onThinkingDone() {
                // 收尾由提交处统一处理，避免重试 / 异常路径各发一条
            }
        };
    }

    /**
     * 构造一条思考消息。
     *
     * <p>{@code {text}} 直接替换、不做二次转义：模型怎么写的就怎么显示（{@code &}
     * 会被 {@link Text} 当颜色代码处理，这在原版聊天里是可接受的，总比丢字符好），
     * 这里也不用 {@code String.format}，所以 {@code %} 之类不会出问题。</p>
     *
     * @param config  配置
     * @param seconds 已经思考的秒数
     * @param count   第几条
     * @param chunk   思考链片段
     * @return 组件
     */
    private static Component thinkingMessage(PluginConfig config, int seconds, int count, String chunk) {
        return Text.component(config.rawMessage("prefix")
                + config.format("build-thinking-stream",
                "seconds", Integer.toString(seconds),
                "count", Integer.toString(count),
                "text", chunk));
    }

    /** 取字符串的尾巴（最多 {@code max} 个字符），用于「只显示最新在想什么」。 */
    private static String tail(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : "…" + text.substring(text.length() - max);
    }

    /** 日志用的短文本。 */
    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    /** 一次建造结束（成功跑完或被取消）。 */
    private void onBuildFinished(UUID id, BuildSession session) {
        active.remove(id);
        if (!plugin.isEnabled()) {
            return;
        }
        if (!session.undo().isEmpty()) {
            pushUndo(id, new UndoSnapshot(session.world(), session.name(),
                    List.copyOf(session.undo()), session.undoComplete(), System.currentTimeMillis()));
        }

        PluginConfig config = plugin.pluginConfig();
        Player player = Bukkit.getPlayer(id);
        send(player, config.message("build-done",
                "name", session.name(),
                "blocks", Integer.toString(session.placed()),
                "skipped", Integer.toString(session.skipped())));
        if (session.outOfRange() > 0) {
            send(player, config.message("build-out-of-range", "count", Integer.toString(session.outOfRange())));
        }
        if (!session.undoComplete()) {
            send(player, config.message("build-undo-incomplete"));
        }
    }

    // ------------------------------------------------------------------
    // 撤销
    // ------------------------------------------------------------------

    /**
     * 撤销最近若干次 AI 建造。
     *
     * @param player 玩家
     * @param count  撤销几次（至少 1）
     * @return 是否真的开始了撤销
     */
    public boolean undo(Player player, int count) {
        PluginConfig config = plugin.pluginConfig();
        UUID id = player.getUniqueId();
        if (active.containsKey(id) || awaitingAi.contains(id)) {
            player.sendMessage(config.message("build-busy"));
            return false;
        }
        Deque<UndoSnapshot> history = undoHistory.get(id);
        if (history == null || history.isEmpty()) {
            player.sendMessage(config.message("build-undo-empty"));
            return false;
        }

        List<UndoSnapshot> taken = new ArrayList<>();
        for (int i = 0; i < Math.max(1, count) && !history.isEmpty(); i++) {
            taken.add(history.pop());
        }

        // 还原顺序：新的建造先还原，同一次建造内部按记录的反序还原
        List<UndoSnapshot.BlockSnapshot> blocks = new ArrayList<>();
        boolean complete = true;
        for (UndoSnapshot snapshot : taken) {
            complete &= snapshot.complete();
            List<UndoSnapshot.BlockSnapshot> list = snapshot.blocks();
            for (int i = list.size() - 1; i >= 0; i--) {
                blocks.add(list.get(i));
            }
        }
        if (blocks.isEmpty()) {
            player.sendMessage(config.message("build-undo-empty"));
            return false;
        }

        World world = taken.get(0).world();
        player.sendMessage(config.message("build-undo-start", "blocks", Integer.toString(blocks.size())));
        if (!complete) {
            player.sendMessage(config.message("build-undo-incomplete"));
        }

        RestoreSession[] holder = new RestoreSession[1];
        RestoreSession session = new RestoreSession(plugin, world, blocks, finished -> {
            restores.remove(holder[0]);
            send(Bukkit.getPlayer(id), config.message("build-undo-done",
                    "blocks", Integer.toString(finished.restored())));
        });
        holder[0] = session;
        restores.add(session);
        session.start();
        return true;
    }

    private void pushUndo(UUID id, UndoSnapshot snapshot) {
        Deque<UndoSnapshot> history = undoHistory.computeIfAbsent(id, key -> new ArrayDeque<>());
        history.push(snapshot);
        int limit = Math.max(1, plugin.pluginConfig().buildUndoHistory());
        while (history.size() > limit) {
            history.removeLast();
        }
    }

    // ------------------------------------------------------------------
    // 取消 / 关闭
    // ------------------------------------------------------------------

    /**
     * 取消玩家正在进行的建造（已放置的方块保留，可用 undo 还原）。
     *
     * @param player 玩家
     * @return 是否取消到了东西
     */
    public boolean cancel(Player player) {
        PluginConfig config = plugin.pluginConfig();
        UUID id = player.getUniqueId();
        BuildSession session = active.remove(id);
        if (session == null) {
            if (awaitingAi.remove(id)) {
                player.sendMessage(config.message("build-cancelled"));
                return true;
            }
            player.sendMessage(config.message("build-nothing-to-cancel"));
            return false;
        }
        player.sendMessage(config.message("build-cancelled"));
        session.cancel();
        // 主动收尾，让已经放置的部分照常进入撤销历史
        try {
            Bukkit.getScheduler().runTask(plugin, () -> onBuildFinished(id, session));
        } catch (RuntimeException ex) {
            onBuildFinished(id, session);
        }
        return true;
    }

    /** 插件卸载：停掉所有还在跑的施工 / 还原任务。 */
    public void shutdown() {
        for (BuildSession session : active.values()) {
            session.cancel();
        }
        active.clear();
        for (RestoreSession session : restores) {
            session.cancel();
        }
        restores.clear();
        awaitingAi.clear();
        thinking.clear();
        cooldowns.clear();
    }

    /**
     * 玩家退出时清理冷却（撤销历史保留，重进后仍可撤销）。
     *
     * @param id 玩家 UUID
     */
    public void forget(UUID id) {
        cooldowns.remove(id);
        thinking.remove(id);
    }

    // ------------------------------------------------------------------
    // 冷却
    // ------------------------------------------------------------------

    /**
     * 查询剩余建造冷却秒数（向上取整）。
     *
     * @param id 玩家 UUID
     * @return 剩余秒数，无冷却返回 0
     */
    public long remainingCooldownSeconds(UUID id) {
        Long until = cooldowns.get(id);
        if (until == null) {
            return 0L;
        }
        long millis = until - System.currentTimeMillis();
        if (millis <= 0L) {
            cooldowns.remove(id);
            return 0L;
        }
        return (millis + 999L) / 1000L;
    }

    private void startCooldown(UUID id) {
        int seconds = plugin.pluginConfig().buildCooldownSeconds();
        if (seconds <= 0) {
            return;
        }
        cooldowns.put(id, System.currentTimeMillis() + seconds * 1000L);
    }

    // ------------------------------------------------------------------
    // 原点 / 请求体 / 提示词
    // ------------------------------------------------------------------

    /**
     * 解析建造原点：优先取玩家准星命中的方块表面，否则取身前 4 格。
     *
     * @param player 玩家
     * @return 原点
     */
    private BuildPos resolveOrigin(Player player) {
        World world = player.getWorld();
        RayTraceResult hit = player.rayTraceBlocks(plugin.pluginConfig().buildMaxDistance());
        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            Block target = hit.getHitBlock().getRelative(hit.getHitBlockFace());
            return new BuildPos(world, target.getX(), target.getY(), target.getZ());
        }

        Location location = player.getLocation();
        Vector direction = location.getDirection().setY(0);
        if (direction.lengthSquared() < 1.0E-4D) {
            direction = new Vector(0, 0, 1);
        }
        direction.normalize().multiply(4);
        int x = location.getBlockX() + (int) Math.round(direction.getX());
        int z = location.getBlockZ() + (int) Math.round(direction.getZ());
        int y = Math.min(world.getMaxHeight() - 1, Math.max(world.getMinHeight(), location.getBlockY()));
        return new BuildPos(world, x, y, z);
    }

    private static String facingOf(Player player) {
        float yaw = player.getLocation().getYaw() % 360.0F;
        if (yaw < 0) {
            yaw += 360.0F;
        }
        if (yaw < 45.0F || yaw >= 315.0F) {
            return "south(+Z)";
        }
        if (yaw < 135.0F) {
            return "west(-X)";
        }
        if (yaw < 225.0F) {
            return "north(-Z)";
        }
        return "east(+X)";
    }

    /** 构造发给 AI 的 user message。 */
    private JsonObject buildPayload(Player player, String request, BuildPos origin) {
        PluginConfig config = plugin.pluginConfig();
        World world = origin.world();

        JsonObject root = new JsonObject();
        root.addProperty("request", request);

        JsonObject originJson = new JsonObject();
        originJson.addProperty("x", origin.x());
        originJson.addProperty("y", origin.y());
        originJson.addProperty("z", origin.z());
        root.add("origin", originJson);

        root.addProperty("player_facing", facingOf(player));
        root.addProperty("player_yaw", Math.round(player.getLocation().getYaw()));

        JsonObject worldJson = new JsonObject();
        worldJson.addProperty("name", world.getName());
        worldJson.addProperty("min_height", world.getMinHeight());
        worldJson.addProperty("max_height", world.getMaxHeight());
        if (origin.y() - 1 >= world.getMinHeight()) {
            BlockData ground = world.getBlockData(origin.x(), origin.y() - 1, origin.z());
            worldJson.addProperty("ground_block", ground.getAsString());
        }
        root.add("world", worldJson);

        JsonObject limits = new JsonObject();
        limits.addProperty("max_blocks", config.buildMaxBlocks());
        limits.addProperty("max_operations", config.buildMaxOperations());
        root.add("limits", limits);
        return root;
    }

    /** 读取建造系统提示词：config.yml -> resources/build-prompt.txt -> 内置兜底。 */
    private String loadPrompt() {
        String fromConfig = plugin.pluginConfig().buildSystemPrompt();
        if (!fromConfig.isBlank()) {
            return fromConfig;
        }
        try (InputStream in = plugin.getResource("build-prompt.txt")) {
            if (in == null) {
                return FALLBACK_PROMPT;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? FALLBACK_PROMPT : text;
        } catch (IOException ex) {
            plugin.getLogger().warning("读取 build-prompt.txt 失败，改用内置提示词：" + ex.getMessage());
            return FALLBACK_PROMPT;
        }
    }

    private static void send(Player player, Component message) {
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }
}
