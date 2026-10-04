package com.hongshikaikai.aicraft.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * AI 合成器界面的 {@link InventoryHolder}。
 *
 * <p>它同时承担两个职责：</p>
 * <ol>
 *   <li><b>身份标记</b>：所有监听器都先用
 *       {@code event.getInventory().getHolder() instanceof CraftHolder}
 *       判断，避免影响其它插件 / 原版的容器界面（规格六.2）。</li>
 *   <li><b>会话状态</b>：记录界面归属的玩家 UUID，以及是否正有一个 AI 请求在途。</li>
 * </ol>
 */
public final class CraftHolder implements InventoryHolder {

    private final UUID owner;
    private Inventory inventory;
    private volatile boolean busy;

    /**
     * @param owner 打开该界面的玩家 UUID
     */
    public CraftHolder(UUID owner) {
        this.owner = owner;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("CraftHolder 尚未绑定 Inventory");
        }
        return inventory;
    }

    /**
     * 由 {@link CraftGui} 在创建界面后回填。
     *
     * @param inventory 该 holder 对应的顶部容器
     */
    void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    /** @return 界面归属玩家 */
    public UUID owner() {
        return owner;
    }

    /** @return 是否正有一个 AI 请求在途 */
    public boolean busy() {
        return busy;
    }

    /**
     * 设置「AI 请求在途」状态。
     *
     * @param busy 是否在途
     */
    public void busy(boolean busy) {
        this.busy = busy;
    }
}
