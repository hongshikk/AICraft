package com.hongshikaikai.aicraft.gui;

import com.hongshikaikai.aicraft.util.Text;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * 界面布局与静态装饰物。
 *
 * <pre>
 * 第1行： 0  1  2 |  3  4  5 |  6  7  8
 * 第2行： 9 10 11 | 12 13 14 | 15 16 17
 * 第3行：18 19 20 | 21 22 23 | 24 25 26
 * </pre>
 *
 * <ul>
 *   <li>左侧 3×3：外圈 8 格淡灰色玻璃板（锁定），中心 10 号格为红色玻璃板「取消」</li>
 *   <li>中间 3×3：{@link #CRAFT_SLOTS} —— 玩家可自由放入 / 取出材料</li>
 *   <li>右侧 3×3：外圈 8 格淡灰色玻璃板（锁定），中心 16 号格为绿色玻璃板「合成!」</li>
 * </ul>
 */
public final class CraftGui {

    /** 3 行 × 9 列 = 27 格。 */
    public static final int SIZE = 27;

    /** 「取消」按钮所在槽位。 */
    public static final int CANCEL_SLOT = 10;

    /** 「合成!」按钮所在槽位。 */
    public static final int CONFIRM_SLOT = 16;

    /** 中间 3×3 合成区（按从上到下、从左到右的顺序）。 */
    public static final Set<Integer> CRAFT_SLOTS = Set.of(3, 4, 5, 12, 13, 14, 21, 22, 23);

    /** 合成区的有序数组，遍历 / 序列化时使用。 */
    public static final int[] CRAFT_SLOT_ORDER = {3, 4, 5, 12, 13, 14, 21, 22, 23};

    private CraftGui() {
    }

    /**
     * 判断某个「原始槽位」（raw slot）是否属于中间合成区。
     *
     * @param rawSlot {@code InventoryClickEvent#getRawSlot()}
     * @return 是则返回 true
     */
    public static boolean isCraftSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < SIZE && CRAFT_SLOTS.contains(rawSlot);
    }

    /**
     * 判断某个原始槽位是否是被锁定的装饰格 / 按钮格。
     *
     * @param rawSlot 原始槽位
     * @return 是则返回 true
     */
    public static boolean isLocked(int rawSlot) {
        return rawSlot >= 0 && rawSlot < SIZE && !CRAFT_SLOTS.contains(rawSlot);
    }

    /**
     * 创建并填充 27 格界面。
     *
     * @param holder 界面 holder
     * @param title  标题组件
     * @return 已填充好的界面
     */
    public static Inventory create(CraftHolder holder, Component title) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE, title);

        ItemStack filler = filler();
        for (int slot = 0; slot < SIZE; slot++) {
            if (CRAFT_SLOTS.contains(slot) || slot == CANCEL_SLOT || slot == CONFIRM_SLOT) {
                continue;
            }
            inventory.setItem(slot, filler.clone());
        }

        inventory.setItem(CANCEL_SLOT, button(Material.RED_STAINED_GLASS_PANE, "&c取消"));
        inventory.setItem(CONFIRM_SLOT, button(Material.LIME_STAINED_GLASS_PANE, "&a合成!"));

        holder.attach(inventory);
        return inventory;
    }

    /**
     * 装饰用淡灰色玻璃板。
     *
     * <p>使用新版数据组件：{@code CUSTOM_NAME} 设置名称，{@code TOOLTIP_DISPLAY}
     * 隐藏属性 / 附魔提示（等价于旧版的 {@code ItemFlag.HIDE_ATTRIBUTES}）。</p>
     *
     * @return 装饰物品
     */
    public static ItemStack filler() {
        ItemStack pane = newPane(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        pane.setData(DataComponentTypes.CUSTOM_NAME, Text.component("&7—"));
        return pane;
    }

    /**
     * 构造一个按钮物品。
     *
     * @param material 材质
     * @param name     带 {@code &} 颜色代码的名称
     * @return 按钮物品
     */
    public static ItemStack button(Material material, String name) {
        ItemStack item = newPane(material);
        item.setData(DataComponentTypes.CUSTOM_NAME, Text.component(name));
        return item;
    }

    /**
     * 玻璃板基础物品，并隐藏属性提示。
     *
     * @param material 材质
     * @return 物品
     */
    private static ItemStack newPane(Material material) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS)
                .build());
        return item;
    }
}
