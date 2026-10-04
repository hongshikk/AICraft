package com.hongshikaikai.aicraft.build;

import com.hongshikaikai.aicraft.AICraft;
import com.hongshikaikai.aicraft.config.PluginConfig;
import com.hongshikaikai.aicraft.util.Text;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 一次 AI 建造的实际施工过程。
 *
 * <p>由 {@link BuildService} 在主线程调度：每次 tick 最多放置
 * {@code build.blocks-per-tick} 个方块，避免一次性改动几万方块把服务器卡死。
 * 每改一个方块都会把「原来的样子」记进撤销快照。</p>
 */
final class BuildSession extends BukkitRunnable {

    /** 长方体遍历模式。 */
    private enum BoxMode {
        /** 实心。 */
        SOLID,
        /** 六面外壳。 */
        SHELL,
        /** 四面墙（不含顶/底）。 */
        WALLS
    }

    /** 待放置的一个方块。 */
    private record BlockChange(int x, int y, int z, BlockData data) {
    }

    /** 单元格判定。 */
    private interface CellTest {
        boolean test(int x, int y, int z);
    }

    private final AICraft plugin;
    private final PluginConfig config;
    private final World world;
    private final BuildPlan plan;
    private final Consumer<BuildSession> onFinish;
    private final Set<Material> blacklist;
    private final List<UndoSnapshot.BlockSnapshot> undo = new ArrayList<>();
    private final Set<Long> recorded = new HashSet<>();

    private final int undoLimit;
    private final boolean physics;

    private int opIndex;
    private Iterator<BlockChange> current;
    private int placed;
    private int skipped;
    private int outOfRange;
    private boolean undoComplete = true;
    private boolean finished;

    BuildSession(AICraft plugin, World world, BuildPlan plan, Consumer<BuildSession> onFinish) {
        this.plugin = plugin;
        this.config = plugin.pluginConfig();
        this.world = world;
        this.plan = plan;
        this.onFinish = onFinish;
        this.blacklist = config.buildBlacklist();
        this.undoLimit = config.buildUndoMaxBlocks();
        this.physics = config.buildApplyPhysics();
    }

    /** 开始施工（下一 tick 起）。 */
    void start() {
        runTaskTimer(plugin, 1L, 1L);
    }

    @Override
    public void run() {
        int budget = Math.max(1, config.buildBlocksPerTick());
        try {
            while (budget > 0) {
                if (current == null) {
                    if (opIndex >= plan.ops().size()) {
                        finish();
                        return;
                    }
                    BuildOp op = plan.ops().get(opIndex++);
                    if (op instanceof BuildOp.Sign sign) {
                        placeSign(sign);
                        budget--;
                        continue;
                    }
                    current = iterator(op);
                    continue;
                }
                if (!current.hasNext()) {
                    current = null;
                    continue;
                }
                apply(current.next());
                budget--;
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("AI 建造执行出错（" + plan.name() + "）：" + ex);
            finish();
        }
    }

    // ------------------------------------------------------------------
    // 放置
    // ------------------------------------------------------------------

    private void apply(BlockChange change) {
        int x = change.x();
        int y = change.y();
        int z = change.z();
        if (!BlockOps.inRange(world, y)) {
            outOfRange++;
            return;
        }
        BlockData old = world.getBlockData(x, y, z);
        if (BlockOps.isProtected(old, blacklist)) {
            skipped++;
            return;
        }
        if (old.equals(change.data())) {
            // 已经是目标方块，不算改动、也不需要撤销记录
            skipped++;
            return;
        }
        remember(x, y, z, old);
        BlockOps.set(world, x, y, z, change.data(), physics);
        placed++;
    }

    private void placeSign(BuildOp.Sign sign) {
        BuildOp.Pos p = sign.p();
        if (!BlockOps.inRange(world, p.y())) {
            outOfRange++;
            return;
        }
        BlockData old = world.getBlockData(p.x(), p.y(), p.z());
        if (BlockOps.isProtected(old, blacklist)) {
            skipped++;
            return;
        }
        if (!old.equals(sign.data())) {
            remember(p.x(), p.y(), p.z(), old);
        }
        Block block = world.getBlockAt(p.x(), p.y(), p.z());
        block.setBlockData(sign.data(), physics);
        BlockState state = block.getState();
        if (state instanceof Sign signState) {
            List<String> lines = sign.lines();
            for (int i = 0; i < 4 && i < lines.size(); i++) {
                signState.line(i, Text.component(lines.get(i)));
            }
            signState.update(true, false);
        }
        placed++;
    }

    private void remember(int x, int y, int z, BlockData old) {
        if (!undoComplete) {
            return;
        }
        if (!recorded.add(BlockOps.pack(x, y, z))) {
            // 同一格被多条指令重复改动：只记第一次（也就是真正的原状）即可
            return;
        }
        if (undo.size() >= undoLimit) {
            undoComplete = false;
            return;
        }
        undo.add(new UndoSnapshot.BlockSnapshot(x, y, z, old));
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        cancel();
        onFinish.accept(this);
    }

    // ------------------------------------------------------------------
    // 指令 -> 方块迭代器
    // ------------------------------------------------------------------

    private Iterator<BlockChange> iterator(BuildOp op) {
        if (op instanceof BuildOp.Fill fill) {
            return box(fill.a(), fill.b(), fill.data(), BoxMode.SOLID);
        }
        if (op instanceof BuildOp.SetBlock set) {
            return Collections.singletonList(
                    new BlockChange(set.p().x(), set.p().y(), set.p().z(), set.data())).iterator();
        }
        if (op instanceof BuildOp.Hollow hollow) {
            return box(hollow.a(), hollow.b(), hollow.data(), BoxMode.SHELL);
        }
        if (op instanceof BuildOp.Walls walls) {
            return box(walls.a(), walls.b(), walls.data(), BoxMode.WALLS);
        }
        if (op instanceof BuildOp.Line line) {
            return line(line);
        }
        if (op instanceof BuildOp.Sphere sphere) {
            return sphere(sphere);
        }
        if (op instanceof BuildOp.Cylinder cylinder) {
            return cylinder(cylinder);
        }
        if (op instanceof BuildOp.Replace replace) {
            return replace(replace);
        }
        return Collections.emptyIterator();
    }

    private Iterator<BlockChange> box(BuildOp.Pos a, BuildOp.Pos b, BlockData data, BoxMode mode) {
        int minX = Math.min(a.x(), b.x());
        int maxX = Math.max(a.x(), b.x());
        int minY = Math.min(a.y(), b.y());
        int maxY = Math.max(a.y(), b.y());
        int minZ = Math.min(a.z(), b.z());
        int maxZ = Math.max(a.z(), b.z());
        return iterate(minX, minY, minZ, maxX, maxY, maxZ, data, (x, y, z) -> switch (mode) {
            case SOLID -> true;
            case SHELL -> x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
            case WALLS -> x == minX || x == maxX || z == minZ || z == maxZ;
        });
    }

    private Iterator<BlockChange> sphere(BuildOp.Sphere spec) {
        int r = spec.radius();
        BuildOp.Pos c = spec.center();
        double outer = (double) r * r;
        double inner = (double) Math.max(0, r - 1) * Math.max(0, r - 1);
        boolean hollow = spec.hollow();
        return iterate(c.x() - r, c.y() - r, c.z() - r, c.x() + r, c.y() + r, c.z() + r, spec.data(),
                (x, y, z) -> {
                    double dx = x - c.x();
                    double dy = y - c.y();
                    double dz = z - c.z();
                    double distance = dx * dx + dy * dy + dz * dz;
                    return distance <= outer && (!hollow || distance >= inner);
                });
    }

    private Iterator<BlockChange> cylinder(BuildOp.Cylinder spec) {
        int r = spec.radius();
        int height = Math.max(1, spec.height());
        BuildOp.Pos base = spec.base();
        double outer = (double) r * r;
        double inner = (double) Math.max(0, r - 1) * Math.max(0, r - 1);
        boolean hollow = spec.hollow();
        return iterate(base.x() - r, base.y(), base.z() - r,
                base.x() + r, base.y() + height - 1, base.z() + r, spec.data(),
                (x, y, z) -> {
                    double dx = x - base.x();
                    double dz = z - base.z();
                    double distance = dx * dx + dz * dz;
                    return distance <= outer && (!hollow || distance >= inner);
                });
    }

    private Iterator<BlockChange> line(BuildOp.Line spec) {
        BuildOp.Pos a = spec.a();
        BuildOp.Pos b = spec.b();
        int dx = b.x() - a.x();
        int dy = b.y() - a.y();
        int dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        List<BlockChange> cells = new ArrayList<>(steps + 1);
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0D : (double) i / steps;
            int x = (int) Math.round(a.x() + dx * t);
            int y = (int) Math.round(a.y() + dy * t);
            int z = (int) Math.round(a.z() + dz * t);
            if (x == lastX && y == lastY && z == lastZ) {
                continue;
            }
            cells.add(new BlockChange(x, y, z, spec.data()));
            lastX = x;
            lastY = y;
            lastZ = z;
        }
        return cells.iterator();
    }

    private Iterator<BlockChange> replace(BuildOp.Replace spec) {
        BuildOp.Pos a = spec.a();
        BuildOp.Pos b = spec.b();
        int minX = Math.min(a.x(), b.x());
        int maxX = Math.max(a.x(), b.x());
        int minY = Math.min(a.y(), b.y());
        int maxY = Math.max(a.y(), b.y());
        int minZ = Math.min(a.z(), b.z());
        int maxZ = Math.max(a.z(), b.z());
        BuildOp.BlockFilter filter = spec.filter();
        return iterate(minX, minY, minZ, maxX, maxY, maxZ, spec.data(), (x, y, z) -> {
            if (!BlockOps.inRange(world, y)) {
                return false;
            }
            BlockData existing = world.getBlockData(x, y, z);
            return !BlockOps.isProtected(existing, blacklist) && filter.matches(existing);
        });
    }

    /** 遍历包围盒并筛出命中的格子，惰性产出（每次只算下一个，主线程开销可控）。 */
    private static Iterator<BlockChange> iterate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                 BlockData data, CellTest test) {
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        long total = (long) sizeX * sizeY * (maxZ - minZ + 1);
        return new Iterator<>() {

            private long index;
            private BlockChange next = find();

            private BlockChange find() {
                while (index < total) {
                    long i = index++;
                    int x = minX + (int) (i % sizeX);
                    int y = minY + (int) ((i / sizeX) % sizeY);
                    int z = minZ + (int) (i / ((long) sizeX * sizeY));
                    if (test.test(x, y, z)) {
                        return new BlockChange(x, y, z, data);
                    }
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public BlockChange next() {
                BlockChange out = next;
                next = find();
                return out;
            }
        };
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    World world() {
        return world;
    }

    String name() {
        return plan.name();
    }

    boolean truncated() {
        return plan.truncated();
    }

    int skipped() {
        return skipped;
    }

    int outOfRange() {
        return outOfRange;
    }

    int placed() {
        return placed;
    }

    List<UndoSnapshot.BlockSnapshot> undo() {
        return undo;
    }

    boolean undoComplete() {
        return undoComplete;
    }
}
