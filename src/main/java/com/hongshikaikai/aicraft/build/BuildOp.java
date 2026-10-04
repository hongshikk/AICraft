package com.hongshikaikai.aicraft.build;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一条 AI 建造指令。
 *
 * <p>AI 返回的是一行行文本指令，{@link #parse(String, Pos)} 把它们解析成这里的记录。
 * 坐标一律支持两种写法：</p>
 * <ul>
 *   <li>{@code ~N} / {@code ~} —— 相对建造原点（玩家视线落点），{@code ~} 等价于 {@code ~0}；</li>
 *   <li>纯数字 —— 世界绝对坐标。</li>
 * </ul>
 *
 * <p>当前支持的指令（大小写不敏感）：</p>
 * <pre>
 *   fill     &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;方块&gt;       实心长方体
 *   setblock &lt;x y z&gt; &lt;方块&gt;                        单个方块
 *   hollow   &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;方块&gt;       长方体外壳（六面）
 *   walls    &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;方块&gt;       长方体四面墙（不含顶/底）
 *   line     &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;方块&gt;       两点连线
 *   sphere   &lt;x y z&gt; &lt;半径&gt; &lt;方块&gt; [hollow|solid]      球体（默认实心）
 *   cylinder &lt;x y z&gt; &lt;半径&gt; &lt;高度&gt; &lt;方块&gt; [hollow]     圆柱（默认实心）
 *   replace  &lt;x1 y1 z1&gt; &lt;x2 y2 z2&gt; &lt;过滤&gt; &lt;方块&gt;  区域内替换（过滤可是方块名 / #标签 / any）
 *   sign     &lt;x y z&gt; [方块] &lt;文本&gt;                    告示牌，文本用 | 分行（最多 4 行）
 * </pre>
 *
 * <p>模型经常换用同义词，这些别名同样接受：{@code box}/{@code cube} = fill、
 * {@code shell}/{@code outline} = hollow、{@code block}/{@code add}/{@code place} = setblock、
 * {@code wall} = walls、{@code ball} = sphere、{@code cyl} = cylinder。
 * 别名只在解析这一层存在，日志与撤销快照里统一显示规范名。</p>
 *
 * <p>方块写法即原版字符串，例如 {@code stone_bricks}、{@code oak_stairs[facing=north,half=top]}、
 * {@code air}。</p>
 */
public sealed interface BuildOp {

    /** 方块坐标。 */
    record Pos(int x, int y, int z) {
    }

    /**
     * 估算本条指令影响的方块数（用于规模限制与进度显示）。
     *
     * @return 方块数
     */
    long volume();

    /**
     * 供日志使用的简短描述。
     *
     * @return 描述文本
     */
    String describe();

    // ------------------------------------------------------------------
    // 各类指令
    // ------------------------------------------------------------------

    /** 实心长方体。 */
    record Fill(Pos a, Pos b, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return boxVolume(a, b);
        }

        @Override
        public String describe() {
            return "fill " + fmt(a) + " -> " + fmt(b) + " " + data.getAsString();
        }
    }

    /** 单个方块。 */
    record SetBlock(Pos p, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return 1L;
        }

        @Override
        public String describe() {
            return "setblock " + fmt(p) + " " + data.getAsString();
        }
    }

    /** 六面外壳。 */
    record Hollow(Pos a, Pos b, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return boxVolume(a, b);
        }

        @Override
        public String describe() {
            return "hollow " + fmt(a) + " -> " + fmt(b) + " " + data.getAsString();
        }
    }

    /** 四面墙（不含顶面与底面）。 */
    record Walls(Pos a, Pos b, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return boxVolume(a, b);
        }

        @Override
        public String describe() {
            return "walls " + fmt(a) + " -> " + fmt(b) + " " + data.getAsString();
        }
    }

    /** 两点连线。 */
    record Line(Pos a, Pos b, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return length(a, b) + 1L;
        }

        @Override
        public String describe() {
            return "line " + fmt(a) + " -> " + fmt(b) + " " + data.getAsString();
        }
    }

    /** 球体（可空心）。 */
    record Sphere(Pos center, int radius, BlockData data, boolean hollow) implements BuildOp {
        @Override
        public long volume() {
            long side = 2L * radius + 1L;
            return side * side * side;
        }

        @Override
        public String describe() {
            return "sphere " + fmt(center) + " r=" + radius + (hollow ? " hollow " : " ") + data.getAsString();
        }
    }

    /** 圆柱（可空心），{@code height} 沿 Y 轴。 */
    record Cylinder(Pos base, int radius, int height, BlockData data, boolean hollow) implements BuildOp {
        @Override
        public long volume() {
            long side = 2L * radius + 1L;
            return side * side * Math.max(1, height);
        }

        @Override
        public String describe() {
            return "cylinder " + fmt(base) + " r=" + radius + " h=" + height
                    + (hollow ? " hollow " : " ") + data.getAsString();
        }
    }

    /** 区域内按过滤器替换。 */
    record Replace(Pos a, Pos b, BlockFilter filter, BlockData data) implements BuildOp {
        @Override
        public long volume() {
            return boxVolume(a, b);
        }

        @Override
        public String describe() {
            return "replace " + fmt(a) + " -> " + fmt(b) + " " + filter.describe() + " " + data.getAsString();
        }
    }

    /** 告示牌（带文本）。 */
    record Sign(Pos p, BlockData data, List<String> lines) implements BuildOp {
        @Override
        public long volume() {
            return 1L;
        }

        @Override
        public String describe() {
            return "sign " + fmt(p) + " " + String.join(" | ", lines);
        }
    }

    // ------------------------------------------------------------------
    // 过滤器
    // ------------------------------------------------------------------

    /**
     * {@code replace} 的匹配条件。
     *
     * @param raw      原始写法（日志用）
     * @param material 指定方块材质；为 null 表示按标签或「任意非空气」匹配
     * @param tag      方块标签；为 null 表示不按标签匹配
     */
    record BlockFilter(String raw, Material material, Tag<Material> tag) {

        /**
         * 判断当前方块是否命中过滤条件。
         *
         * @param current 当前方块数据
         * @return 命中返回 true
         */
        public boolean matches(BlockData current) {
            if (tag != null) {
                return tag.isTagged(current.getMaterial());
            }
            if (material != null) {
                return current.getMaterial() == material;
            }
            return !current.getMaterial().isAir();
        }

        /** @return 日志描述 */
        public String describe() {
            return raw;
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 线段长度上限，避免 AI 写出跨越几千格的「线」。 */
    int MAX_LINE_LENGTH = 1024;

    /**
     * 解析一行指令。
     *
     * @param line   AI 返回的一行文本，形如 {@code fill ~0 ~0 ~0 ~5 ~3 ~5 stone}
     * @param origin 建造原点（{@code ~} 的基准）
     * @return 解析结果
     * @throws IllegalArgumentException 指令无法识别 / 参数不合法时抛出（调用方跳过该条即可）
     */
    static BuildOp parse(String line, Pos origin) {
        String text = line == null ? "" : line.trim();
        while (text.startsWith("/")) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("空指令");
        }
        String[] tokens = text.split("\\s+");
        String name = tokens[0].toLowerCase(Locale.ROOT);
        return switch (name) {
            case "fill", "box", "cube" ->
                    new Fill(pos(tokens, 1, origin), pos(tokens, 4, origin), block(token(tokens, 7)));
            case "setblock", "set", "block", "add", "place" ->
                    new SetBlock(pos(tokens, 1, origin), block(token(tokens, 4)));
            case "hollow", "shell", "outline" ->
                    new Hollow(pos(tokens, 1, origin), pos(tokens, 4, origin), block(token(tokens, 7)));
            case "walls", "wall" ->
                    new Walls(pos(tokens, 1, origin), pos(tokens, 4, origin), block(token(tokens, 7)));
            case "line" -> newLine(pos(tokens, 1, origin), pos(tokens, 4, origin), block(token(tokens, 7)));
            case "sphere", "ball" -> newSphere(tokens, origin);
            case "cylinder", "cyl" -> newCylinder(tokens, origin);
            case "replace" -> new Replace(pos(tokens, 1, origin), pos(tokens, 4, origin),
                    filter(token(tokens, 7)), block(token(tokens, 8)));
            case "sign" -> newSign(tokens, origin);
            default -> throw new IllegalArgumentException("未知指令: " + name);
        };
    }

    private static BuildOp newLine(Pos a, Pos b, BlockData data) {
        int length = length(a, b);
        if (length > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("线段过长: " + length);
        }
        return new Line(a, b, data);
    }

    private static BuildOp newSphere(String[] tokens, Pos origin) {
        Pos center = pos(tokens, 1, origin);
        int radius = positiveInt(token(tokens, 4), "半径");
        BlockData data = block(token(tokens, 5));
        boolean hollow = tokens.length > 6 && isHollow(tokens[6]);
        return new Sphere(center, radius, data, hollow);
    }

    private static BuildOp newCylinder(String[] tokens, Pos origin) {
        Pos base = pos(tokens, 1, origin);
        int radius = positiveInt(token(tokens, 4), "半径");
        int height = positiveInt(token(tokens, 5), "高度");
        BlockData data = block(token(tokens, 6));
        boolean hollow = tokens.length > 7 && isHollow(tokens[7]);
        return new Cylinder(base, radius, height, data, hollow);
    }

    private static BuildOp newSign(String[] tokens, Pos origin) {
        Pos p = pos(tokens, 1, origin);
        BlockData data = Material.OAK_SIGN.createBlockData();
        int textStart = 4;
        if (tokens.length > 5) {
            // 可选：sign x y z <某种告示牌> <文本>
            try {
                BlockData candidate = block(tokens[4]);
                if (candidate.getMaterial().name().contains("SIGN")) {
                    data = candidate;
                    textStart = 5;
                }
            } catch (IllegalArgumentException ignored) {
                // 不是方块名，说明第 5 个 token 已经是文本
            }
        }
        if (tokens.length <= textStart) {
            throw new IllegalArgumentException("sign 缺少文本");
        }
        List<String> lines = new ArrayList<>(4);
        for (String part : join(tokens, textStart).split("\\|")) {
            if (lines.size() >= 4) {
                break;
            }
            lines.add(part.trim());
        }
        return new Sign(p, data, List.copyOf(lines));
    }

    private static boolean isHollow(String token) {
        String value = token.toLowerCase(Locale.ROOT);
        return value.equals("hollow") || value.equals("shell") || value.equals("true") || value.equals("空心");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 取第 {@code index} 个 token，缺失即报错。 */
    private static String token(String[] tokens, int index) {
        if (index >= tokens.length) {
            throw new IllegalArgumentException("指令参数不足（缺少第 " + (index + 1) + " 项）");
        }
        return tokens[index];
    }

    /** 取三个坐标。 */
    private static Pos pos(String[] tokens, int index, Pos origin) {
        return new Pos(
                coord(token(tokens, index), origin.x()),
                coord(token(tokens, index + 1), origin.y()),
                coord(token(tokens, index + 2), origin.z()));
    }

    /**
     * 解析单个坐标：{@code ~} / {@code ~N} 相对原点，纯数字为世界绝对坐标。
     *
     * @param raw         原始 token
     * @param originValue 原点在该轴上的值
     * @return 绝对坐标
     */
    static int coord(String raw, int originValue) {
        String text = raw.trim();
        if (text.startsWith("^")) {
            throw new IllegalArgumentException("不支持 ^ 局部坐标: " + raw);
        }
        if (text.startsWith("~")) {
            String rest = text.substring(1).trim();
            if (rest.isEmpty() || rest.equals("+")) {
                return originValue;
            }
            return originValue + (int) Math.floor(parseNumber(rest));
        }
        return (int) Math.floor(parseNumber(text));
    }

    private static double parseNumber(String text) {
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("无法解析数字: " + text);
        }
        // NaN / Infinity / 1e9 这类「解析成功但没法当坐标」的值直接挡掉，
        // 否则强转成 int 会静默变成 1 或 Integer.MAX_VALUE，建出一根跨越几千格的柱子。
        if (!Double.isFinite(value) || Math.abs(value) > 30_000_000D) {
            throw new IllegalArgumentException("坐标超出范围: " + text);
        }
        return value;
    }

    private static int positiveInt(String raw, String what) {
        int value = (int) Math.floor(parseNumber(raw.trim()));
        if (value < 0) {
            throw new IllegalArgumentException(what + "不能为负: " + raw);
        }
        return value;
    }

    /**
     * 解析方块字符串，支持原版方块状态写法。
     *
     * @param raw 形如 {@code stone} / {@code oak_stairs[facing=north]}
     * @return 方块数据
     */
    static BlockData block(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.startsWith("minecraft:")) {
            text = text.substring("minecraft:".length());
        }
        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少方块");
        }
        try {
            return Bukkit.createBlockData(text);
        } catch (IllegalArgumentException ignored) {
            // 落到下面的材质兜底
        }
        Material material = Material.matchMaterial(text);
        if (material == null || !material.isBlock()) {
            throw new IllegalArgumentException("未知方块: " + raw);
        }
        return material.createBlockData();
    }

    /**
     * 解析 replace 的过滤条件。
     *
     * @param raw 方块名 / {@code #标签} / {@code any}
     * @return 过滤器
     */
    static BlockFilter filter(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.equals("any") || text.equals("*") || text.equals("non_air")) {
            return new BlockFilter(text.isEmpty() ? "any" : text, null, null);
        }
        if (text.startsWith("#")) {
            NamespacedKey key = NamespacedKey.fromString(text.substring(1));
            Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
            if (tag == null) {
                throw new IllegalArgumentException("未知方块标签: " + raw);
            }
            return new BlockFilter(text, null, tag);
        }
        BlockData data = block(text);
        return new BlockFilter(text, data.getMaterial(), null);
    }

    private static long boxVolume(Pos a, Pos b) {
        return (long) (Math.abs(a.x() - b.x()) + 1)
                * (Math.abs(a.y() - b.y()) + 1)
                * (Math.abs(a.z() - b.z()) + 1);
    }

    private static int length(Pos a, Pos b) {
        return Math.max(Math.abs(a.x() - b.x()),
                Math.max(Math.abs(a.y() - b.y()), Math.abs(a.z() - b.z())));
    }

    private static String join(String[] tokens, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < tokens.length; i++) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(tokens[i]);
        }
        return builder.toString();
    }

    /** @return {@code "x y z"} */
    static String fmt(Pos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }
}
