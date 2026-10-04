package com.hongshikaikai.aicraft.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「结构化 JSON 建造方案」到 {@link BuildOp} 指令文本的翻译层。
 *
 * <p>提示词现在要求模型输出自描述的 op 对象：</p>
 * <pre>
 * {
 *   "success": true,
 *   "name": "角斗场",
 *   "summary": "椭圆形石造看台 + 沙地竞技场",
 *   "ops": [
 *     { "op": "fill",  "from": [-9, 0, -9], "to": [9, 0, 9], "block": "stone_bricks" },
 *     { "op": "sphere", "at": [0, 3, 0], "radius": 2, "block": "gold_block", "hollow": true }
 *   ]
 * }
 * </pre>
 *
 * <p>本类把每个 op 翻成 {@code BuildOp.parse} 认得的规范指令文本（{@code "fill 0 0 0 1 1 1 stone"}）。
 * 这样做而不是直接构造 {@link BuildOp}，有两个原因：解析与方块校验只有一份实现；
 * 本类不引用任何 Bukkit 类型，可以脱开服务端做单元测试。</p>
 *
 * <h2>为什么要写得这么宽容</h2>
 * <p>免费车道上的模型即使被要求输出固定 schema，字段名与坐标写法仍会漂移。实测出现过
 * {@code "position"} / {@code "pos"} 代替 {@code "at"}、{@code "x1 y1 z1 x2 y2 z2"} 平铺、
 * 坐标写成 {@code {"x":0,"y":0,"z":0}} 或 {@code "0, 0, 0"}、
 * 指令名写成 {@code "place_block"} / {@code "box"} 等等。这些都在这里吸收掉：
 * 只要能认出「要干什么、在哪里、用什么方块」，就一定能翻译成一条合法指令。
 * 确实缺关键字段的 op 返回 {@code null}，由调用方计入「已跳过」，而不是让整次建造失败。</p>
 *
 * <p>旧协议也继续接受：{@code ops} 里放字符串指令、{@code commands} 数组、
 * 甚至把整段指令塞进一个多行字符串。</p>
 */
final class OpJson {

    /** 指令数组可能出现的键名（按优先级：新协议优先）。 */
    private static final String[] ARRAY_KEYS = {
            "ops", "commands", "operations", "actions", "instructions", "steps",
            "plan", "blueprint", "layout", "commands_list", "build_commands", "command_list"
    };

    /** 方案可能被再套一层的键名。 */
    private static final String[] NESTED_KEYS = {
            "build", "plan", "result", "data", "output", "response", "building", "structure"
    };

    /** op 对象里可能表示「指令名」的键名。 */
    private static final String[] OP_KEYS = {
            "op", "operation", "action", "command", "kind", "type", "cmd", "name"
    };

    /** op 对象里可能表示「方块」的键名。 */
    private static final String[] BLOCK_KEYS = {
            "block", "material", "block_type", "blockType", "block_id", "blockId",
            "new_block", "to_block", "blocks", "tile", "id"
    };

    /** 长方体起点候选键。 */
    private static final String[] START_KEYS = {
            "from", "start", "min", "minimum", "a", "pos1", "p1", "corner1", "first", "begin"
    };

    /** 长方体终点候选键。 */
    private static final String[] END_KEYS = {
            "to", "end", "max", "maximum", "b", "pos2", "p2", "corner2", "second", "finish"
    };

    /** 单点坐标候选键。 */
    private static final String[] POINT_KEYS = {
            "at", "pos", "position", "center", "centre", "base", "location",
            "point", "origin", "where", "p"
    };

    private OpJson() {
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 从根 JSON 里收集全部建造指令（已翻译成 {@link BuildOp} 认得的文本）。
     *
     * @param root AI 返回的根对象
     * @return 指令文本列表；没有任何指令时为空列表
     */
    static List<String> collect(JsonObject root) {
        List<String> lines = new ArrayList<>();
        collectFrom(root, lines, 0);
        return lines;
    }

    /**
     * 判断根 JSON 是否明确表示「这次做不了」。
     *
     * <p>只有显式的假值（{@code false} / {@code "false"} / {@code 0} / {@code "no"}）才算失败；
     * {@code success} 缺失、写错类型、或写成看不懂的字符串时一律按成功处理 ——
     * 否则一个含糊的字段就会让明明有 ops 的方案被判死。</p>
     *
     * @param root AI 返回的根对象
     * @return 明确失败返回 true
     */
    static boolean isExplicitFailure(JsonObject root) {
        JsonObject object = root;
        for (int depth = 0; depth <= 2 && object != null; depth++) {
            JsonElement success = object.get("success");
            if (success != null && success.isJsonPrimitive()) {
                JsonPrimitive primitive = success.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    return !primitive.getAsBoolean();
                }
                if (primitive.isNumber()) {
                    return primitive.getAsInt() == 0;
                }
                String text = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
                return text.equals("false") || text.equals("no") || text.equals("0")
                        || text.equals("失败") || text.equals("否");
            }
            object = nested(object);
        }
        return false;
    }

    /**
     * 在根对象（或它的嵌套容器）里找一个字符串字段。
     *
     * @param root 根对象
     * @param key  字段名
     * @return 字段值；找不到返回 null
     */
    static String findString(JsonObject root, String key) {
        JsonObject object = root;
        for (int depth = 0; depth <= 2 && object != null; depth++) {
            String value = scalar(object.get(key));
            if (value != null) {
                return value;
            }
            object = nested(object);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 收集
    // ------------------------------------------------------------------

    private static void collectFrom(JsonObject object, List<String> lines, int depth) {
        if (object == null || depth > 2) {
            return;
        }
        for (String key : ARRAY_KEYS) {
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            addValue(value, lines);
            if (!lines.isEmpty()) {
                // 找到一组就用它，避免同一份方案被重复收集
                return;
            }
        }
        for (String key : NESTED_KEYS) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonObject()) {
                collectFrom(value.getAsJsonObject(), lines, depth + 1);
                if (!lines.isEmpty()) {
                    return;
                }
            }
        }
    }

    private static void addValue(JsonElement value, List<String> lines) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                String line = toCommandLine(item);
                if (line != null) {
                    lines.add(line);
                }
            }
            return;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (unwrap(object) != null) {
                // 单个 op 被写成了对象而不是数组
                String line = toCommandLine(object);
                if (line != null) {
                    lines.add(line);
                }
                return;
            }
            // {"0": {...}, "1": {...}} 或 {"ops": [...]} 这类包装
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                addValue(entry.getValue(), lines);
            }
            return;
        }
        // 整个数组被写成一个多行字符串
        String text = scalar(value);
        if (text != null) {
            for (String raw : text.split("\r?\n")) {
                String line = raw.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
    }

    /**
     * 把任意形态的一项（字符串 / 对象 / 数组）翻成一条指令文本。
     *
     * @param element 数组里的一项
     * @return 指令文本；无法识别返回 null
     */
    static String toCommandLine(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray()) {
            return arrayToCommand(element.getAsJsonArray());
        }
        if (element.isJsonObject()) {
            return objectToCommand(element.getAsJsonObject());
        }
        if (element.isJsonPrimitive()) {
            String text = element.getAsString();
            return text == null || text.isBlank() ? null : text.trim();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 对象形式
    // ------------------------------------------------------------------

    private record Unwrapped(String op, JsonObject args) {
    }

    private static String objectToCommand(JsonObject object) {
        Unwrapped unwrapped = unwrap(object);
        if (unwrapped == null) {
            // {"command": "fill 0 0 0 1 1 1 stone"} 这种把整条指令塞进字段的写法
            String direct = scalarDeep(object.get("command"));
            if (direct == null) {
                direct = scalarDeep(object.get("cmd"));
            }
            if (direct != null && normalizeOp(firstToken(direct)) != null) {
                return direct.trim();
            }
            return null;
        }
        String op = unwrapped.op();
        JsonObject args = unwrapped.args();
        switch (op) {
            case "fill", "hollow", "walls", "line" -> {
                String from = boxStart(args);
                String to = boxEnd(args);
                String block = blockArg(args);
                if (from == null || to == null || block == null) {
                    return null;
                }
                return op + " " + from + " " + to + " " + block;
            }
            case "replace" -> {
                String from = boxStart(args);
                String to = boxEnd(args);
                String block = blockArg(args);
                if (from == null || to == null || block == null) {
                    return null;
                }
                String filter = textArg(args, "filter", "match", "target", "old", "existing", "replaceable");
                return "replace " + from + " " + to + " " + (filter == null ? "any" : filter) + " " + block;
            }
            case "setblock" -> {
                String at = singlePoint(args);
                String block = blockArg(args);
                if (at == null || block == null) {
                    return null;
                }
                return "setblock " + at + " " + block;
            }
            case "sphere" -> {
                String at = singlePoint(args);
                String radius = radiusArg(args);
                String block = blockArg(args);
                if (at == null || radius == null || block == null) {
                    return null;
                }
                return "sphere " + at + " " + radius + " " + block + (hollowArg(args) ? " hollow" : "");
            }
            case "cylinder" -> {
                String at = singlePoint(args);
                String radius = radiusArg(args);
                String height = numberArg(args, "height", "h", "length", "layers", "thickness");
                String block = blockArg(args);
                if (at == null || radius == null || block == null) {
                    return null;
                }
                // 高度缺失时退化成一层圆盘，总比整条丢掉好
                return "cylinder " + at + " " + radius + " " + (height == null ? "1" : height) + " "
                        + block + (hollowArg(args) ? " hollow" : "");
            }
            case "sign" -> {
                String at = singlePoint(args);
                String text = signText(args);
                if (at == null || text == null) {
                    return null;
                }
                String block = blockArg(args);
                return "sign " + at + " " + (block == null ? "" : block + " ") + text;
            }
            default -> {
                return null;
            }
        }
    }

    /** 认出「指令名」，同时兼容 {@code {"fill": {...}}} 这种把指令名当键的写法。 */
    private static Unwrapped unwrap(JsonObject object) {
        if (object == null) {
            return null;
        }
        String op = opName(object);
        if (op != null) {
            return new Unwrapped(op, object);
        }
        if (object.size() == 1) {
            Map.Entry<String, JsonElement> entry = object.entrySet().iterator().next();
            String keyed = normalizeOp(entry.getKey());
            if (keyed != null && entry.getValue() != null && entry.getValue().isJsonObject()) {
                JsonObject merged = new JsonObject();
                merged.addProperty("op", keyed);
                for (Map.Entry<String, JsonElement> property : entry.getValue().getAsJsonObject().entrySet()) {
                    merged.add(property.getKey(), property.getValue());
                }
                return new Unwrapped(keyed, merged);
            }
        }
        return null;
    }

    private static String opName(JsonObject object) {
        for (String key : OP_KEYS) {
            String raw = scalar(object.get(key));
            if (raw == null) {
                continue;
            }
            String op = normalizeOp(raw);
            if (op != null) {
                return op;
            }
        }
        return null;
    }

    /** 把模型可能用的同义词归一到 {@link BuildOp} 的规范指令名；不认识返回 null。 */
    private static String normalizeOp(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (name) {
            case "fill", "box", "cube", "cuboid", "fill_box", "fill_region", "solid_box" -> "fill";
            case "setblock", "set_block", "set", "place", "place_block", "put", "add", "add_block", "block" ->
                    "setblock";
            case "hollow", "hollow_box", "shell", "outline", "frame", "box_shell" -> "hollow";
            case "walls", "wall", "four_walls", "wall_ring" -> "walls";
            case "line", "draw_line", "connect", "wire" -> "line";
            case "sphere", "ball", "dome" -> "sphere";
            case "cylinder", "cyl", "tube", "column" -> "cylinder";
            case "replace", "replace_block", "swap", "substitute" -> "replace";
            case "sign", "set_sign", "sign_board" -> "sign";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // 数组形式：[op, from, to, block]
    // ------------------------------------------------------------------

    private static String arrayToCommand(JsonArray array) {
        if (array.size() < 2 || array.get(0) == null || !array.get(0).isJsonPrimitive()) {
            return null;
        }
        String op = normalizeOp(array.get(0).getAsString());
        if (op == null) {
            return null;
        }
        JsonObject args = new JsonObject();
        args.addProperty("op", op);
        switch (op) {
            case "fill", "hollow", "walls", "line" -> {
                args.add("from", array.get(1));
                if (array.size() > 2) {
                    args.add("to", array.get(2));
                }
                if (array.size() > 3) {
                    args.add("block", array.get(3));
                }
            }
            case "replace" -> {
                args.add("from", array.get(1));
                if (array.size() > 2) {
                    args.add("to", array.get(2));
                }
                if (array.size() > 3) {
                    args.add("filter", array.get(3));
                }
                if (array.size() > 4) {
                    args.add("block", array.get(4));
                }
            }
            case "setblock" -> {
                args.add("at", array.get(1));
                if (array.size() > 2) {
                    args.add("block", array.get(2));
                }
            }
            case "sphere" -> {
                args.add("at", array.get(1));
                if (array.size() > 2) {
                    args.add("radius", array.get(2));
                }
                if (array.size() > 3) {
                    args.add("block", array.get(3));
                }
                if (array.size() > 4) {
                    args.add("hollow", array.get(4));
                }
            }
            case "cylinder" -> {
                args.add("at", array.get(1));
                if (array.size() > 2) {
                    args.add("radius", array.get(2));
                }
                if (array.size() > 3) {
                    args.add("height", array.get(3));
                }
                if (array.size() > 4) {
                    args.add("block", array.get(4));
                }
                if (array.size() > 5) {
                    args.add("hollow", array.get(5));
                }
            }
            case "sign" -> {
                args.add("at", array.get(1));
                if (array.size() > 2) {
                    args.add("text", array.get(2));
                }
                if (array.size() > 3) {
                    args.add("block", array.get(3));
                }
            }
            default -> {
                return null;
            }
        }
        return objectToCommand(args);
    }

    // ------------------------------------------------------------------
    // 字段提取
    // ------------------------------------------------------------------

    private static String boxStart(JsonObject object) {
        String point = pointArg(object, START_KEYS);
        return point != null ? point : flatPoint(object, "x1", "y1", "z1");
    }

    private static String boxEnd(JsonObject object) {
        String point = pointArg(object, END_KEYS);
        return point != null ? point : flatPoint(object, "x2", "y2", "z2");
    }

    private static String singlePoint(JsonObject object) {
        String point = pointArg(object, POINT_KEYS);
        return point != null ? point : flatPoint(object, "x", "y", "z");
    }

    private static String pointArg(JsonObject object, String... keys) {
        for (String key : keys) {
            String point = pointOf(object.get(key));
            if (point != null) {
                return point;
            }
        }
        return null;
    }

    /** 把 {@code [x,y,z]} / {@code {"x":..,"y":..,"z":..}} / {@code "0, 0, 0"} 统一成 {@code "x y z"}。 */
    private static String pointOf(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (array.size() == 1 && array.get(0) != null && array.get(0).isJsonArray()) {
                array = array.get(0).getAsJsonArray();
            }
            if (array.size() < 3) {
                return null;
            }
            List<String> parts = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                String token = scalar(array.get(i));
                if (token == null || !isCoordToken(token)) {
                    return null;
                }
                parts.add(token);
            }
            return String.join(" ", parts);
        }
        if (value.isJsonObject()) {
            return flatPoint(value.getAsJsonObject(), "x", "y", "z");
        }
        if (value.isJsonPrimitive()) {
            return sanitizePoint(value.getAsString());
        }
        return null;
    }

    private static String flatPoint(JsonObject object, String kx, String ky, String kz) {
        String x = scalar(object.get(kx));
        String y = scalar(object.get(ky));
        String z = scalar(object.get(kz));
        if (x == null || y == null || z == null
                || !isCoordToken(x) || !isCoordToken(y) || !isCoordToken(z)) {
            return null;
        }
        return x + " " + y + " " + z;
    }

    /** 清洗一个「一个坐标点」的字符串写法：{@code "(0, 0, 0)"} -> {@code "0 0 0"}。 */
    private static String sanitizePoint(String raw) {
        if (raw == null) {
            return null;
        }
        String text = stripWrapping(raw)
                .replace(',', ' ')
                .replace('，', ' ')
                .replace('=', ' ')
                .replace(':', ' ');
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        for (String part : parts) {
            if (!isCoordToken(part)) {
                return null;
            }
        }
        return String.join(" ", parts);
    }

    /** 坐标 token：纯数字，或 {@code ~} / {@code ~N} 这种相对写法。 */
    private static boolean isCoordToken(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.equals("~") || text.equals("~+")) {
            return true;
        }
        String body = text.startsWith("~") ? text.substring(1) : text;
        try {
            Double.parseDouble(body);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String blockArg(JsonObject object) {
        for (String key : BLOCK_KEYS) {
            String block = scalarDeep(object.get(key));
            if (block != null) {
                return sanitizeId(block);
            }
        }
        // type 已经被用作指令名时不能再当方块名
        String type = scalar(object.get("type"));
        if (type != null && normalizeOp(type) == null) {
            return sanitizeId(type);
        }
        return null;
    }

    private static String radiusArg(JsonObject object) {
        String radius = numberArg(object, "radius", "r", "rad", "size", "range");
        if (radius != null) {
            return radius;
        }
        String diameter = numberArg(object, "diameter", "d");
        if (diameter == null) {
            return null;
        }
        try {
            return Integer.toString((int) Math.floor(Double.parseDouble(diameter) / 2.0D));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 取第一个能解析成数字的字段。 */
    private static String numberArg(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = scalarDeep(object.get(key));
            if (value == null) {
                continue;
            }
            try {
                Double.parseDouble(value.replace("+", "").trim());
                return value.trim();
            } catch (NumberFormatException ignored) {
                // 不是数字，试下一个键
            }
        }
        return null;
    }

    private static boolean hollowArg(JsonObject object) {
        for (String key : new String[]{"hollow", "shell", "empty", "ring", "only_shell"}) {
            String text = scalar(object.get(key));
            if (text == null) {
                continue;
            }
            String value = text.toLowerCase(Locale.ROOT);
            if (value.equals("true") || value.equals("hollow") || value.equals("shell")
                    || value.equals("yes") || value.equals("1") || value.equals("空心")) {
                return true;
            }
            if (value.equals("false") || value.equals("solid") || value.equals("no")
                    || value.equals("0") || value.equals("实心")) {
                return false;
            }
        }
        return false;
    }

    /** 取第一个存在的字符串字段（用于 filter 这类自由文本）。 */
    private static String textArg(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = scalarDeep(object.get(key));
            if (value != null) {
                return sanitizeId(value);
            }
        }
        return null;
    }

    private static String signText(JsonObject object) {
        JsonElement value = firstPresent(object, "text", "lines", "content", "message", "label", "texts");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        List<String> parts = new ArrayList<>(4);
        if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                String line = scalar(item);
                if (line != null) {
                    parts.add(line);
                }
            }
        } else if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                String line = scalar(entry.getValue());
                if (line != null) {
                    parts.add(line);
                }
            }
        } else {
            String text = scalar(value);
            if (text != null) {
                for (String line : text.split("\\r?\\n|\\|")) {
                    if (!line.isBlank()) {
                        parts.add(line.trim());
                    }
                }
            }
        }
        List<String> cleaned = new ArrayList<>(4);
        for (String part : parts) {
            // 文本里再出现 | 会打乱分行，统一换成 /
            String line = part.replace('|', '/').trim();
            if (!line.isEmpty()) {
                cleaned.add(line);
            }
            if (cleaned.size() == 4) {
                break;
            }
        }
        return cleaned.isEmpty() ? null : String.join(" | ", cleaned);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static JsonObject nested(JsonObject object) {
        for (String key : NESTED_KEYS) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonObject()) {
                return value.getAsJsonObject();
            }
        }
        return null;
    }

    private static JsonElement firstPresent(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                return value;
            }
        }
        return null;
    }

    private static String firstToken(String text) {
        String trimmed = text == null ? "" : text.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /** 取原始标量字符串；非标量 / 空串返回 null。 */
    private static String scalar(JsonElement value) {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            String text = value.getAsString();
            return text == null || text.isBlank() ? null : text.trim();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 取标量字符串；对象则再往里找一层 {@code id/name/block}。 */
    private static String scalarDeep(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : new String[]{"id", "name", "block", "type", "material", "value", "block_id"}) {
                String nested = scalar(object.get(key));
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return scalar(value);
    }

    /** 去掉包裹的括号 / 引号 / 反引号。 */
    private static String stripWrapping(String raw) {
        String text = raw == null ? "" : raw.trim();
        while (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            boolean pair = (first == '(' && last == ')')
                    || (first == '[' && last == ']')
                    || (first == '{' && last == '}')
                    || (first == '"' && last == '"')
                    || (first == '\'' && last == '\'')
                    || (first == '`' && last == '`');
            if (!pair) {
                break;
            }
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    /** 方块 ID / 标签 / 过滤条件：去掉包裹与所有空白（{@code oak_stairs[a=b, c=d]} 里的空格要清掉）。 */
    private static String sanitizeId(String raw) {
        String text = stripWrapping(raw).replaceAll("\\s+", "");
        return text.isEmpty() ? null : text;
    }
}
