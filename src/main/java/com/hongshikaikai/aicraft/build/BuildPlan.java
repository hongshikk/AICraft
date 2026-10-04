package com.hongshikaikai.aicraft.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI 返回的建造方案。
 *
 * <p>AI 需要输出这样的 JSON（严格 JSON，不要 Markdown 围栏）。指令是自描述的 op 对象，
 * 坐标是相对原点的整数三元组，不写 {@code ~}：</p>
 * <pre>
 * {
 *   "success": true,
 *   "name": "角斗场",
 *   "summary": "椭圆形石造看台 + 沙地竞技场",
 *   "ops": [
 *     { "op": "fill",   "from": [-14, 0, -11], "to": [14, 0, 11], "block": "stone_bricks" },
 *     { "op": "walls",  "from": [-14, 1, -11], "to": [14, 3, 11], "block": "stone_bricks" },
 *     { "op": "sphere", "at": [0, 5, 0], "radius": 2, "block": "gold_block", "hollow": true }
 *   ]
 * }
 * </pre>
 *
 * <h2>为什么改成结构化 JSON</h2>
 * <p>旧协议让模型在 {@code commands} 里写一整行文本指令（{@code "fill ~-8 ~0 ~-8 …"}），
 * 实测里失败率很高：模型会漏写 {@code ~}、把坐标串成一行散文、写成函数调用
 * （{@code fill(-8,0,-8, 8,0,8, stone)}）、或干脆不写 commands 只写一段推演。
 * 现在改成「一个对象一条 op、坐标是数字数组」，模型只需要填字段，不再需要记住
 * 指令文的标点与顺序。{@link OpJson} 负责把对象翻成规范指令文本，
 * 并兼容字段名 / 坐标写法的各种漂移。</p>
 *
 * <p>旧格式仍然全面兼容：字符串指令数组、{@code commands} 字段名、以及完全没有 JSON、
 * 正文就是一行行指令的情况（{@link #parseText}）。一条都救不回来时，
 * {@link #rawLines()} 与 {@link #skippedDetails()} 会留下完整证据链，
 * 便于在控制台定位到底是模型的问题还是解析的问题。</p>
 *
 * <p>解析是「尽力而为」的：单条指令写错只会计入 {@link #skipped()} 并跳过，
 * 不会让整次建造失败；但一条都解析不出来时返回失败。规模超过配置上限时按上限截断
 * （{@link #truncated()}）。</p>
 */
public final class BuildPlan {

    /**
     * 规模限制。
     *
     * @param maxOperations 最多指令条数
     * @param maxBlocks     单次建造最多方块数（估算）
     * @param maxVolume     单条指令最多方块数
     */
    public record Limits(int maxOperations, long maxBlocks, long maxVolume) {
    }

    /** 单条原文最多保留多少字符（用于日志，避免把几 MB 的推演全塞进内存）。 */
    private static final int MAX_RAW_LINE_CHARS = 400;

    /** 最多保留多少条原文（够定位问题即可）。 */
    private static final int MAX_RAW_LINES = 40;

    private final String name;
    private final String summary;
    private final List<BuildOp> ops;
    private final int skipped;
    private final boolean truncated;
    private final String failureReason;
    private final List<String> rawLines;
    private final List<String> skippedDetails;

    /** 文本降级解析时认可的指令首词。 */
    private static final String[] COMMAND_NAMES = {
            "fill", "setblock", "set", "hollow", "shell", "walls", "wall", "line",
            "sphere", "ball", "cylinder", "cyl", "replace", "sign"
    };

    /**
     * 把「几条指令挤在同一行」拆开的软分隔符。
     *
     * <p>需要「分隔符 + 空白 + 已知指令首词」同时成立才拆，避免把
     * {@code oak_log[axis=y]} 这类正常内容误伤。</p>
     */
    private static final Pattern MULTI_COMMAND = Pattern.compile(
            "[,;；，]\\s+(?=(?i:" + String.join("|", COMMAND_NAMES) + ")\\s)");

    private BuildPlan(String name, String summary, List<BuildOp> ops, int skipped,
                      boolean truncated, String failureReason,
                      List<String> rawLines, List<String> skippedDetails) {
        this.name = name;
        this.summary = summary;
        this.ops = ops;
        this.skipped = skipped;
        this.truncated = truncated;
        this.failureReason = failureReason;
        this.rawLines = rawLines;
        this.skippedDetails = skippedDetails;
    }

    /**
     * 构造一个失败方案。
     *
     * @param reason 失败原因（会直接展示给玩家）
     * @return 方案
     */
    public static BuildPlan failure(String reason) {
        return new BuildPlan(null, null, List.of(), 0, false, reason, List.of(), List.of());
    }

    /**
     * 解析 AI 返回的 JSON。
     *
     * <p>优先读结构化 {@code ops}（对象数组，见类注释），同时兼容旧的字符串指令数组；
     * {@code success} 只有写成明确的假值才算失败。</p>
     *
     * @param json   AI 返回的根对象
     * @param origin 建造原点（{@code ~} 的基准）
     * @param limits 规模限制
     * @return 方案；{@link #success()} 为 false 时看 {@link #failureReason()}
     */
    public static BuildPlan parse(JsonObject json, BuildOp.Pos origin, Limits limits) {
        if (json == null) {
            return failure("AI 没有返回任何内容");
        }

        if (OpJson.isExplicitFailure(json)) {
            return failure(optString(json, "reason", "AI 拒绝了这次建造请求"));
        }

        List<String> lines = OpJson.collect(json);
        if (lines.isEmpty()) {
            return failure("AI 没有给出任何建造指令");
        }
        String name = OpJson.findString(json, "name");
        String summary = OpJson.findString(json, "summary");
        return build(name == null || name.isBlank() ? "AI 建筑" : name,
                summary == null ? "" : summary, lines, origin, limits);
    }

    /**
     * 降级解析：模型没有输出 JSON、而是直接输出一行行建造指令时走这里。
     *
     * <p>会剥掉 Markdown 代码围栏、列表符号、行号、开头的 {@code /}，
     * 以及「无数组包裹的 JSON 字符串」留下的引号与尾逗号；
     * 不像指令的行（例如说明文字）会被整行忽略，不计入 {@link #skipped()}。</p>
     *
     * @param content 模型正文
     * @param origin  建造原点
     * @param limits  规模限制
     * @return 方案
     */
    public static BuildPlan parseText(String content, BuildOp.Pos origin, Limits limits) {
        if (content == null || content.isBlank()) {
            return failure("AI 没有返回任何内容");
        }
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\r?\n")) {
            String line = cleanLine(raw);
            if (line == null) {
                continue;
            }
            // 同一行里塞了多条指令的情况：按「, fill」这类软分隔符拆开
            for (String part : MULTI_COMMAND.split(line)) {
                String cleaned = cleanLine(part);
                if (cleaned != null) {
                    lines.add(cleaned);
                }
            }
        }
        if (lines.isEmpty()) {
            return failure("AI 没有给出任何建造指令");
        }
        return build("AI 建筑", "", lines, origin, limits);
    }

    /** 把一行正文规范成指令；不像指令的行返回 null。 */
    private static String cleanLine(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("```")) {
            return null;
        }
        // 去掉列表符号 / 行号： "- "、"* "、"1. "、"1) "
        line = line.replaceFirst("^[-*+•]\\s+", "");
        line = line.replaceFirst("^\\d+[.)]\\s+", "");
        // 无数组包裹的 JSON 字符串： "fill ...", / 'fill ...';
        if (!line.isEmpty() && (line.charAt(0) == '"' || line.charAt(0) == '\'')) {
            line = line.substring(1).trim();
        }
        // 反引号 / 粗体包裹：`fill ...` / **fill ...**
        line = line.replaceFirst("^`+", "").replaceFirst("^\\*\\*", "");
        line = line.replaceFirst("\\*\\*$", "").replaceFirst("`+$", "").trim();
        while (!line.isEmpty() && ",;；，。".indexOf(line.charAt(line.length() - 1)) >= 0) {
            line = line.substring(0, line.length() - 1).trim();
        }
        if (line.length() >= 2) {
            char last = line.charAt(line.length() - 1);
            char beforeLast = line.charAt(line.length() - 2);
            if ((last == '"' && beforeLast != '\\') || (last == '\'' && beforeLast != '\\')) {
                line = line.substring(0, line.length() - 1).trim();
            }
        }
        line = line.replaceFirst("\\s*//.*$", "").trim();
        while (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        for (String name : COMMAND_NAMES) {
            if (line.regionMatches(true, 0, name, 0, name.length())
                    && (line.length() == name.length() || Character.isWhitespace(line.charAt(name.length())))) {
                return line;
            }
        }
        return null;
    }

    /** 累加指令：单条解析失败计入 skipped，超限则截断。 */
    private static BuildPlan build(String name, String summary, List<String> lines,
                                   BuildOp.Pos origin, Limits limits) {
        List<BuildOp> ops = new ArrayList<>();
        List<String> rawLines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        int skipped = 0;
        boolean truncated = false;
        long total = 0L;
        for (String line : lines) {
            addRawLine(rawLines, line);
            if (ops.size() >= limits.maxOperations()) {
                truncated = true;
                addDetail(details, "超出指令条数上限：" + abbreviate(line, 120));
                break;
            }
            BuildOp op;
            try {
                op = BuildOp.parse(line, origin);
            } catch (RuntimeException ex) {
                skipped++;
                addDetail(details, ex.getMessage() + " <- " + abbreviate(line, 120));
                continue;
            }
            long volume = op.volume();
            if (volume > limits.maxVolume()) {
                // 单条指令就超限（例如超大 fill），直接跳过
                skipped++;
                addDetail(details, "单条指令方块数 " + volume + " 超过上限 " + limits.maxVolume()
                        + " <- " + abbreviate(line, 120));
                continue;
            }
            if (total + volume > limits.maxBlocks()) {
                truncated = true;
                addDetail(details, "超出总方块上限 " + limits.maxBlocks() + " <- " + abbreviate(line, 120));
                break;
            }
            total += volume;
            ops.add(op);
        }

        if (ops.isEmpty()) {
            return new BuildPlan(null, null, List.of(), skipped, truncated,
                    "AI 给出的建造指令全部无法解析，请换个说法再试一次",
                    List.copyOf(rawLines), List.copyOf(details));
        }
        return new BuildPlan(name, summary, List.copyOf(ops), skipped, truncated, null,
                List.copyOf(rawLines), List.copyOf(details));
    }

    private static void addRawLine(List<String> rawLines, String line) {
        if (rawLines.size() < MAX_RAW_LINES) {
            rawLines.add(abbreviate(line, MAX_RAW_LINE_CHARS));
        }
    }

    private static void addDetail(List<String> details, String detail) {
        if (details.size() < MAX_RAW_LINES) {
            details.add(detail);
        }
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String compact = text.replace('\n', ' ').replace('\r', ' ');
        return compact.length() <= max ? compact : compact.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    /** @return 是否成功解析出可执行的方案 */
    public boolean success() {
        return failureReason == null;
    }

    /** @return 失败原因；成功时为 null */
    public String failureReason() {
        return failureReason;
    }

    /** @return 建筑名 */
    public String name() {
        return name;
    }

    /** @return 一句话说明，可能为空串 */
    public String summary() {
        return summary;
    }

    /** @return 可执行的指令列表 */
    public List<BuildOp> ops() {
        return ops;
    }

    /** @return 被跳过的指令条数 */
    public int skipped() {
        return skipped;
    }

    /** @return 是否因为超出规模上限被截断 */
    public boolean truncated() {
        return truncated;
    }

    /** @return 估算的总方块数 */
    public long estimatedBlocks() {
        long total = 0L;
        for (BuildOp op : ops) {
            total += op.volume();
        }
        return total;
    }

    /**
     * 模型给出的原始行（已清洗、已截断，最多 {@value #MAX_RAW_LINES} 条）。
     *
     * <p>只用于诊断：一条都解析不出来时，把原文写进控制台才看得出是模型写成散文、
     * 还是方块名不存在、还是 JSON 被截断。</p>
     *
     * @return 原文行
     */
    public List<String> rawLines() {
        return rawLines;
    }

    /**
     * 每一条被丢弃的指令的原因（解析异常 / 超限），最多 {@value #MAX_RAW_LINES} 条。
     *
     * @return 丢弃原因
     */
    public List<String> skippedDetails() {
        return skippedDetails;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String optString(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
