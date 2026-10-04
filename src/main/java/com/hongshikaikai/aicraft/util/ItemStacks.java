package com.hongshikaikai.aicraft.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Collection;
import java.util.Map;

/**
 * 物品发放工具：优先塞进玩家背包，塞不下的掉落在地上。
 *
 * <p>所有方法都必须在主线程调用。</p>
 */
public final class ItemStacks {

    /**
     * 发放结果。
     *
     * @param given   成功放进背包的数量
     * @param dropped 因为背包满而掉落到地上的数量
     */
    public record Delivery(int given, int dropped) {
        public boolean droppedAnything() {
            return dropped > 0;
        }
    }

    private ItemStacks() {
    }

    /**
     * 把物品发给在线玩家；背包放不下的部分掉落在玩家脚下。
     *
     * @param player 目标玩家
     * @param items  物品列表
     * @return 发放结果
     */
    public static Delivery giveOrDrop(Player player, Collection<ItemStack> items) {
        if (items == null || items.isEmpty() || player == null) {
            return new Delivery(0, 0);
        }
        // 玩家已经离线：直接掉在它当前位置，绝不能静默丢弃
        if (!player.isOnline()) {
            return new Delivery(0, dropAt(player.getLocation(), items));
        }
        PlayerInventory inventory = player.getInventory();
        int requested = 0;
        for (ItemStack stack : items) {
            if (stack != null && !stack.getType().isAir()) {
                requested += stack.getAmount();
            }
        }
        Map<Integer, ItemStack> leftover = inventory.addItem(items.toArray(new ItemStack[0]));
        int dropped = 0;
        if (!leftover.isEmpty()) {
            World world = player.getWorld();
            Location location = player.getLocation();
            for (ItemStack stack : leftover.values()) {
                dropped += stack.getAmount();
                world.dropItemNaturally(location, stack);
            }
        }
        return new Delivery(requested - dropped, dropped);
    }

    /**
     * 在指定位置掉落物品（玩家离线时使用）。
     *
     * @param location 掉落位置
     * @param items    物品
     * @return 掉落的物品数量
     */
    public static int dropAt(Location location, Collection<ItemStack> items) {
        if (location == null || items == null || items.isEmpty()) {
            return 0;
        }
        World world = location.getWorld();
        if (world == null) {
            return 0;
        }
        int dropped = 0;
        for (ItemStack stack : items) {
            if (stack != null && !stack.getType().isAir()) {
                world.dropItemNaturally(location, stack);
                dropped += stack.getAmount();
            }
        }
        return dropped;
    }
}
