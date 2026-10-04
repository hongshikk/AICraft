package com.hongshikaikai.aicraft.item;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.ai.AiCraftResult;
import com.hongshikaikai.aicraft.config.PluginConfig;
import com.hongshikaikai.aicraft.util.Text;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.AttackRange;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.DamageResistant;
import io.papermc.paper.datacomponent.item.Enchantable;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.FoodProperties;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.Tool;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.papermc.paper.datacomponent.item.UseCooldown;
import io.papermc.paper.datacomponent.item.Weapon;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import io.papermc.paper.registry.set.RegistryKeySet;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.TriState;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockType;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把 {@link AiCraftResult} 构建成 {@link ItemStack}。
 *
 * <p>构建流程分两部分：</p>
 * <ol>
 *   <li><b>插件自管字段</b>（name / lore / enchantments / attributes / glow / unbreakable /
 *       custom_model_data / amount）走各自的专用 API，因为它们有自定义逻辑
 *       （颜色代码、自创附魔 PDC、别名映射等）。</li>
 *   <li><b>{@code components} 字段</b>走<b>类型化数据组件映射</b>：
 *       26.3 的 {@code DataComponentType} 带类型参数（{@code Valued<T>} / {@code NonValued}），
 *       没有「按名字取 Codec 通用解码」的入口，所以这里逐个组件显式映射
 *       （见 {@link #applyComponent}，可用键与 {@code AICraft.COMPONENT_REFERENCE} 一致），
 *       再用 {@link ItemStack#setData} 应用。</li>
 * </ol>
 *
 * <p>任何单个组件设置失败都只会被跳过并记一条日志，不会让整次合成失败——
 * AI 给出的组件未必适用于该材质（例如给石头发「不可破坏」）。</p>
 */
public final class ItemFactory {

    /** 自创附魔在物品持久化数据里的键名（命名空间为插件名）。 */
    public static final String CUSTOM_ENCHANTMENTS_KEY = "custom_enchantments";

    /** 旧版（1.21.2 之前）附魔名到现代命名空间键的别名表。 */
    private static final Map<String, String> ENCHANTMENT_ALIASES = Map.ofEntries(
            Map.entry("damage_all", "sharpness"),
            Map.entry("durability", "unbreaking"),
            Map.entry("arrow_damage", "power"),
            Map.entry("arrow_knockback", "punch"),
            Map.entry("arrow_fire", "flame"),
            Map.entry("arrow_infinite", "infinity"),
            Map.entry("dig_speed", "efficiency"),
            Map.entry("loot_bonus_blocks", "fortune"),
            Map.entry("loot_bonus_mobs", "looting"),
            Map.entry("protection_environmental", "protection"),
            Map.entry("protection_explosions", "blast_protection"),
            Map.entry("protection_fall", "feather_falling"),
            Map.entry("protection_fire", "fire_protection"),
            Map.entry("protection_projectile", "projectile_protection"),
            Map.entry("water_worker", "aqua_affinity"),
            Map.entry("oxygen", "respiration"),
            Map.entry("luck", "luck_of_the_sea"));

    private ItemFactory() {
    }

    /**
     * 构建物品。
     *
     * @param result AI 结果（必须 {@link AiCraftResult#success()} 为 true）
     * @param plugin 插件实例，用于生成属性修饰符的 {@link NamespacedKey}
     * @return 构建好的物品
     */
    public static ItemStack build(AiCraftResult result, AICraft plugin) {
        if (!result.success() || result.material() == null) {
            throw new IllegalArgumentException("不能从失败结果构建物品");
        }
        PluginConfig config = plugin.pluginConfig();

        ItemStack stack = new ItemStack(result.material(), 1);

        // 1) 拆附魔：能对上原版注册表的真实生效，对不上的作为「自创附魔」保留下来。
        EnchantmentSplit enchantments = splitEnchantments(result.enchantments(), plugin);

        // 2) 自创附魔写进持久化数据（aicraft:custom_enchantments）。
        //    放在最前面：editMeta 会做一次 ItemMeta 往返，先做它就不会覆盖后面设的数据组件。
        if (config.customEnchantmentsStoreInPdc() && !enchantments.custom().isEmpty()) {
            setQuietly(plugin, "custom_enchantments(pdc)",
                    () -> storeCustomEnchantments(stack, enchantments.custom(), plugin));
        }

        // 3) 「components」里的额外组件：走通用数据组件通道（含 max_stack_size 等
        //    影响后续行为的关键项，所以必须在设置数量之前）。
        applyExtraComponents(stack, result.components(), plugin);

        // 4) 名称（支持 & 颜色代码）
        if (result.name() != null && !result.name().isBlank()) {
            setQuietly(plugin, "CUSTOM_NAME", () -> stack.setData(DataComponentTypes.CUSTOM_NAME, Text.component(result.name())));
        }

        // 5) Lore = AI 给的 lore + 自创附魔的展示行
        List<Component> lines = new ArrayList<>(result.lore().size() + enchantments.custom().size());
        for (String line : result.lore()) {
            lines.add(Text.component(line));
        }
        if (config.customEnchantmentsShowInLore()) {
            for (Map.Entry<String, Integer> entry : enchantments.custom().entrySet()) {
                lines.add(Text.component(config.customEnchantmentsLoreFormat()
                        .replace("{name}", displayEnchantmentName(entry.getKey()))
                        .replace("{level}", String.valueOf(entry.getValue()))));
            }
        }
        if (!lines.isEmpty()) {
            setQuietly(plugin, "LORE", () -> stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines)));
        }

        // 6) 原版附魔（真实生效）
        if (!enchantments.vanilla().isEmpty()) {
            setQuietly(plugin, "ENCHANTMENTS", () -> stack.setData(DataComponentTypes.ENCHANTMENTS,
                    ItemEnchantments.itemEnchantments(enchantments.vanilla())));
        }

        // 7) 发光：新版直接写 ENCHANTMENT_GLINT_OVERRIDE，
        //    等价于规格里说的「无附魔时附加隐藏附魔 + HIDE_ENCHANTS」，但更干净、且支持关掉附魔光效。
        if (result.glow()) {
            setQuietly(plugin, "ENCHANTMENT_GLINT_OVERRIDE",
                    () -> stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, Boolean.TRUE));
        }

        // 8) 不可破坏
        if (result.unbreakable()) {
            setQuietly(plugin, "UNBREAKABLE", () -> stack.setData(DataComponentTypes.UNBREAKABLE));
        }

        // 9) 自定义模型数据（1.21.4+ 的 CUSTOM_MODEL_DATA 是一个浮点列表）
        if (result.customModelData() != 0) {
            setQuietly(plugin, "CUSTOM_MODEL_DATA", () -> stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(result.customModelData()).build()));
        }

        // 10) 属性修饰符
        ItemAttributeModifiers modifiers = buildAttributeModifiers(result.attributes(), plugin);
        if (modifiers != null && !modifiers.modifiers().isEmpty()) {
            setQuietly(plugin, "ATTRIBUTE_MODIFIERS",
                    () -> stack.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, modifiers));
        }

        // 11) 数量：受 MAX_STACK_SIZE 组件限制
        int maxStackSize = Math.max(1, stack.getMaxStackSize());
        stack.setAmount(Math.max(1, Math.min(result.amount(), maxStackSize)));

        return stack;
    }

    // ------------------------------------------------------------------
    // components：常用数据组件的类型化映射
    // ------------------------------------------------------------------

    /**
     * 把 AI 的 {@code components} 字段逐个映射到对应的数据组件。
     *
     * <p>26.3 的 {@code DataComponentType} 是<b>带类型参数</b>的
     * （{@code DataComponentType.Valued<T>} / {@code NonValued}），API 没有
     * 「按名字取 Codec 再通用解码」的入口，因此这里对提示词里列出的组件做显式映射。
     * 键名写错、或值不适用于该材质时只会被跳过并记一条 FINE 日志，不会让整次合成失败。</p>
     *
     * @param stack      目标物品
     * @param components AI 给出的组件表
     * @param plugin     插件实例（日志用）
     */
    private static void applyExtraComponents(ItemStack stack, Map<String, JsonElement> components, AICraft plugin) {
        if (components == null || components.isEmpty()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : components.entrySet()) {
            String key = normalizeComponentKey(entry.getKey());
            if (key.isEmpty()) {
                continue;
            }
            JsonElement value = entry.getValue();
            setQuietly(plugin, key, () -> applyComponent(stack, key, value, plugin));
        }
    }

    /**
     * 单个组件的类型化应用。
     *
     * @param stack 目标物品
     * @param key   已规整的组件键名（小写、无命名空间）
     * @param value AI 给的 JSON 值
     * @param plugin 插件实例（日志用）
     */
    private static void applyComponent(ItemStack stack, String key, JsonElement value, AICraft plugin) {
        switch (key) {
            case "max_stack_size" -> stack.setData(DataComponentTypes.MAX_STACK_SIZE, clamp(asInt(value, 1), 1, 99));
            case "max_damage" -> stack.setData(DataComponentTypes.MAX_DAMAGE, Math.max(1, asInt(value, 1)));
            case "damage" -> stack.setData(DataComponentTypes.DAMAGE, Math.max(0, asInt(value, 0)));
            case "repair_cost" -> stack.setData(DataComponentTypes.REPAIR_COST, Math.max(0, asInt(value, 0)));
            case "rarity" -> {
                ItemRarity rarity = parseRarity(asString(value, ""));
                if (rarity != null) {
                    stack.setData(DataComponentTypes.RARITY, rarity);
                }
            }
            case "enchantable" -> stack.setData(DataComponentTypes.ENCHANTABLE,
                    Enchantable.enchantable(Math.max(0, asInt(field(value, "value"), 1))));
            case "enchantment_glint_override" ->
                    stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, asBoolean(value, true));
            case "unbreakable" -> setUnit(stack, DataComponentTypes.UNBREAKABLE, value);
            case "glider" -> setUnit(stack, DataComponentTypes.GLIDER, value);
            case "intangible_projectile" -> setUnit(stack, DataComponentTypes.INTANGIBLE_PROJECTILE, value);
            case "fire_resistant" -> {
                if (asBoolean(value, false)) {
                    RegistryKeySet<DamageType> fireTypes = damageTypeRegistry().getTag(DamageTypeTagKeys.IS_FIRE);
                    stack.setData(DataComponentTypes.DAMAGE_RESISTANT, DamageResistant.damageResistant(fireTypes));
                }
            }
            case "hide_tooltip" -> {
                if (asBoolean(value, false)) {
                    stack.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                            TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
                }
            }
            case "item_model" -> withKey(asString(value, ""),
                    k -> stack.setData(DataComponentTypes.ITEM_MODEL, k));
            case "tooltip_style" -> withKey(asString(value, ""),
                    k -> stack.setData(DataComponentTypes.TOOLTIP_STYLE, k));
            case "custom_model_data" ->
                    stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, buildCustomModelData(value));
            case "custom_name" -> stack.setData(DataComponentTypes.CUSTOM_NAME, Text.component(asString(value, "")));
            case "lore" -> {
                List<Component> lines = new ArrayList<>();
                if (value != null && value.isJsonArray()) {
                    for (JsonElement element : value.getAsJsonArray()) {
                        lines.add(Text.component(asString(element, "")));
                    }
                }
                stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
            }
            case "food" -> stack.setData(DataComponentTypes.FOOD, buildFood(value));
            case "weapon" -> stack.setData(DataComponentTypes.WEAPON, buildWeapon(value));
            case "attack_range" -> stack.setData(DataComponentTypes.ATTACK_RANGE, buildAttackRange(value));
            case "use_cooldown" -> stack.setData(DataComponentTypes.USE_COOLDOWN, buildUseCooldown(value));
            case "equippable" -> applyEquippable(stack, value);
            case "tool" -> applyTool(stack, value, plugin);
            default -> plugin.getLogger().fine("忽略 AI 返回的未知组件: " + key);
        }
    }

    /** 单位组件（unbreakable / glider 等）：值为 true 时写入，false 时移除。 */
    private static void setUnit(ItemStack stack, DataComponentType.NonValued type, JsonElement value) {
        if (asBoolean(value, true)) {
            stack.setData(type);
        } else {
            stack.unsetData(type);
        }
    }

    /**
     * 把 AI 写的键名规整成组件键的形状：小写、空格/连字符转下划线、去掉 {@code minecraft:} 前缀。
     *
     * @param raw 原始键名
     * @return 规整后的键名（空串表示无效）
     */
    private static String normalizeComponentKey(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        return key;
    }

    // ------------------------------------------------------------------
    // 各数据组件的构造
    // ------------------------------------------------------------------

    private static CustomModelData buildCustomModelData(JsonElement value) {
        CustomModelData.Builder builder = CustomModelData.customModelData();
        if (value != null && value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                builder.addFloat(asFloat(element, 0f));
            }
        } else {
            builder.addFloat(asFloat(value, 0f));
        }
        return builder.build();
    }

    private static FoodProperties buildFood(JsonElement value) {
        return FoodProperties.food()
                .nutrition(Math.max(0, asInt(field(value, "nutrition"), 0)))
                .saturation(Math.max(0f, asFloat(field(value, "saturation"), 0f)))
                .canAlwaysEat(asBoolean(field(value, "can_always_eat"), false))
                .build();
    }

    private static Weapon buildWeapon(JsonElement value) {
        return Weapon.weapon()
                .itemDamagePerAttack(asInt(field(value, "item_damage_per_attack"), 1))
                .disableBlockingForSeconds(asFloat(field(value, "disable_blocking_for_seconds"), 0f))
                .build();
    }

    private static AttackRange buildAttackRange(JsonElement value) {
        return AttackRange.attackRange()
                .minReach(Math.max(0f, asFloat(field(value, "min_reach"), 0f)))
                .maxReach(Math.max(0f, asFloat(field(value, "max_reach"), 3f)))
                .minCreativeReach(Math.max(0f, asFloat(field(value, "min_creative_reach"), 0f)))
                .maxCreativeReach(Math.max(0f, asFloat(field(value, "max_creative_reach"), 5f)))
                .hitboxMargin(Math.max(0f, asFloat(field(value, "hitbox_margin"), 0.3f)))
                .mobFactor(Math.max(0f, asFloat(field(value, "mob_factor"), 1f)))
                .build();
    }

    private static UseCooldown buildUseCooldown(JsonElement value) {
        UseCooldown.Builder builder;
        if (value != null && value.isJsonObject()) {
            builder = UseCooldown.useCooldown(Math.max(0f, asFloat(field(value, "seconds"), 0f)));
            Key group = parseKey(asString(field(value, "cooldown_group"), ""));
            if (group != null) {
                builder.cooldownGroup(group);
            }
        } else {
            builder = UseCooldown.useCooldown(Math.max(0f, asFloat(value, 0f)));
        }
        return builder.build();
    }

    private static void applyEquippable(ItemStack stack, JsonElement value) {
        EquipmentSlot slot = parseSlot(asString(field(value, "slot"), ""));
        if (slot == null) {
            return;
        }
        Equippable.Builder builder = Equippable.equippable(slot);
        Key sound = parseKey(asString(field(value, "equip_sound"), ""));
        if (sound != null) {
            builder.equipSound(sound);
        }
        Key asset = parseKey(asString(field(value, "asset_id"), ""));
        if (asset != null) {
            builder.assetId(asset);
        }
        if (value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("dispensable")) {
                builder.dispensable(asBoolean(field(value, "dispensable"), true));
            }
            if (object.has("swappable")) {
                builder.swappable(asBoolean(field(value, "swappable"), true));
            }
            if (object.has("damage_on_hurt")) {
                builder.damageOnHurt(asBoolean(field(value, "damage_on_hurt"), true));
            }
            if (object.has("equip_on_interact")) {
                builder.equipOnInteract(asBoolean(field(value, "equip_on_interact"), false));
            }
        }
        stack.setData(DataComponentTypes.EQUIPPABLE, builder.build());
    }

    private static void applyTool(ItemStack stack, JsonElement value, AICraft plugin) {
        Tool.Builder builder = Tool.tool()
                .defaultMiningSpeed(Math.max(0f, asFloat(field(value, "default_mining_speed"), 1f)))
                .damagePerBlock(Math.max(0, asInt(field(value, "damage_per_block"), 1)));
        JsonElement rules = field(value, "rules");
        if (rules != null && rules.isJsonArray()) {
            for (JsonElement element : rules.getAsJsonArray()) {
                Tool.Rule rule = buildToolRule(element, plugin);
                if (rule != null) {
                    builder.addRule(rule);
                }
            }
        }
        stack.setData(DataComponentTypes.TOOL, builder.build());
    }

    private static Tool.Rule buildToolRule(JsonElement element, AICraft plugin) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        RegistryKeySet<BlockType> blocks = blockTag(asString(field(element, "blocks"), ""), plugin);
        if (blocks == null) {
            return null;
        }
        TriState correctForDrops = TriState.NOT_SET;
        JsonElement correct = field(element, "correct_for_drops");
        if (correct != null && correct.isJsonPrimitive() && correct.getAsJsonPrimitive().isBoolean()) {
            correctForDrops = correct.getAsBoolean() ? TriState.TRUE : TriState.FALSE;
        }
        JsonElement speedElement = field(element, "speed");
        Float speed = speedElement == null || speedElement.isJsonNull() ? null : asFloat(speedElement, 1f);
        return Tool.rule(blocks, speed, correctForDrops);
    }

    /**
     * 把 {@code #minecraft:mineable/pickaxe} 这样的方块标签解析成 {@link RegistryKeySet}。
     *
     * <p>26.3 的 API 没有「用一组方块名拼 RegistryKeySet」的公开入口，所以这里只支持标签写法；
     * 直接写方块名会被跳过并记一条日志。</p>
     *
     * @param raw    AI 写的 blocks 字段
     * @param plugin 插件实例（日志用）
     * @return 方块标签集合，无法解析时返回 null
     */
    private static RegistryKeySet<BlockType> blockTag(String raw, AICraft plugin) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (!text.startsWith("#")) {
            plugin.getLogger().fine("tool.rules.blocks 目前只支持标签写法（形如 #minecraft:mineable/pickaxe），已跳过: " + text);
            return null;
        }
        try {
            TagKey<BlockType> tagKey = TagKey.create(RegistryKey.BLOCK, text.substring(1));
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK).getTag(tagKey);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ItemRarity parseRarity(String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (ItemRarity rarity : ItemRarity.values()) {
            if (rarity.name().equals(name)) {
                return rarity;
            }
        }
        return null;
    }

    private static EquipmentSlot parseSlot(String raw) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (name.isEmpty()) {
            return null;
        }
        if ("MAINHAND".equals(name)) {
            return EquipmentSlot.HAND;
        }
        if ("OFFHAND".equals(name)) {
            return EquipmentSlot.OFF_HAND;
        }
        try {
            return EquipmentSlot.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Key parseKey(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return text.contains(":") ? Key.key(text) : Key.key("minecraft", text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void withKey(String raw, Consumer<Key> consumer) {
        Key key = parseKey(raw);
        if (key != null) {
            consumer.accept(key);
        }
    }

    // ------------------------------------------------------------------
    // 附魔
    // ------------------------------------------------------------------

    /**
     * 附魔拆分结果。
     *
     * @param vanilla 能在原版注册表里找到的附魔（真实生效）
     * @param custom  找不到的原版附魔，即 AI 自创的附魔名（只做展示 + 存档）
     */
    private record EnchantmentSplit(Map<Enchantment, Integer> vanilla, Map<String, Integer> custom) {
    }

    /**
     * 把 AI 给的附魔拆成「原版」与「自创」两组。
     *
     * <p>提示词已经明确告诉 AI：附魔名可以随便编。为了不让自创附魔被静默丢弃，
     * 对不上原版注册表的名字会被保留下来 —— 见 {@link #storeCustomEnchantments} 与
     * {@link #build} 里往 Lore 追加的展示行。</p>
     *
     * @param raw    AI 给出的附魔表
     * @param plugin 插件实例（日志用）
     * @return 拆分结果
     */
    private static EnchantmentSplit splitEnchantments(Map<String, Integer> raw, AICraft plugin) {
        Map<Enchantment, Integer> vanilla = new LinkedHashMap<>();
        Map<String, Integer> custom = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return new EnchantmentSplit(vanilla, custom);
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                continue;
            }
            int level = clamp(entry.getValue() == null ? 1 : entry.getValue(), 1, 255);
            Enchantment enchantment = resolveEnchantment(name);
            if (enchantment != null) {
                vanilla.put(enchantment, level);
            } else {
                // 自创附魔：原样保留（去掉首尾空白），由调用方决定怎么展示 / 存档
                custom.put(name.trim(), level);
                plugin.getLogger().fine("自创附魔（无原版效果，仅展示）: " + name + " " + level);
            }
        }
        return new EnchantmentSplit(vanilla, custom);
    }

    /**
     * 把自创附魔写进物品的持久化数据，键为 {@code aicraft:custom_enchantments}，
     * 值为 {@code 名字=等级} 的字符串列表，方便其它插件读取。
     *
     * @param stack  目标物品
     * @param custom 自创附魔
     * @param plugin 插件实例（提供 {@link NamespacedKey} 命名空间）
     */
    private static void storeCustomEnchantments(ItemStack stack, Map<String, Integer> custom, AICraft plugin) {
        if (custom.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<>(custom.size());
        for (Map.Entry<String, Integer> entry : custom.entrySet()) {
            entries.add(entry.getKey() + "=" + entry.getValue());
        }
        NamespacedKey key = new NamespacedKey(plugin, CUSTOM_ENCHANTMENTS_KEY);
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(key, PersistentDataType.LIST.strings(), entries));
    }

    /**
     * 自创附魔在 Lore 里的展示名。
     *
     * <p>纯 ASCII 下划线命名（如 {@code SOUL_HARVEST}）会美化成 {@code Soul Harvest}；
     * 其它写法（中文、已经带 {@code &} 颜色代码等）原样保留，不做任何大小写改动。</p>
     *
     * @param raw 原始附魔名
     * @return 展示名
     */
    private static String displayEnchantmentName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!name.matches("[A-Za-z0-9_ ]+")) {
            return name;
        }
        StringBuilder builder = new StringBuilder();
        for (String word : name.toLowerCase(Locale.ROOT).split("[_ ]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    /**
     * 按名字解析原版附魔（走注册表，兼容 {@code SHARPNESS} / {@code sharpness} /
     * {@code minecraft:sharpness} 三种写法）。
     *
     * @param raw 原始名称
     * @return 附魔，不是原版附魔时返回 null
     */
    private static Enchantment resolveEnchantment(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (name.isEmpty()) {
            return null;
        }
        String mapped = ENCHANTMENT_ALIASES.getOrDefault(name, name);
        try {
            if (mapped.contains(":")) {
                NamespacedKey key = NamespacedKey.fromString(mapped);
                return key == null ? null : enchantmentRegistry().get(key);
            }
            return enchantmentRegistry().get(NamespacedKey.minecraft(mapped));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 附魔注册表。
     *
     * <p>{@code Registry.ENCHANTMENT} 这类静态字段自 1.21 起已过时，
     * 现代写法是 {@link RegistryAccess#getRegistry(RegistryKey)}。</p>
     *
     * @return 附魔注册表
     */
    public static Registry<Enchantment> enchantmentRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    }

    /**
     * 伤害类型注册表（{@code DAMAGE_RESISTANT} 组件需要）。
     *
     * @return 伤害类型注册表
     */
    public static Registry<DamageType> damageTypeRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE);
    }

    // ------------------------------------------------------------------
    // 属性修饰符
    // ------------------------------------------------------------------

    private static ItemAttributeModifiers buildAttributeModifiers(List<AiCraftResult.AttributeSpec> specs, AICraft plugin) {
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        int index = 0;
        for (AiCraftResult.AttributeSpec spec : specs) {
            Attribute attribute = resolveAttribute(spec.attribute());
            if (attribute == null) {
                plugin.getLogger().fine("忽略未知属性: " + spec.attribute());
                continue;
            }
            EquipmentSlotGroup group = resolveSlotGroup(spec.slot());
            AttributeModifier.Operation operation = resolveOperation(spec.operation());
            NamespacedKey key = new NamespacedKey(plugin, "ai_modifier_" + index);
            AttributeModifier modifier = new AttributeModifier(key, spec.amount(), operation, group);
            builder.addModifier(attribute, modifier, group);
            index++;
        }
        return builder.build();
    }

    /**
     * 解析属性名。
     *
     * <p>AI 常按 1.21.2 之前的写法给出 {@code GENERIC_ATTACK_DAMAGE}，
     * 而现代注册表键是 {@code attack_damage}，这里统一剥掉旧前缀。</p>
     *
     * @param raw 原始属性名
     * @return 属性，未找到返回 null
     */
    private static Attribute resolveAttribute(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (String prefix : new String[]{"generic_", "player_", "zombie_", "horse_", "animal_"}) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        try {
            if (name.contains(":")) {
                NamespacedKey key = NamespacedKey.fromString(name);
                return key == null ? null : attributeRegistry().get(key);
            }
            return attributeRegistry().get(NamespacedKey.minecraft(name));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 属性注册表。
     *
     * @return 属性注册表
     */
    public static Registry<Attribute> attributeRegistry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE);
    }

    private static EquipmentSlotGroup resolveSlotGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            return EquipmentSlotGroup.ANY;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            EquipmentSlotGroup group = EquipmentSlotGroup.getByName(name);
            return group == null ? EquipmentSlotGroup.ANY : group;
        } catch (RuntimeException ex) {
            return EquipmentSlotGroup.ANY;
        }
    }

    private static AttributeModifier.Operation resolveOperation(String raw) {
        if (raw == null || raw.isBlank()) {
            return AttributeModifier.Operation.ADD_NUMBER;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return AttributeModifier.Operation.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return AttributeModifier.Operation.ADD_NUMBER;
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private interface ComponentAction {
        void run();
    }

    private static void setQuietly(AICraft plugin, String what, ComponentAction action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            plugin.getLogger().fine("数据组件 " + what + " 应用失败（已跳过）: " + ex.getMessage());
        }
    }

    /** 取嵌套对象的字段；值不是对象时返回 null。 */
    private static JsonElement field(JsonElement value, String key) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        return value.getAsJsonObject().get(key);
    }

    private static String asString(JsonElement element, String fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsString();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static float asFloat(JsonElement element, float fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsFloat();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int asInt(JsonElement element, int fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static boolean asBoolean(JsonElement element, boolean fallback) {
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}