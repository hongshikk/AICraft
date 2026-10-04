package com.hongshikaikai.aicraft.build;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.Set;

/**
 * 世界方块读写的共享工具。
 *
 * <p>所有方法都<b>必须在主线程调用</b>，并且只操作已被服务端加载的世界。</p>
 */
final class BlockOps {

    private BlockOps() {
    }

    /**
     * 判断某个 Y 是否在世界建筑高度内。
     *
     * @param world 世界
     * @param y     坐标
     * @return 在高度范围内返回 true
     */
    static boolean inRange(World world, int y) {
        return y >= world.getMinHeight() && y < world.getMaxHeight();
    }

    /**
     * 判断该方块是否受保护（在配置的黑名单里，不允许被 AI 覆盖 / 破坏）。
     *
     * @param current   当前方块
     * @param blacklist 黑名单
     * @return 受保护返回 true
     */
    static boolean isProtected(BlockData current, Set<Material> blacklist) {
        return blacklist != null && !blacklist.isEmpty() && blacklist.contains(current.getMaterial());
    }

    /**
     * 放置一个方块。
     *
     * @param world   世界
     * @param x       坐标
     * @param y       坐标
     * @param z       坐标
     * @param data    方块数据
     * @param physics 是否触发方块物理（默认 false，避免连锁更新造成卡顿）
     */
    static void set(World world, int x, int y, int z, BlockData data, boolean physics) {
        world.getBlockAt(x, y, z).setBlockData(data, physics);
    }

    /**
     * 打包方块坐标为 long（与原版区块键一致的位布局），用于去重。
     *
     * @param x 坐标
     * @param y 坐标
     * @param z 坐标
     * @return 打包值
     */
    static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}
