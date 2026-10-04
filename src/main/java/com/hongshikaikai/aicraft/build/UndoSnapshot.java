package com.hongshikaikai.aicraft.build;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.List;

/**
 * 一次建造的撤销快照。
 *
 * <p>记录被改动方块「原来的样子」，{@code /aibuild undo} 按记录的反序还原。
 * 快照只保存在内存里，服务器重启后失效。</p>
 *
 * @param world    所在世界
 * @param name     建筑名（提示用）
 * @param blocks   被改动方块的原状态
 * @param complete 是否完整记录（超出 {@code build.undo.max-blocks} 时为 false，撤销可能不完整）
 * @param time     建造完成时间戳（毫秒）
 */
public record UndoSnapshot(World world, String name, List<BlockSnapshot> blocks, boolean complete, long time) {

    /**
     * 单个方块的原始状态。
     *
     * @param x    坐标
     * @param y    坐标
     * @param z    坐标
     * @param data 原方块数据
     */
    public record BlockSnapshot(int x, int y, int z, BlockData data) {
    }

    /** @return 快照里的方块数 */
    public int size() {
        return blocks.size();
    }
}
