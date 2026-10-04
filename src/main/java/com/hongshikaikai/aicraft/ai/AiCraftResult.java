package com.hongshikaikai.aicraft.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 返回结果的结构化表示。
 *
 * <p>这个类是「校验」与「构建」的分界线：{@link #parse(JsonObject)} 负责把 AI 的 JSON
 * 校验成合法数据（材质必须是合法原版 ItemType 等），非法即抛 {@link AiException}
 * 触发重试；{@code com.hongshikaikai.aicraft.item.ItemFactory} 再把它变成
 * {@link org.bukkit.inventory.ItemStack}。</p>
 */
public final class AiCraftResult {

    /**
     * 一条属性修饰符。
     *
     * @param attribute 属性名（AI 原文，如 {@code GENERIC_ATTACK_DAMAGE}）
     * @param amount    数值
     * @param operation 运算方式（{@code ADD_NUMBER} / {@code ADD_SCALAR} / {@code MULTIPLY_SCALAR_1}）
     * @param slot      装备槽位组（如 {@code MAINHAND}）
     */
    public record AttributeSpec(String attribute, double amount, String operation, String slot) {
    }

    private final boolean success;
    private final String reason;
    private final Material material;
    private final int amount;
    private final String name;
    private final List<String> lore;
    private final Map<String, Integer> enchantments;
    private final boolean unbreakable;
    private final int customModelData;
    private final boolean glow;
    private final List<AttributeSpec> attributes;
    private final Map<String, JsonElement> components;

    private AiCraftResult(boolean success, String reason, Material material, int amount, String name,
                          List<String> lore, Map<String, Integer> enchantments, boolean unbreakable,
                          int customModelData, boolean glow, List<AttributeSpec> attributes,
                          Map<String, JsonElement> components) {
        this.success = success;
        this.reason = reason;
        this.material = material;
        this.amount = amount;
        this.name = name;
        this.lore = lore;
        this.enchantments = enchantments;
        this.unbreakable = unbreakable;
        this.customModelData = customModelData;
        this.glow = glow;
        this.attributes = attributes;
        this.components = components;
    }

    /**
     * 构造一个「合成失败」结果（AI 明确返回 {@code success: false}）。
     *
     * @param reason 失败原因
     * @return 结果
     */
    public static AiCraftResult failure(String reason) {
        return new AiCraftResult(false, reason, null, 0, null, List.of(), Map.of(), false, 0, false,
                List.of(), Map.of());
    }

    /**
     * 解析并校验 AI 返回的 JSON 对象。
     *
     * @param json AI 返回的根对象
     * @return 解析结果
     * @throws AiException 结构错误（缺字段、材质非法等）时抛出，调用方应重试
     */
    public static AiCraftResult parse(JsonObject json) {
        if (json == null) {
            throw new AiException("AI 返回内容不是 JSON 对象");
        }

        boolean success = optBoolean(json, "success", false);
        if (!success) {
            return failure(optString(json, "reason", "AI 无法用这些材料合成物品"));
        }

        String materialName = optString(json, "material", "");
        Material material = matchItem(materialName);
        if (material == null) {
            throw new AiException("AI 返回了非法的材质名: " + materialName);
        }

        int amount = optInt(json, "amount", 1);
        if (amount < 1) {
            amount = 1;
        }
        if (amount > 64) {
            amount = 64;
        }

        String name = optString(json, "name", null);

        List<String> lore = new ArrayList<>();
        JsonElement loreElement = json.get("lore");
        if (loreElement != null && loreElement.isJsonArray()) {
            for (JsonElement line : loreElement.getAsJsonArray()) {
                if (line != null && !line.isJsonNull()) {
                    lore.add(line.getAsString());
                }
            }
        }

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        JsonElement enchantElement = json.get("enchantments");
        if (enchantElement != null && enchantElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : enchantElement.getAsJsonObject().entrySet()) {
                try {
                    int level = entry.getValue().getAsInt();
                    if (level > 0) {
                        enchantments.put(entry.getKey(), Math.min(level, 255));
                    }
                } catch (RuntimeException ignored) {
                    // 单个附魔解析失败不影响整体，跳过即可
                }
            }
        }

        boolean unbreakable = optBoolean(json, "unbreakable", false);
        int customModelData = optInt(json, "custom_model_data", 0);
        boolean glow = optBoolean(json, "glow", false);

        List<AttributeSpec> attributes = new ArrayList<>();
        JsonElement attributeElement = json.get("attributes");
        if (attributeElement != null && attributeElement.isJsonArray()) {
            for (JsonElement element : attributeElement.getAsJsonArray()) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject attribute = element.getAsJsonObject();
                String attributeName = optString(attribute, "attribute", "");
                if (attributeName.isBlank()) {
                    continue;
                }
                attributes.add(new AttributeSpec(
                        attributeName,
                        optDouble(attribute, "amount", 0.0D),
                        optString(attribute, "operation", "ADD_NUMBER"),
                        optString(attribute, "slot", "ANY")));
            }
        }

        Map<String, JsonElement> components = new LinkedHashMap<>();
        JsonElement componentElement = json.get("components");
        if (componentElement != null && componentElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : componentElement.getAsJsonObject().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isJsonNull()) {
                    components.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
        }

        return new AiCraftResult(true, null, material, amount, name, List.copyOf(lore),
                Collections.unmodifiableMap(enchantments), unbreakable, customModelData, glow,
                List.copyOf(attributes), Collections.unmodifiableMap(components));
    }

    /**
     * 把 AI 给的材质名解析为合法的原版物品材质。
     *
     * <p>会做几层容错：去掉命名空间前缀、统一大写、下划线化，并拒绝空气 / 非物品材质。</p>
     *
     * @param raw 原始材质名
     * @return 合法材质，非法时返回 null
     */
    public static Material matchItem(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.isEmpty()) {
            return null;
        }
        Material material;
        try {
            material = Material.matchMaterial(name);
        } catch (RuntimeException ex) {
            return null;
        }
        if (material == null || material == Material.AIR || !material.isItem()) {
            return null;
        }
        return material;
    }

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

    private static int optInt(JsonObject json, String key, int fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static double optDouble(JsonObject json, String key, double fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean optBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    public boolean success() {
        return success;
    }

    public String reason() {
        return reason;
    }

    public Material material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    public String name() {
        return name;
    }

    public List<String> lore() {
        return lore;
    }

    public Map<String, Integer> enchantments() {
        return enchantments;
    }

    public boolean unbreakable() {
        return unbreakable;
    }

    public int customModelData() {
        return customModelData;
    }

    public boolean glow() {
        return glow;
    }

    public List<AttributeSpec> attributes() {
        return attributes;
    }

    public Map<String, JsonElement> components() {
        return components;
    }

    /**
     * 用于日志的简短描述。
     *
     * @return 描述文本
     */
    public String describe() {
        if (!success) {
            return "failure(" + reason + ")";
        }
        return material + " x" + amount + (name == null ? "" : " name=" + name);
    }
}
