package com.hongshikaikai.aicraft.config;

import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * config.yml 的类型化视图。
 *
 * <p>所有取值都带默认值：即使服务器管理员删掉了某一项，插件也不会因为
 * 拿到 null 而抛异常。</p>
 */
public final class PluginConfig {

    /** 内置默认消息：config.yml 缺项时兜底。 */
    private static final Map<String, String> DEFAULT_MESSAGES = new LinkedHashMap<>();

    static {
        DEFAULT_MESSAGES.put("prefix", "&8[&bAICraft&8] &r");
        DEFAULT_MESSAGES.put("crafting", "&eAI 正在思考合成结果...");
        DEFAULT_MESSAGES.put("success", "&a合成成功：&f{item_name}");
        DEFAULT_MESSAGES.put("failure", "&c合成失败：{reason}");
        DEFAULT_MESSAGES.put("empty", "&c合成区域不能为空");
        DEFAULT_MESSAGES.put("amount", "&c合成材料每格数量必须为 1，已返还材料");
        DEFAULT_MESSAGES.put("no-response", "&cAI 无响应，已返还材料，请稍后重试");
        DEFAULT_MESSAGES.put("inventory-full", "&e背包已满，多余的物品已掉落在你脚下");
        DEFAULT_MESSAGES.put("returned", "&7合成区域内的物品已返还");
        DEFAULT_MESSAGES.put("busy", "&e上一次合成还在进行中，请稍候");
        DEFAULT_MESSAGES.put("reloaded", "&a配置已重载");
        DEFAULT_MESSAGES.put("no-permission", "&c你没有权限使用该命令");
        DEFAULT_MESSAGES.put("players-only", "&c该命令只能由玩家执行");

        // ---- /aibuild ----
        DEFAULT_MESSAGES.put("build-usage",
                "&e/aibuild <描述> &7让 AI 建造 &8| &e/aibuild undo [次数] &8| &e/aibuild cancel");
        DEFAULT_MESSAGES.put("build-disabled", "&cAI 建造功能已在配置中关闭");
        DEFAULT_MESSAGES.put("build-thinking", "&eAI 正在设计建筑，请稍候...");
        DEFAULT_MESSAGES.put("build-thinking-stream", "&7💭 &8[&7思考中 {seconds}s&8] &7{text}");
        DEFAULT_MESSAGES.put("build-thinking-done", "&7AI 思考完毕，正在整理建造指令...");
        DEFAULT_MESSAGES.put("build-busy", "&e上一次建造还在进行中，请稍候");
        DEFAULT_MESSAGES.put("build-cooldown", "&c建造冷却中，剩余 {seconds} 秒");
        DEFAULT_MESSAGES.put("build-planned",
                "&aAI 设计了「&f{name}&a」：{ops} 条指令 / 约 {blocks} 个方块，开始施工");
        DEFAULT_MESSAGES.put("build-skipped", "&7已跳过 {count} 条无法识别的建造指令");
        DEFAULT_MESSAGES.put("build-truncated", "&e建筑规模超出上限（{limit} 方块），已按上限截断");
        DEFAULT_MESSAGES.put("build-failed", "&c建造失败：{reason}");
        DEFAULT_MESSAGES.put("build-unparsable-hint",
                "&7提示：把需求写得更具体一点；服务器控制台开 debug: true 可以看到 AI 的原文。");
        DEFAULT_MESSAGES.put("build-timeout",
                "&cAI 在 {seconds} 秒内没设计完这个建筑。把描述改简单些，或调大 build.timeout-seconds / 调小 build.max-tokens");
        DEFAULT_MESSAGES.put("build-no-content",
                "&cAI 光顾着想、没写出结果（免费车道的模型会把生成预算花在思考上）。把建筑描述改简单些再试");
        DEFAULT_MESSAGES.put("build-done", "&a建筑「&f{name}&a」已完工：放置 {blocks} 个方块（跳过 {skipped}）");
        DEFAULT_MESSAGES.put("build-out-of-range", "&7有 {count} 个方块超出世界高度，已跳过");
        DEFAULT_MESSAGES.put("build-cancelled", "&7已取消当前建造，可用 /aibuild undo 还原已放置的部分");
        DEFAULT_MESSAGES.put("build-nothing-to-cancel", "&7当前没有正在进行的建造");
        DEFAULT_MESSAGES.put("build-undo-start", "&e正在还原 {blocks} 个方块...");
        DEFAULT_MESSAGES.put("build-undo-done", "&a已撤销建造，还原 {blocks} 个方块");
        DEFAULT_MESSAGES.put("build-undo-empty", "&c没有可撤销的建造记录");
        DEFAULT_MESSAGES.put("build-undo-incomplete", "&e该次建造的撤销记录不完整，还原结果可能不完美");
    }

    private final String apiBaseUrl;
    private final String apiPath;
    private final String apiKey;
    private final String apiModel;
    private final String apiUserAgent;
    private final String apiClient;
    private final String apiProject;
    private final int apiTimeoutSeconds;
    private final int apiIdleTimeoutSeconds;
    private final int apiMaxAttempts;
    private final long apiRetryBackoffMs;
    private final boolean apiToolFingerprint;
    private final int apiMaxTokens;
    private final double apiTemperature;
    private final String apiSystemPrompt;

    private final String guiTitle;

    private final boolean customEnchantmentsShowInLore;
    private final String customEnchantmentsLoreFormat;
    private final boolean customEnchantmentsStoreInPdc;

    private final boolean debug;

    private final boolean buildEnabled;
    private final int buildCooldownSeconds;
    private final int buildMaxDistance;
    private final int buildMaxOperations;
    private final long buildMaxBlocks;
    private final long buildMaxVolume;
    private final int buildBlocksPerTick;
    private final boolean buildApplyPhysics;
    private final int buildMaxTokens;
    private final int buildTimeoutSeconds;
    private final String buildModel;
    private final boolean buildStreamThinking;
    private final int buildThinkingIntervalMs;
    private final int buildThinkingChunkChars;
    private final int buildUndoHistory;
    private final int buildUndoMaxBlocks;
    private final String buildSystemPrompt;
    private final Set<Material> buildBlacklist;

    private final Map<String, String> messages;

    private PluginConfig(FileConfiguration cfg) {
        this.apiBaseUrl = trimTrailingSlash(cfg.getString("api.base-url", "https://opencode.ai"));
        this.apiPath = ensureLeadingSlash(cfg.getString("api.path", "/zen/v1/chat/completions"));
        this.apiKey = cfg.getString("api.key", "public");
        this.apiModel = cfg.getString("api.model", "mimo-v2.6-flash-free");
        this.apiUserAgent = cfg.getString("api.user-agent", "opencode/1.18.31");
        this.apiClient = cfg.getString("api.client", "desktop");
        this.apiProject = cfg.getString("api.project", "global");
        this.apiTimeoutSeconds = Math.max(5, cfg.getInt("api.timeout-seconds", 90));
        this.apiIdleTimeoutSeconds = Math.max(5, cfg.getInt("api.idle-timeout-seconds", 30));
        this.apiMaxAttempts = Math.max(1, cfg.getInt("api.max-attempts", 3));
        this.apiRetryBackoffMs = Math.max(0L, cfg.getLong("api.retry-backoff-ms", 800L));
        this.apiToolFingerprint = cfg.getBoolean("api.tool-fingerprint", true);
        this.apiMaxTokens = Math.max(64, cfg.getInt("api.max-tokens", 2048));
        this.apiTemperature = cfg.getDouble("api.temperature", 0.8D);
        this.apiSystemPrompt = cfg.getString("api.system-prompt", "");

        this.guiTitle = cfg.getString("gui.title", "AI合成器");

        this.customEnchantmentsShowInLore = cfg.getBoolean("craft.custom-enchantments.show-in-lore", true);
        this.customEnchantmentsLoreFormat = cfg.getString("craft.custom-enchantments.lore-format",
                "&7✦ &d{name} &7{level}");
        this.customEnchantmentsStoreInPdc = cfg.getBoolean("craft.custom-enchantments.store-in-pdc", true);

        this.debug = cfg.getBoolean("debug", false);

        this.buildEnabled = cfg.getBoolean("build.enabled", true);
        this.buildCooldownSeconds = Math.max(0, cfg.getInt("build.cooldown-seconds", 60));
        this.buildMaxDistance = Math.max(5, cfg.getInt("build.max-distance", 40));
        this.buildMaxOperations = Math.max(1, cfg.getInt("build.max-operations", 400));
        this.buildMaxBlocks = Math.max(1L, cfg.getLong("build.max-blocks", 120000L));
        this.buildMaxVolume = Math.max(1L, cfg.getLong("build.max-volume", 200000L));
        this.buildBlocksPerTick = Math.max(1, cfg.getInt("build.blocks-per-tick", 8192));
        this.buildApplyPhysics = cfg.getBoolean("build.apply-physics", false);
        this.buildMaxTokens = Math.max(256, cfg.getInt("build.max-tokens", 8192));
        this.buildTimeoutSeconds = Math.max(5, cfg.getInt("build.timeout-seconds", 360));
        // 建造单独一个模型：合成只要一小段 JSON，建造却要模型先把设计想完再写指令，
        // 两者对「模型爱不爱把预算花在思维链上」的敏感度差得很远（实测见 README §4）。
        // 留空表示沿用 api.model。
        String configuredBuildModel = cfg.getString("build.model", "nemotron-3.5-lightning-free");
        this.buildModel = configuredBuildModel == null || configuredBuildModel.isBlank()
                ? apiModel
                : configuredBuildModel.trim();
        // 建造时把模型的思考链实时转达给玩家：免费车道的模型可能先想几分钟才动笔，
        // 让玩家看得见「它在想什么」既是体验，也是排查「AI 设计跑偏」的唯一窗口。
        this.buildStreamThinking = cfg.getBoolean("build.stream-thinking", true);
        this.buildThinkingIntervalMs = Math.max(250, cfg.getInt("build.thinking.interval-ms", 1500));
        this.buildThinkingChunkChars = Math.max(16, cfg.getInt("build.thinking.chunk-chars", 96));
        this.buildUndoHistory = Math.max(1, cfg.getInt("build.undo.history", 3));
        this.buildUndoMaxBlocks = Math.max(1, cfg.getInt("build.undo.max-blocks", 80000));
        this.buildSystemPrompt = cfg.getString("build.system-prompt", "");
        this.buildBlacklist = parseMaterials(cfg.getStringList("build.blacklist"));

        Map<String, String> loaded = new LinkedHashMap<>(DEFAULT_MESSAGES);
        ConfigurationSection section = cfg.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    loaded.put(key, value);
                }
            }
        }
        this.messages = Map.copyOf(loaded);
    }

    /**
     * 从插件主类读取配置。
     *
     * @param plugin 插件实例
     * @return 类型化配置对象
     */
    public static PluginConfig load(AICraft plugin) {
        return new PluginConfig(plugin.getConfig());
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String ensureLeadingSlash(String value) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty()) {
            return "/";
        }
        return out.startsWith("/") ? out : "/" + out;
    }

    /**
     * 把配置里的方块名列表解析成 {@link Material} 集合。
     *
     * @param names 方块名（大小写不敏感，支持 {@code minecraft:} 前缀）
     * @return 材质集合，非法名字会被忽略
     */
    private static Set<Material> parseMaterials(List<String> names) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        if (names == null) {
            return out;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(name.trim());
            if (material != null && material.isBlock()) {
                out.add(material);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    /**
     * 取一条消息的原始文本（带 {@code &} 颜色代码）。
     *
     * @param key 消息键
     * @return 原始文本
     */
    public String rawMessage(String key) {
        return messages.getOrDefault(key, key);
    }

    /**
     * 格式化一条消息（替换占位符，未翻译颜色代码）。
     *
     * @param key          消息键
     * @param placeholders 占位符键值对：{@code "item_name", "钻石剑", "seconds", "12"}
     * @return 替换后的原始文本
     */
    public String format(String key, String... placeholders) {
        String out = rawMessage(key);
        if (placeholders != null) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                String name = placeholders[i];
                String value = placeholders[i + 1];
                out = out.replace("{" + name + "}", value == null ? "" : value);
            }
        }
        return out;
    }

    /**
     * 格式化一条消息并带上前缀，转成 Component。
     *
     * @param key          消息键
     * @param placeholders 占位符键值对
     * @return 可直接发送给玩家的组件
     */
    public Component message(String key, String... placeholders) {
        return Text.component(rawMessage("prefix") + format(key, placeholders));
    }

    /** @return 界面标题组件 */
    public Component guiTitleComponent() {
        return Text.component(guiTitle);
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    public String apiPath() {
        return apiPath;
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiModel() {
        return apiModel;
    }

    public String apiUserAgent() {
        return apiUserAgent;
    }

    public String apiClient() {
        return apiClient;
    }

    public String apiProject() {
        return apiProject;
    }

    /** @return 单次合成请求的总时长红线（秒） */
    public int apiTimeoutSeconds() {
        return apiTimeoutSeconds;
    }

    /** @return 闲置红线（秒）：连续这么久收不到新数据就判超时；只要还在出数据就不会被判死 */
    public int apiIdleTimeoutSeconds() {
        return apiIdleTimeoutSeconds;
    }

    public int apiMaxAttempts() {
        return apiMaxAttempts;
    }

    public long apiRetryBackoffMs() {
        return apiRetryBackoffMs;
    }

    public boolean apiToolFingerprint() {
        return apiToolFingerprint;
    }

    public int apiMaxTokens() {
        return apiMaxTokens;
    }

    public double apiTemperature() {
        return apiTemperature;
    }

    /** @return config.yml 里自定义的系统提示词，为空表示改用 resources/prompt.txt */
    public String apiSystemPrompt() {
        return apiSystemPrompt == null ? "" : apiSystemPrompt;
    }

    /** @return 自创附魔是否以 Lore 形式显示在物品上 */
    public boolean customEnchantmentsShowInLore() {
        return customEnchantmentsShowInLore;
    }

    /** @return 自创附魔的 Lore 格式，支持 {name} / {level} 占位符 */
    public String customEnchantmentsLoreFormat() {
        return customEnchantmentsLoreFormat;
    }

    /** @return 是否把自创附魔写入物品持久化数据（aicraft:custom_enchantments） */
    public boolean customEnchantmentsStoreInPdc() {
        return customEnchantmentsStoreInPdc;
    }

    public boolean debug() {
        return debug;
    }

    // ------------------------------------------------------------------
    // /aibuild
    // ------------------------------------------------------------------

    /** @return 是否启用 /aibuild */
    public boolean buildEnabled() {
        return buildEnabled;
    }

    /** @return 每位玩家的建造冷却（秒） */
    public int buildCooldownSeconds() {
        return buildCooldownSeconds;
    }

    /** @return 视线追踪建造原点的最大距离（格） */
    public int buildMaxDistance() {
        return buildMaxDistance;
    }

    /** @return 单次建造最多指令条数 */
    public int buildMaxOperations() {
        return buildMaxOperations;
    }

    /** @return 单次建造总方块上限 */
    public long buildMaxBlocks() {
        return buildMaxBlocks;
    }

    /** @return 单条指令方块上限 */
    public long buildMaxVolume() {
        return buildMaxVolume;
    }

    /** @return 每 tick 最多放置的方块数 */
    public int buildBlocksPerTick() {
        return buildBlocksPerTick;
    }

    /** @return 放置方块时是否触发方块物理 */
    public boolean buildApplyPhysics() {
        return buildApplyPhysics;
    }

    /** @return 建造请求的生成 token 上限 */
    public int buildMaxTokens() {
        return buildMaxTokens;
    }

    /** @return 单次建造请求的总时长红线（秒），比合成的更宽 */
    public int buildTimeoutSeconds() {
        return buildTimeoutSeconds;
    }

    /** @return 建造请求使用的模型；config.yml 里留空则等于 {@link #apiModel()} */
    public String buildModel() {
        return buildModel;
    }

    /** @return 建造时是否把模型的思考链实时转达给玩家 */
    public boolean buildStreamThinking() {
        return buildStreamThinking;
    }

    /** @return 思考链转达的最小间隔（毫秒），避免逐 token 刷屏 */
    public int buildThinkingIntervalMs() {
        return buildThinkingIntervalMs;
    }

    /** @return 每条思考消息最多展示多少个字符（取最新的尾巴） */
    public int buildThinkingChunkChars() {
        return buildThinkingChunkChars;
    }

    /** @return 每位玩家保留的撤销次数 */
    public int buildUndoHistory() {
        return buildUndoHistory;
    }

    /** @return 单次建造最多记录多少方块用于撤销 */
    public int buildUndoMaxBlocks() {
        return buildUndoMaxBlocks;
    }

    /** @return config.yml 里自定义的建造提示词，为空表示用 resources/build-prompt.txt */
    public String buildSystemPrompt() {
        return buildSystemPrompt == null ? "" : buildSystemPrompt;
    }

    /** @return 不允许被 AI 覆盖的方块集合 */
    public Set<Material> buildBlacklist() {
        return buildBlacklist;
    }
}
