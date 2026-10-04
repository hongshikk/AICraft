package com.hongshikaikai.aicraft;

import com.google.gson.JsonObject;
import com.hongshikaikai.aicraft.ai.AiClient;
import com.hongshikaikai.aicraft.ai.AiCraftResult;
import com.hongshikaikai.aicraft.build.BuildService;
import com.hongshikaikai.aicraft.command.BuildCommand;
import com.hongshikaikai.aicraft.command.CraftCommand;
import com.hongshikaikai.aicraft.config.PluginConfig;
import com.hongshikaikai.aicraft.gui.CraftGui;
import com.hongshikaikai.aicraft.gui.CraftHolder;
import com.hongshikaikai.aicraft.gui.CraftListener;
import com.hongshikaikai.aicraft.item.ItemFactory;
import com.hongshikaikai.aicraft.item.ItemSerializer;
import com.hongshikaikai.aicraft.util.ItemStacks;
import com.hongshikaikai.aicraft.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AICraft 主类。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>注册命令 {@code /aicraft}（别名 {@code /aic}）、{@code /aibuild}（别名 {@code /aib}）与界面监听器；</li>
 *   <li>维护「在途请求」；</li>
 *   <li>负责合成流程的编排 —— 所有涉及背包 / 玩家 / 世界的操作都在主线程执行，
 *       网络请求异步，回调后用 {@link org.bukkit.scheduler.BukkitScheduler#runTask}
 *       切回主线程（规格六.1）；</li>
 *   <li>AI 建造（{@code /aibuild}）的整体编排交给 {@link BuildService}。</li>
 * </ul>
 */
public final class AICraft extends JavaPlugin {

    // ------------------------------------------------------------------
    // 物品组件参考
    // ------------------------------------------------------------------

    /** 用来避免重复注入组件清单的标记。 */
    private static final String COMPONENT_REFERENCE_MARKER = "===== 可用的 components 键";

    /**
     * 全部可用的物品组件清单（Java 版 26.3 数据组件）。
     *
     * <p>这段文本会在 {@link #loadPrompt()} 里自动拼到系统提示词末尾，
     * 保证无论提示词来自 {@code config.yml}、{@code prompt.txt} 还是兜底常量，
     * AI 都能知道所有可写的 {@code components} 键。</p>
     *
     * <p>26.3 的 {@code DataComponentType} 带类型参数，没有「按名字取 Codec 再通用解码」
     * 的入口，所以 {@code ItemFactory} 对这些组件做了显式映射：<b>只有下面列出的键会生效</b>。</p>
     */
    private static final String COMPONENT_REFERENCE = """

            ===== 可用的 components 键（Java 版 26.3 已支持的子集）=====
            [基础] max_stack_size(1-99), max_damage, damage, repair_cost, rarity,
                   custom_model_data, unbreakable, glider, intangible_projectile,
                   enchantable, enchantment_glint_override
            [外观] custom_name, lore, item_model, tooltip_style, hide_tooltip, fire_resistant
            [食物] food
            [战斗] weapon, attack_range, tool
            [装备] equippable
            [其它] use_cooldown

            值的类型按原版数据组件格式书写，示例：
              "max_stack_size": 1
              "unbreakable": true
              "rarity": "epic"
              "enchantment_glint_override": true
              "enchantable": 10
              "item_model": "minecraft:diamond_sword"
              "fire_resistant": true
              "hide_tooltip": true
              "custom_model_data": [12345.0]
              "food": { "nutrition": 8, "saturation": 0.8, "can_always_eat": true }
              "weapon": { "item_damage_per_attack": 5, "disable_blocking_for_seconds": 1.6 }
              "attack_range": { "min_reach": 0.0, "max_reach": 4.5, "hitbox_margin": 0.3 }
              "use_cooldown": { "seconds": 3.0, "cooldown_group": "minecraft:custom" }
              "equippable": { "slot": "HEAD", "swappable": true, "damage_on_hurt": false }
              "tool": {
                "default_mining_speed": 8.0,
                "damage_per_block": 1,
                "rules": [ { "blocks": "#minecraft:mineable/pickaxe",
                             "speed": 8.0, "correct_for_drops": true } ]
              }

            说明：tool.rules.blocks 目前只支持方块标签写法（以 # 开头），
            直接列举方块名会被忽略。写不准或不需要的组件可以省略，不要编造不存在的组件名。
            ===== 清单结束 =====
            """;

    /** resources/prompt.txt 缺失时的兜底系统提示词。 */
    private static final String FALLBACK_PROMPT = """
            你是一个 Minecraft 物品合成引擎，同时也是一个天马行空的物品设计师。
            用户会给你一组合成材料，你需要创造性地合成出一件全新的 Minecraft 物品。

            除了下面两条之外，一切都随你发挥：物品名称、Lore、附魔名称与等级、属性数值都可以自由编造，
            不需要遵循原版 Minecraft 的上限，也不必刻意维持平衡；自创的附魔名会作为「自定义附魔」
            显示在物品上并保存下来。

            硬性约束（游戏引擎限制）：
            1. material 必须是真实存在的原版物品 ID（全大写、下划线分隔，形如 DIAMOND_SWORD）。
            2. attributes[].attribute 必须是真实存在的原版属性 ID（形如 GENERIC_ATTACK_DAMAGE 或 ATTACK_DAMAGE）。

            components 支持下方清单里列出的数据组件（max_stack_size、food、weapon、tool、
            equippable、attribute_modifiers、fire_resistant、rarity …… 等等），
            能写多少写多少，值按原版数据组件的 JSON 结构填写。

            只输出严格 JSON，不要任何解释或 Markdown 代码块标记，结构如下：
            {
              "success": true,
              "material": "DIAMOND_SWORD",
              "amount": 1,
              "name": "&b&l钻石之刃",
              "lore": ["&7由AI合成", "&7附魔物品"],
              "enchantments": { "SHARPNESS": 5, "UNBREAKING": 3, "LIFESTEAL": 2 },
              "unbreakable": false,
              "custom_model_data": 0,
              "glow": true,
              "attributes": [
                { "attribute": "GENERIC_ATTACK_DAMAGE", "amount": 2.0, "operation": "ADD_NUMBER", "slot": "MAINHAND" }
              ],
              "components": {
                "max_stack_size": 1,
                "fire_resistant": true,
                "rarity": "epic",
                "tool": { "default_mining_speed": 8.0, "damage_per_block": 1 }
              }
            }

            材料无法合成时返回：{ "success": false, "reason": "原因" }
            """;

    private PluginConfig pluginConfig;
    private AiClient aiClient;
    private BuildService buildService;
    private String systemPrompt;

    /**
     * 一次在途的 AI 请求所保管的材料。
     *
     * @param dropLocation 玩家提交时的位置（离线 / 关服时用于掉落）
     * @param items        从合成区取出的材料
     */
    private record PendingCraft(Location dropLocation, List<ItemStack> items) {
    }

    /** 正在等待 AI 响应的玩家 -> 该次请求保管的材料。 */
    private final Map<UUID, PendingCraft> pending = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = PluginConfig.load(this);
        this.systemPrompt = loadPrompt();
        this.aiClient = new AiClient(this, pluginConfig, systemPrompt);
        this.buildService = new BuildService(this);

        getServer().getPluginManager().registerEvents(new CraftListener(this), this);

        PluginCommand command = getCommand("aicraft");
        if (command != null) {
            CraftCommand handler = new CraftCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        } else {
            getLogger().severe("plugin.yml 里找不到 aicraft 命令，命令将不可用！");
        }

        PluginCommand buildCommand = getCommand("aibuild");
        if (buildCommand != null) {
            BuildCommand handler = new BuildCommand(this);
            buildCommand.setExecutor(handler);
            buildCommand.setTabCompleter(handler);
        } else {
            getLogger().severe("plugin.yml 里找不到 aibuild 命令，命令将不可用！");
        }

        getLogger().info("AICraft 已启用 | 接口 " + pluginConfig.apiBaseUrl() + pluginConfig.apiPath()
                + " | 模型 " + pluginConfig.apiModel()
                + " | /aibuild " + (pluginConfig.buildEnabled() ? "开启" : "关闭"));
    }

    @Override
    public void onDisable() {
        // 1) 还在等 AI 响应的请求：材料由插件保管着，关服前必须还回去。
        //    先取出并清空 pending，这样即使稍后还有回调跑起来，也找不到条目、不会重复返还。
        for (Map.Entry<UUID, PendingCraft> entry : pending.entrySet()) {
            PendingCraft craft = pending.remove(entry.getKey());
            if (craft != null) {
                refund(entry.getKey(), craft.dropLocation(), craft.items());
            }
        }
        pending.clear();

        // 2) 还开着的界面里的材料
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Inventory top = player.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof CraftHolder holder && holder.owner().equals(player.getUniqueId())) {
                    returnCraftingItems(player, holder, false);
                }
            } catch (RuntimeException ex) {
                getLogger().warning("关闭界面时返还物品失败（" + player.getName() + "）：" + ex.getMessage());
            }
        }

        if (aiClient != null) {
            aiClient.shutdown();
        }
        if (buildService != null) {
            buildService.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    /**
     * 打开 AI 合成器界面（规格：打开界面无提示）。
     *
     * @param player 目标玩家
     */
    public void openGui(Player player) {
        CraftHolder holder = new CraftHolder(player.getUniqueId());
        Inventory inventory = CraftGui.create(holder, pluginConfig.guiTitleComponent());
        player.openInventory(inventory);
    }

    /**
     * 重载配置（会重建 AI 客户端与提示词）。
     */
    public void reloadPlugin() {
        reloadConfig();
        this.pluginConfig = PluginConfig.load(this);
        this.systemPrompt = loadPrompt();
        this.aiClient.shutdown();
        this.aiClient = new AiClient(this, pluginConfig, systemPrompt);
        this.buildService.reload();
        getLogger().info("配置已重载 | 模型 " + pluginConfig.apiModel());
    }

    // ------------------------------------------------------------------
    // 交互：取消
    // ------------------------------------------------------------------

    /**
     * 点击「取消」：返还合成区物品并关闭界面。
     *
     * @param player 玩家
     * @param holder 界面 holder
     * @param close  是否关闭界面
     */
    public void cancelCraft(Player player, CraftHolder holder, boolean close) {
        returnCraftingItems(player, holder, true);
        if (close) {
            player.closeInventory();
        }
    }

    /**
     * 把合成区里剩余的物品返还给玩家（背包满则掉落）。
     *
     * @param player 玩家
     * @param holder 界面 holder
     * @param notify 是否发送「已返还」提示
     * @return 返还的物品格数
     */
    public int returnCraftingItems(Player player, CraftHolder holder, boolean notify) {
        Inventory top;
        try {
            top = holder.getInventory();
        } catch (IllegalStateException ex) {
            return 0;
        }
        List<ItemStack> items = new ArrayList<>();
        for (int slot : CraftGui.CRAFT_SLOT_ORDER) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            items.add(item.clone());
            top.setItem(slot, null);
        }
        if (items.isEmpty()) {
            return 0;
        }
        ItemStacks.Delivery delivery = ItemStacks.giveOrDrop(player, items);
        if (notify && player.isOnline()) {
            player.sendMessage(pluginConfig.message("returned"));
            if (delivery.droppedAnything()) {
                player.sendMessage(pluginConfig.message("inventory-full"));
            }
        }
        return items.size();
    }

    // ------------------------------------------------------------------
    // 交互：合成
    // ------------------------------------------------------------------

    /**
     * 点击「合成!」的完整流程。
     *
     * <p>顺序：空检查 → 堆叠数量检查 → 提交给 AI。</p>
     *
     * @param player 玩家
     * @param holder 界面 holder
     */
    public void submitCraft(Player player, CraftHolder holder) {
        UUID uuid = player.getUniqueId();

        if (pending.containsKey(uuid) || holder.busy()) {
            player.sendMessage(pluginConfig.message("busy"));
            return;
        }

        Inventory top = holder.getInventory();
        List<ItemStack> materials = new ArrayList<>();
        boolean stacked = false;
        for (int slot : CraftGui.CRAFT_SLOT_ORDER) {
            ItemStack item = top.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            materials.add(item);
            if (item.getAmount() > 1) {
                stacked = true;
            }
        }

        // 1) 空检查：不做任何事，仅提示，不关闭界面
        if (materials.isEmpty()) {
            player.sendMessage(pluginConfig.message("empty"));
            return;
        }

        // 2) 堆叠数量检查：返还全部材料、清空合成区、提示并取消
        if (stacked) {
            returnCraftingItems(player, holder, false);
            player.sendMessage(pluginConfig.message("amount"));
            return;
        }

        // 3) 提交给 AI
        //    先深拷贝材料并清空合成区：材料改由插件保管，
        //    这样即使玩家在等待期间关闭界面 / 退出游戏，也不会出现复制或丢失。
        List<ItemStack> snapshot = new ArrayList<>(materials.size());
        for (ItemStack item : materials) {
            snapshot.add(item.clone());
        }
        for (int slot : CraftGui.CRAFT_SLOT_ORDER) {
            top.setItem(slot, null);
        }

        Location dropLocation = player.getLocation().clone();
        PendingCraft record = new PendingCraft(dropLocation, snapshot);
        pending.put(uuid, record);
        holder.busy(true);
        player.sendMessage(pluginConfig.message("crafting"));

        // 序列化材料 -> {"materials":[...]}，作为 user message 发给 AI
        JsonObject payload = ItemSerializer.toPayload(snapshot);

        aiClient.requestCraft(uuid, payload).whenComplete((result, error) -> {
            // HTTP 回调线程：绝对不能在这里碰 Bukkit API，先切回主线程
            Runnable task = () -> handleResult(uuid, dropLocation, holder, snapshot, result, error);
            try {
                Bukkit.getScheduler().runTask(this, task);
            } catch (RuntimeException ex) {
                // 插件已被禁用 / 服务端正在关闭：还材料，避免物品丢失。
                // 只有在 pending 里还找得到这一条时才返还，防止和 onDisable 的兜底重复发放。
                if (pending.remove(uuid) != null) {
                    holder.busy(false);
                    refund(uuid, dropLocation, snapshot);
                    getLogger().warning("无法把 AI 回调切回主线程，已直接返还材料：" + ex.getMessage());
                }
            }
        });
    }

    /**
     * AI 回调（已切回主线程）后的收尾。
     *
     * @param uuid         玩家 UUID
     * @param dropLocation 玩家提交时的位置（离线时用于掉落）
     * @param holder       界面 holder
     * @param snapshot     提交时保管的材料
     * @param result       AI 结果，失败时为 null
     * @param error        异常，成功时为 null
     */
    private void handleResult(UUID uuid, Location dropLocation, CraftHolder holder,
                              List<ItemStack> snapshot, AiCraftResult result, Throwable error) {
        pending.remove(uuid);
        holder.busy(false);

        Player player = Bukkit.getPlayer(uuid);

        // ---- 请求失败（已重试到上限） ----
        if (error != null) {
            Throwable cause = AiClient.unwrap(error);
            getLogger().warning("AI 合成失败（" + uuid + "）：" + AiClient.describe(cause));
            if (pluginConfig.debug()) {
                cause.printStackTrace();
            }
            refund(uuid, dropLocation, snapshot);
            send(player, pluginConfig.message("no-response"));
            return;
        }

        // ---- AI 明确表示无法合成 ----
        if (result == null || !result.success()) {
            String reason = result == null ? "AI 没有返回结果" : result.reason();
            refund(uuid, dropLocation, snapshot);
            send(player, pluginConfig.message("failure", "reason", reason));
            return;
        }

        // ---- 构建物品 ----
        ItemStack crafted;
        try {
            crafted = ItemFactory.build(result, this);
        } catch (RuntimeException ex) {
            getLogger().warning("根据 AI 结果构建物品失败（" + uuid + "）：" + ex.getMessage());
            refund(uuid, dropLocation, snapshot);
            send(player, pluginConfig.message("failure", "reason", "AI 返回的物品无法构建"));
            return;
        }

        // ---- 成功：发物品、提示 ----
        String displayName = displayNameOf(crafted);

        if (player != null && player.isOnline()) {
            ItemStacks.Delivery delivery = ItemStacks.giveOrDrop(player, List.of(crafted));
            player.sendMessage(pluginConfig.message("success", "item_name", displayName));
            if (delivery.droppedAnything()) {
                player.sendMessage(pluginConfig.message("inventory-full"));
            }
        } else {
            ItemStacks.dropAt(dropLocation, List.of(crafted));
            getLogger().info("玩家 " + uuid + " 已离线，合成结果 " + displayName + " 已掉落在其原位置");
        }
        if (pluginConfig.debug()) {
            getLogger().info("[debug] 合成结果: " + result.describe() + " -> " + crafted.getType()
                    + " x" + crafted.getAmount());
        }
    }

    /**
     * 把材料还给玩家；玩家已离线则掉落在提交时的位置。
     *
     * @param uuid         玩家 UUID
     * @param dropLocation 掉落位置
     * @param items        物品
     */
    private void refund(UUID uuid, Location dropLocation, List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            ItemStacks.Delivery delivery = ItemStacks.giveOrDrop(player, items);
            if (delivery.droppedAnything()) {
                player.sendMessage(pluginConfig.message("inventory-full"));
            }
            return;
        }
        ItemStacks.dropAt(dropLocation, items);
    }

    private void send(Player player, Component message) {
        if (player != null && player.isOnline()) {
            player.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 玩家退出时清理状态。 */
    public void forget(UUID uuid) {
        if (buildService != null) {
            buildService.forget(uuid);
        }
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 读取系统提示词，优先级从高到低：
     * <ol>
     *   <li>{@code config.yml} 的 {@code api.system-prompt}（非空时整体覆盖，方便不重打包就调整）；</li>
     *   <li>{@code resources/prompt.txt}（默认，完整的创作自由度说明 + 原版附魔 / 属性清单）；</li>
     *   <li>内置的精简兜底常量。</li>
     * </ol>
     *
     * <p>无论来自哪个来源，都会在末尾拼接 {@link #COMPONENT_REFERENCE}
     * （如果尚未包含），保证 AI 知道全部可用的 {@code components} 键。</p>
     *
     * @return 提示词
     */
    private String loadPrompt() {
        String base;
        String fromConfig = pluginConfig.apiSystemPrompt();
        if (!fromConfig.isBlank()) {
            base = fromConfig;
        } else {
            try (InputStream in = getResource("prompt.txt")) {
                if (in == null) {
                    base = FALLBACK_PROMPT;
                } else {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    base = text.isBlank() ? FALLBACK_PROMPT : text;
                }
            } catch (IOException ex) {
                getLogger().warning("读取 prompt.txt 失败，改用内置提示词：" + ex.getMessage());
                base = FALLBACK_PROMPT;
            }
        }

        // 注入完整组件清单（带 marker 去重，避免 prompt.txt / config.yml 里已经写过时重复叠加）
        if (base.contains(COMPONENT_REFERENCE_MARKER)) {
            return base;
        }
        return base + COMPONENT_REFERENCE;
    }

    /**
     * 取物品的展示名（带 {@code &} 颜色代码），没有自定义名称时用材质名兜底。
     *
     * @param stack 物品
     * @return 展示名
     */
    private String displayNameOf(ItemStack stack) {
        try {
            Component custom = stack.getData(DataComponentTypes.CUSTOM_NAME);
            if (custom != null) {
                return Text.toAmpersand(custom);
            }
        } catch (RuntimeException ignored) {
            // 序列化失败就退回材质名
        }
        return prettify(stack.getType().name());
    }

    private static String prettify(String materialName) {
        String[] words = materialName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
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

    /** @return 类型化配置 */
    public PluginConfig pluginConfig() {
        return pluginConfig;
    }

    /** @return AI 接口客户端（/aibuild 复用同一条请求通道） */
    public AiClient aiClient() {
        return aiClient;
    }

    /** @return AI 建造服务 */
    public BuildService buildService() {
        return buildService;
    }
}