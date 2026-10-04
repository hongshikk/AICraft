package com.hongshikaikai.aicraft.build;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpJson} 的测试：结构化 JSON 建造方案 -> 规范指令文本。
 *
 * <p>这些都是「模型不按 schema 出牌」的真实形状：字段名漂移、坐标写成对象 / 字符串 /
 * 平铺、指令名用同义词、坐标带 {@code ~}……翻译层必须把它们全部吸收，
 * 否则玩家看到的就是「AI 给出的建造指令全部无法解析」。</p>
 *
 * <p>本测试不依赖 Bukkit：翻译层只产出文本，不碰 {@code Material} / {@code BlockData}。</p>
 */
class OpJsonTest {

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String line(String opJson) {
        return OpJson.toCommandLine(JsonParser.parseString(opJson));
    }

    private static List<String> collect(String rootJson) {
        return OpJson.collect(json(rootJson));
    }

    // ------------------------------------------------------------------
    // 标准协议
    // ------------------------------------------------------------------

    @Test
    void translatesEveryOpKind() {
        String root = """
                {
                  "success": true,
                  "name": "测试",
                  "summary": "说明",
                  "ops": [
                    { "op": "fill",     "from": [-9, 0, -9], "to": [9, 0, 9], "block": "stone_bricks" },
                    { "op": "walls",    "from": [-9, 1, -9], "to": [9, 4, 9], "block": "oak_planks" },
                    { "op": "hollow",   "from": [-9, 5, -9], "to": [9, 6, 9], "block": "oak_planks" },
                    { "op": "line",     "from": [0, 7, 0], "to": [0, 12, 0], "block": "oak_log" },
                    { "op": "setblock", "at": [0, 5, 0], "block": "oak_planks" },
                    { "op": "sphere",   "at": [0, 12, 0], "radius": 2, "block": "gold_block", "hollow": true },
                    { "op": "cylinder", "at": [0, 1, 0], "radius": 3, "height": 4, "block": "stone_bricks" },
                    { "op": "replace",  "from": [-9, 1, -9], "to": [9, 4, 9], "filter": "any", "block": "air" },
                    { "op": "sign",     "at": [0, 2, -9], "text": ["第一行", "第二行"] }
                  ]
                }
                """;
        List<String> lines = collect(root);
        assertEquals(9, lines.size());
        assertEquals("fill -9 0 -9 9 0 9 stone_bricks", lines.get(0));
        assertEquals("walls -9 1 -9 9 4 9 oak_planks", lines.get(1));
        assertEquals("hollow -9 5 -9 9 6 9 oak_planks", lines.get(2));
        assertEquals("line 0 7 0 0 12 0 oak_log", lines.get(3));
        assertEquals("setblock 0 5 0 oak_planks", lines.get(4));
        assertEquals("sphere 0 12 0 2 gold_block hollow", lines.get(5));
        assertEquals("cylinder 0 1 0 3 4 stone_bricks", lines.get(6));
        assertEquals("replace -9 1 -9 9 4 9 any air", lines.get(7));
        assertEquals("sign 0 2 -9 第一行 | 第二行", lines.get(8));
    }

    @Test
    void replaceWithoutFilterDefaultsToAny() {
        assertEquals("replace 0 0 0 1 1 1 any air",
                line("{\"op\":\"replace\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"air\"}"));
    }

    @Test
    void cylinderWithoutHeightDegradesToADiscInsteadOfBeingDropped() {
        assertEquals("cylinder 0 0 0 3 1 stone",
                line("{\"op\":\"cylinder\",\"at\":[0,0,0],\"radius\":3,\"block\":\"stone\"}"));
    }

    @Test
    void negativeAndRelativeStringCoordinatesSurvive() {
        // 旧协议的 ~ 写法仍然能被翻译层放行，由 BuildOp.parse 解析
        assertEquals("fill ~-8 ~0 ~-8 ~8 ~0 ~8 stone",
                line("{\"op\":\"fill\",\"from\":\"~-8 ~0 ~-8\",\"to\":\"~8 ~0 ~8\",\"block\":\"stone\"}"));
    }

    // ------------------------------------------------------------------
    // 字段名 / 写法的漂移
    // ------------------------------------------------------------------

    @Test
    void acceptsAliasFieldNamesAndCoordinateForms() {
        // position 代替 at，坐标是对象，指令名用 place_block
        assertEquals("setblock 1 2 3 oak_planks",
                line("{\"op\":\"place_block\",\"position\":{\"x\":1,\"y\":2,\"z\":3},\"block\":\"oak_planks\"}"));
        // 坐标写成 "(1, 2, 3)"
        assertEquals("setblock 1 2 3 stone",
                line("{\"op\":\"setblock\",\"at\":\"(1, 2, 3)\",\"block\":\"stone\"}"));
        // 平铺的 x1 y1 z1 / x2 y2 z2
        assertEquals("fill 0 0 0 1 1 1 stone",
                line("{\"op\":\"fill\",\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1,\"block\":\"stone\"}"));
        // material 代替 block；box 是 fill 的同义词
        assertEquals("fill 0 0 0 1 1 1 stone",
                line("{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"material\":\"stone\"}"));
    }

    @Test
    void stripsSpacesInsideBlockStates() {
        // 方块状态里的空格会让 BuildOp.parse 把方块名拆断，翻译层必须清掉
        assertEquals("setblock 0 0 0 oak_stairs[facing=north,half=top]",
                line("{\"op\":\"setblock\",\"at\":[0,0,0],\"block\":\"oak_stairs[facing=north, half=top]\"}"));
    }

    @Test
    void acceptsDiameterAndStringHollow() {
        assertEquals("sphere 0 0 0 2 glass",
                line("{\"op\":\"sphere\",\"at\":[0,0,0],\"diameter\":4,\"block\":\"glass\"}"));
        assertEquals("sphere 0 0 0 2 glass hollow",
                line("{\"op\":\"sphere\",\"at\":[0,0,0],\"radius\":2,\"block\":\"glass\",\"hollow\":\"true\"}"));
    }

    @Test
    void acceptsPositionalArrayForm() {
        assertEquals("fill 0 0 0 1 1 1 stone",
                line("[\"fill\",[0,0,0],[1,1,1],\"stone\"]"));
        assertEquals("setblock 0 0 0 stone",
                line("[\"set_block\",[0,0,0],\"stone\"]"));
    }

    @Test
    void acceptsOpNameAsTheOnlyKey() {
        assertEquals("walls 0 0 0 1 3 1 stone",
                line("{\"walls\":{\"from\":[0,0,0],\"to\":[1,3,1],\"block\":\"stone\"}}"));
    }

    @Test
    void acceptsFullCommandInsideAField() {
        assertEquals("fill 0 0 0 1 1 1 stone",
                line("{\"command\":\"fill 0 0 0 1 1 1 stone\"}"));
    }

    @Test
    void signTextAcceptsStringWithPipes() {
        assertEquals("sign 0 0 0 甲 | 乙",
                line("{\"op\":\"sign\",\"at\":[0,0,0],\"text\":\"甲|乙\"}"));
    }

    // ------------------------------------------------------------------
    // 缺失字段 = 跳过这一条，而不是整次失败
    // ------------------------------------------------------------------

    @Test
    void opMissingKeyFieldsIsDropped() {
        assertNull(line("{\"op\":\"fill\",\"to\":[1,1,1],\"block\":\"stone\"}"));
        assertNull(line("{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[1,1,1]}"));
        assertNull(line("{\"op\":\"unknown_thing\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}"));
        assertNull(line("{\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}"));
    }

    // ------------------------------------------------------------------
    // 旧协议与包装
    // ------------------------------------------------------------------

    @Test
    void stillAcceptsLegacyStringCommandArrays() {
        assertEquals(List.of("fill 0 0 0 1 1 1 stone", "setblock 0 0 0 stone"),
                collect("{\"commands\":[\"fill 0 0 0 1 1 1 stone\",\"setblock 0 0 0 stone\"]}"));
    }

    @Test
    void acceptsWholeCommandListAsOneMultilineString() {
        assertEquals(List.of("fill 0 0 0 1 1 1 stone", "setblock 0 0 0 stone"),
                collect("{\"commands\":\"fill 0 0 0 1 1 1 stone\\nsetblock 0 0 0 stone\"}"));
    }

    @Test
    void findsOpsNestedInAContainerObject() {
        assertEquals(List.of("fill 0 0 0 1 1 1 stone"),
                collect("{\"build\":{\"ops\":[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}]}}"));
    }

    @Test
    void findsOpsKeyedByIndex() {
        assertEquals(List.of("setblock 0 0 0 stone", "setblock 1 0 0 stone"),
                collect("{\"ops\":{\"0\":{\"op\":\"setblock\",\"at\":[0,0,0],\"block\":\"stone\"},"
                        + "\"1\":{\"op\":\"setblock\",\"at\":[1,0,0],\"block\":\"stone\"}}}"));
    }

    // ------------------------------------------------------------------
    // success 字段
    // ------------------------------------------------------------------

    @Test
    void onlyExplicitFalseCountsAsFailure() {
        assertTrue(OpJson.isExplicitFailure(json("{\"success\":false,\"reason\":\"x\"}")));
        assertTrue(OpJson.isExplicitFailure(json("{\"success\":\"false\"}")));
        assertTrue(OpJson.isExplicitFailure(json("{\"success\":0}")));
        assertFalse(OpJson.isExplicitFailure(json("{\"success\":true}")));
        assertFalse(OpJson.isExplicitFailure(json("{\"success\":\"true\"}")));
        // 含糊的值按成功处理：宁可去解析 ops，也不要因为一个字段判死整份方案
        assertFalse(OpJson.isExplicitFailure(json("{\"success\":\"maybe\"}")));
        assertFalse(OpJson.isExplicitFailure(json("{\"ops\":[]}")));
    }

    @Test
    void findsNameAndSummaryInNestedContainer() {
        JsonObject root = json("{\"build\":{\"name\":\"角斗场\",\"summary\":\"看台\",\"ops\":[]}}");
        assertEquals("角斗场", OpJson.findString(root, "name"));
        assertEquals("看台", OpJson.findString(root, "summary"));
    }
}
