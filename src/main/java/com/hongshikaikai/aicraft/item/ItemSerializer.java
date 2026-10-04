package com.hongshikaikai.aicraft.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.hongshikaikai.aicraft.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;

/**
 * 把玩家放进合成区的 {@link ItemStack} 序列化成发给 AI 的 user message。
 *
 * <p>输出结构与规格 4.3 一致：</p>
 * <pre>
 * {
 *   "materials": [
 *     { "material": "DIAMOND", "amount": 1, "name": null, "lore": [], "enchantments": {}, "components": {} }
 *   ]
 * }
 * </pre>
 *
 * <p>读取全部走新版数据组件 API（{@code ItemStack#getData / hasData}），
 * 不再依赖已过时的 {@code ItemMeta}。</p>
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    /**
     * 序列化合成区材料。
     *
     * @param materials 材料列表（必须是原物品的副本信息即可，方法只读）
     * @return user message 的 JSON 对象
     */
    public static JsonObject toPayload(Collection<ItemStack> materials) {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        if (materials != null) {
            for (ItemStack item : materials) {
                if (item == null || item.getType().isAir()) {
                    continue;
                }
                array.add(describe(item));
            }
        }
        root.add("materials", array);
        return root;
    }

    /**
     * 描述单个物品。
     *
     * @param item 物品
     * @return JSON 对象
     */
    private static JsonObject describe(ItemStack item) {
        JsonObject json = new JsonObject();
        json.addProperty("material", item.getType().name());
        json.addProperty("amount", item.getAmount());

        // 显示名：优先 CUSTOM_NAME（铁砧改名），退回 ITEM_NAME
        Component name = item.getData(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            name = item.getData(DataComponentTypes.ITEM_NAME);
        }
        if (name == null) {
            json.add("name", JsonNull.INSTANCE);
        } else {
            json.addProperty("name", Text.toAmpersand(name));
        }

        // Lore
        JsonArray lore = new JsonArray();
        ItemLore itemLore = item.getData(DataComponentTypes.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.add(Text.toAmpersand(line));
            }
        }
        json.add("lore", lore);

        // 附魔
        JsonObject enchantments = new JsonObject();
        ItemEnchantments itemEnchantments = item.getData(DataComponentTypes.ENCHANTMENTS);
        if (itemEnchantments != null) {
            for (Map.Entry<Enchantment, Integer> entry : itemEnchantments.enchantments().entrySet()) {
                enchantments.addProperty(keyOf(entry.getKey()), entry.getValue());
            }
        }
        json.add("enchantments", enchantments);

        // 其它数据组件（只挑对「价值判断」有用的几项，避免 prompt 过长）
        JsonObject components = new JsonObject();
        if (item.hasData(DataComponentTypes.MAX_STACK_SIZE)) {
            components.addProperty("max_stack_size", item.getData(DataComponentTypes.MAX_STACK_SIZE));
        }
        if (item.hasData(DataComponentTypes.MAX_DAMAGE)) {
            components.addProperty("max_damage", item.getData(DataComponentTypes.MAX_DAMAGE));
        }
        if (item.hasData(DataComponentTypes.DAMAGE)) {
            components.addProperty("damage", item.getData(DataComponentTypes.DAMAGE));
        }
        if (item.hasData(DataComponentTypes.UNBREAKABLE)) {
            components.addProperty("unbreakable", true);
        }
        if (item.hasData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)) {
            Boolean glint = item.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
            components.addProperty("enchantment_glint_override", glint != null && glint);
        }
        if (item.hasData(DataComponentTypes.RARITY)) {
            ItemRarity rarity = item.getData(DataComponentTypes.RARITY);
            if (rarity != null) {
                components.addProperty("rarity", rarity.name());
            }
        }
        CustomModelData modelData = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData != null && !modelData.floats().isEmpty()) {
            components.addProperty("custom_model_data", modelData.floats().get(0));
        }
        json.add("components", components);

        return json;
    }

    private static String keyOf(Enchantment enchantment) {
        try {
            return enchantment.getKey().getKey();
        } catch (RuntimeException ex) {
            return enchantment.toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 判断一个物品是否为空气。
     *
     * @param item 物品，可为 null
     * @return 是空气 / null 时返回 true
     */
    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }
}
