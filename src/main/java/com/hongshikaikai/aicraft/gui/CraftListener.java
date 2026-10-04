package com.hongshikaikai.aicraft.gui;

import com.hongshikaikai.aicraft.AICraft;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * 界面交互监听：锁定格保护、按钮点击、关闭返还。
 *
 * <p>规格六.2 要求：所有处理都必须先判断
 * {@code event.getInventory().getHolder() instanceof CraftHolder}，
 * 只有本插件的界面才会被接管，不会影响其它容器。</p>
 */
public final class CraftListener implements Listener {

    private final AICraft plugin;

    public CraftListener(AICraft plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CraftHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        // 防御：只处理界面归属者自己的操作
        if (!holder.owner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();

        // 双键收集 / 双击：不管点在哪一格都取消。
        // 否则玩家可以拿一个玻璃板双击，把锁定格里的淡灰色玻璃板一起吸到光标上。
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
        }

        // ---------- 顶部容器（0..26） ----------
        if (rawSlot >= 0 && rawSlot < CraftGui.SIZE) {
            if (CraftGui.isCraftSlot(rawSlot)) {
                // 中间合成区：允许正常的放入 / 取出（含数字键交换、副手交换）
                event.setCancelled(false);
                return;
            }

            // 锁定格 or 按钮：一律先取消默认行为
            // 这样淡灰色玻璃板就不可拿起、不可移动、不可丢弃、不可数字键交换、不可双键收集，
            // 按钮本身也不会被玩家拿走。
            event.setCancelled(true);

            if (rawSlot == CraftGui.CANCEL_SLOT) {
                plugin.cancelCraft(player, holder, true);
            } else if (rawSlot == CraftGui.CONFIRM_SLOT) {
                plugin.submitCraft(player, holder);
            }
            return;
        }

        // ---------- 玩家背包（rawSlot >= 27） ----------
        // 允许正常的拿起 / 放下，但阻止 Shift 点击自动转移：
        // 它会把物品一股脑塞进顶栏，可能落进锁定格。
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // 拖拽
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CraftHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.owner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < CraftGui.SIZE && !CraftGui.isCraftSlot(rawSlot)) {
                // 只要拖拽路径上碰到任何一个锁定格，整次拖拽取消
                event.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    /**
     * 玩家关闭界面（含退出、传送、死亡等导致的关闭）时，把合成区里剩余的物品返还。
     *
     * <p>提交给 AI 时合成区已经被清空、材料由插件保管，所以这里不会重复返还。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CraftHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        plugin.returnCraftingItems(player, holder, true);
    }

    /**
     * 玩家退出时：返还界面里的材料并清理状态。
     *
     * <p>退出时服务端不保证一定派发 {@link InventoryCloseEvent}（取决于具体关闭原因），
     * 所以这里再兜一次底。{@code returnCraftingItems} 会先把槽位清空，
     * 即使两个事件都触发也不会重复返还。</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        try {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof CraftHolder holder && holder.owner().equals(player.getUniqueId())) {
                plugin.returnCraftingItems(player, holder, false);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("玩家退出时返还物品失败（" + player.getName() + "）：" + ex.getMessage());
        }
        plugin.forget(player.getUniqueId());
    }
}
