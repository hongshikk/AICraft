package com.hongshikaikai.aicraft.build;

import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.config.PluginConfig;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.function.Consumer;

/**
 * 撤销施工：把 {@link UndoSnapshot} 里记录的方块原状态写回去。
 *
 * <p>与 {@link BuildSession} 一样按 tick 分片执行，避免一次性还原几万方块造成卡顿。</p>
 */
final class RestoreSession extends BukkitRunnable {

    private final AICraft plugin;
    private final PluginConfig config;
    private final World world;
    private final List<UndoSnapshot.BlockSnapshot> blocks;
    private final Consumer<RestoreSession> onFinish;

    private int index;
    private int restored;
    private int skipped;

    RestoreSession(AICraft plugin, World world, List<UndoSnapshot.BlockSnapshot> blocks,
                   Consumer<RestoreSession> onFinish) {
        this.plugin = plugin;
        this.config = plugin.pluginConfig();
        this.world = world;
        this.blocks = blocks;
        this.onFinish = onFinish;
    }

    /** 开始还原（下一 tick 起）。 */
    BukkitTask start() {
        return runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public void run() {
        int budget = Math.max(1, config.buildBlocksPerTick());
        while (budget > 0 && index < blocks.size()) {
            UndoSnapshot.BlockSnapshot snapshot = blocks.get(index++);
            if (!BlockOps.inRange(world, snapshot.y())) {
                skipped++;
                budget--;
                continue;
            }
            BlockOps.set(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.data(), config.buildApplyPhysics());
            restored++;
            budget--;
        }
        if (index >= blocks.size()) {
            cancel();
            onFinish.accept(this);
        }
    }

    /** @return 已还原的方块数 */
    int restored() {
        return restored;
    }

    /** @return 因为高度越界被跳过的方块数 */
    int skipped() {
        return skipped;
    }
}
