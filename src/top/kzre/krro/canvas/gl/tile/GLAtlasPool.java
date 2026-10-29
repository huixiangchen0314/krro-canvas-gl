package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLThreadAffine;
import top.kzre.krro.canvas.gl.util.Range;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TileFactory;
import top.kzre.krro.util.tile.TiledCanvas;
import top.kzre.krro.util.tile.util.WeakReferenceQueue;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Atlas 池：管理 {@link GLAtlas} 的生命周期。
 *
 * <p><b>定位</b>：池是 atlas 的容器，不关心 unit——unit 由外部
 * 分配和绑定。池只负责：
 * <ul>
 *   <li>按需创建 atlas，直到达到容量上限</li>
 *   <li>返回非满的 atlas 供分配瓦片</li>
 *   <li>取出空闲 atlas（所有瓦片已释放）并归还所有权</li>
 *   <li>压缩：把瓦片搬到尽可能少的 atlas 里</li>
 * </ul>
 *
 * <p><b>容量语义</b>：{@code capacity} 是池能容纳的 atlas 数量上限。
 *
 * <p><b>惰性创建</b>：atlas 按需创建，避免冷启动一次性分配全部显存。
 *
 * <p><b>线程契约</b>：所有方法都涉及 GL 资源或状态变更，必须在
 * GL 线程上调用。池由单一线程独占，内部无同步。
 */
public final class GLAtlasPool implements AutoCloseable, GLThreadAffine {

    // ═══════════════════════════════════════════════
    // 字段
    // ═══════════════════════════════════════════════
    private final SerialExecutor glExecutor;
    /**
     * 池能容纳的 atlas 数量上限。
     */
    private final int capacity;

    /**
     * atlas 创建工厂。
     */
    private final GLAtlasFactory atlasFactory;

    /**
     * 池中的 atlas。
     */
    private final List<GLAtlas> atlases = new ArrayList<>();

    /**
     * 被提升到 显存的瓦片
     */
    private final WeakReferenceQueue<Tile> liftedTiles = new WeakReferenceQueue<>();

    /**
     * 冻结瓦片池，冻结时候不可变，这是强制保证下标稳定。
     * 以及page 有效
     */
    private final AtomicBoolean freeze = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param capacity     池能容纳的 atlas 数量，必须 ≥ 1
     * @param atlasFactory atlas 创建工厂，不能为 null
     */
    public GLAtlasPool(SerialExecutor glExecutor, int capacity, GLAtlasFactory atlasFactory) {
        if (glExecutor == null) {
            throw new NullPointerException("glExecutor");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        if (atlasFactory == null) {
            throw new IllegalArgumentException("atlasFactory must not be null");
        }
        this.glExecutor = glExecutor;
        this.capacity = capacity;
        this.atlasFactory = atlasFactory;
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return glExecutor;
    }

    /**
     * 返回当前池中 atlas 的快照视图。
     *
     * <p><b>快照语义</b>：返回的对象持有调用时刻的 atlas 数组副本。
     * 之后池的变化（acquire 新 atlas、extract 移除 atlas）不影响
     * 已返回的 page。调用方应在规划阶段开始时取快照，并在整个
     * 规划-执行周期内使用同一个 page。
     */
    public AtlasPoolPage page() {
        requireNoFreeze();
        if (freeze.compareAndSet(false, true)) {
            // Atlas 冻结
            return new DefaultAtlasPoolPage(atlases.toArray(new GLAtlas[0]));
        } else {
            throw new IllegalStateException("Freeze has already been set");
        }

    }

    private void requireNoFreeze() {
        if (freeze.get()) {
            throw new IllegalStateException("Freeze is already set");
        }
    }

    /**
     * 预热池——创建到指定数量的 atlas。
     *
     * <p><b>语义</b>：
     * <ul>
     *   <li>已有 {@code count} 个以上 atlas 时 no-op</li>
     *   <li>创建到 {@code count} 个——不检查空闲槽位，纯粹按数量</li>
     * </ul>
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     *
     * @param count 目标 atlas 数量，必须 {@code 0 <= count <= capacity}
     * @throws IllegalArgumentException count 为负或超过 capacity
     */
    public void warmup(int count) {
        requireGlThread();
        requireNoFreeze();
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        if (count > capacity) {
            throw new IllegalArgumentException(
                    "count " + count + " exceeds pool capacity " + capacity);
        }
        while (atlases.size() < count) {
            atlases.add(atlasFactory.create());
        }
    }

    /**
     * 预热到容量上限。
     *
     * <p>等价于 {@code warmup(capacity)}。
     */
    public void warmup() {
        warmup(capacity);
    }


    // ═══════════════════════════════════════════════
    // 清理（转移语义）
    // ═══════════════════════════════════════════════

    /**
     * 取出一个空闲的 atlas（所有瓦片均已释放）。
     *
     * <p><b>转移语义</b>：atlas 从池中移除，所有权交给调用方。
     * 调用方负责 {@link GLAtlas#close()} 释放底层纹理。
     *
     * @return 被取出的 atlas；没有空闲 atlas 时返回 {@code null}
     */
    public GLAtlas extractIdle() {
        requireGlThread();
        requireNoFreeze();
        Iterator<GLAtlas> it = atlases.iterator();
        while (it.hasNext()) {
            GLAtlas atlas = it.next();
            if (atlas.isEmpty()) {
                it.remove();
                return atlas;
            }
        }
        return null;
    }

    /**
     * 取出所有空闲的 atlas。
     *
     * <p>语义同 {@link #extractIdle()}——每个返回的 atlas 所有权
     * 都转移给调用方。
     *
     * @return 被取出的 atlas 列表；没有空闲 atlas 时返回空列表
     */
    public List<GLAtlas> extractAllIdle() {
        requireGlThread();
        requireNoFreeze();
        List<GLAtlas> result = new ArrayList<>();
        GLAtlas atlas;
        while ((atlas = extractIdle()) != null) {
            result.add(atlas);
        }
        return result;
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 释放全部 atlas 的底层纹理并清空池。幂等。
     *
     * <p>与 {@code extractAllIdle} 不同——本方法<b>不转移</b>所有权，
     * 直接释放全部纹理。
     */
    @Override
    public void close() {
        requireNoFreeze();
        for (GLAtlas atlas : atlases) {
            atlas.close();
        }
        atlases.clear();
    }

    // ═══════════════════════════════════════════════
    // 整理 / 压缩
    // ═══════════════════════════════════════════════

    /**
     * 只清理空闲 atlas，不移动任何瓦片。
     *
     * <p>等价于 {@code pack} 但不做搬运——纯清理。
     */
    public void purge() {
        requireGlThread();
        requireNoFreeze();
        releaseIdle();
    }

    /**
     * 尽可能压缩，等价于 {@code pack(1)}。
     */
    public void pack() {
        pack(1);
    }

    /**
     * 整理池中的 atlas，把瓦片压缩到不超过 {@code keepAtlases} 个非空 atlas。
     *
     * <p><b>策略</b>：非空 atlas 按已分配数量<b>升序</b>排列——已分配最少
     * 的优先作为 src 搬空。dst 是已分配最多的若干个（保留集）。这样每次
     * 搬运的瓦片数量最少。
     *
     * <p><b>keepAtlases 语义</b>：
     * <ul>
     *   <li>{@code keepAtlases >= 1}：尝试压缩到 ≤ keepAtlases 个非空 atlas</li>
     *   <li>{@code keepAtlases = 0}：尽可能压缩——效果等同 keepAtlases = 1，
     *       因为只要池中有数据，非空 atlas 至少 1 个</li>
     * </ul>
     *
     * <p><b>尽力而为</b>：空间不足时搬不动的留在原 atlas，不报错。
     *
     * <p><b>必须在 GL 线程上调用。</b>
     *
     * @param keepAtlases 目标非空 atlas 数量上限，≥ 0
     */
    public void pack(int keepAtlases) {
        requireGlThread();
        requireNoFreeze();
        if (keepAtlases < 0) {
            throw new IllegalArgumentException("keepAtlases must be >= 0: " + keepAtlases);
        }

        int effectiveLimit = Math.max(keepAtlases, 1);

        List<GLAtlas> nonEmpty = collectNonEmpty();
        if (nonEmpty.size() <= effectiveLimit) {
            releaseIdle();
            return;
        }
        nonEmpty.sort(Comparator.comparingInt(GLAtlas::getAllocatedCount));

        // 保留集 = 末尾 effectiveLimit 个（已分配最多）
        // src 集 = 开头 (size - effectiveLimit) 个（已分配最少）
        int keepFrom = nonEmpty.size() - effectiveLimit;

        for (int i = 0; i < keepFrom; i++) {
            GLAtlas src = nonEmpty.get(i);
            if (src.isEmpty()) {
                continue;    // 已被前面的搬运清空
            }

            for (int j = keepFrom; j < nonEmpty.size() && !src.isEmpty(); j++) {
                GLAtlas dst = nonEmpty.get(j);
                if (dst.isFull()) {
                    continue;
                }
                for (GLAtlas.GLTileDataImpl tile : src.getActiveTiles()) {
                    if (dst.isFull()) {
                        break;
                    }
                    GLAtlas.move(dst, src, tile);
                }
            }
        }

        releaseIdle();
    }

    /**
     * 收集所有非空 atlas。
     */
    private List<GLAtlas> collectNonEmpty() {
        List<GLAtlas> result = new ArrayList<>();
        for (GLAtlas atlas : atlases) {
            if (!atlas.isEmpty()) {
                result.add(atlas);
            }
        }
        return result;
    }

    /**
     * 取出并释放所有空闲 atlas。
     */
    private void releaseIdle() {
        for (GLAtlas idle : extractAllIdle()) {
            idle.close();
        }
    }


    /**
     * {@link AtlasPoolPage} 的默认实现。
     */
    private class DefaultAtlasPoolPage implements AtlasPoolPage {
        private final GLAtlas[] atlases;
        private final int capacity;

        public DefaultAtlasPoolPage(GLAtlas[] atlases) {
            if (atlases == null || atlases.length == 0) {
                throw new IllegalArgumentException("atlases must not be empty. If you call it in init, warmup before");
            }
            this.atlases = atlases;
            int totalSlot = 0;
            for (GLAtlas atlas : atlases) {
                totalSlot += atlas.getCapacity();
            }
            this.capacity = totalSlot;
        }


        @Override
        public int getCapacity() {
            return capacity;
        }

        @Override
        public void purge() {
            liftedTiles.cleanup();

            Iterator<Tile> it = liftedTiles.iterator();
            while (it.hasNext()) {
                Tile tile = it.next();

                if (!tile.isValid()) {            // 死瓦片：直接移除
                    it.remove();
                    continue;
                }

                if (!isOutdate(tile)) continue;

                GLAtlas.GLTileDataImpl gl =
                        tile.queryData(GLAtlas.GLTileDataImpl.class);
                if (gl == null || !gl.isValid()) {
                    it.remove();
                    continue;
                }

                if (!GLAtlas.isExclusive(gl)) {
                    continue;
                }

                // 有效且独占——正常换出
                GLAtlas.pageOut(tile);
                it.remove();
            }
        }

        @Override
        public void junk(List<Tile> tiles) {
            for (Tile tile : tiles) {
                if (GLAtlas.inPage(tile)) {
                    // 重复入队是安全的
                    liftedTiles.add(tile);
                }
            }
        }

        @Override
        public int ensureSlotsKeep(int counts, Set<Tile> keepTiles) {
            if (counts < 0) {
                throw new IllegalArgumentException("counts must be >= 0: " + counts);
            }

            liftedTiles.cleanup();

            int currentFree = 0;
            for (GLAtlas atlas : atlases) {
                currentFree += atlas.getFreeCount();
            }
            if (currentFree >= counts) {
                return currentFree;
            }

            int extraNeed = counts - currentFree;

            // ── 第一遍：驱逐脏瓦片 ──
            Iterator<Tile> it = liftedTiles.iterator();
            while (it.hasNext() && extraNeed > 0) {
                Tile tile = it.next();
                if (keepTiles != null && keepTiles.contains(tile)) continue;

                if (!tile.isValid()) {            // 死瓦片
                    it.remove();
                    continue;
                }

                if (!isOutdate(tile)) continue;

                GLAtlas.GLTileDataImpl gl =
                        tile.queryData(GLAtlas.GLTileDataImpl.class);

                if (gl == null || !gl.isValid()) {
                    it.remove();
                    continue;
                }

                if (!GLAtlas.isExclusive(gl)) {
                    continue;
                }

                if (GLAtlas.pageOut(tile)) {
                    extraNeed--;
                    currentFree++;
                }
                it.remove();
            }

            // ── 第二遍：驱逐干净瓦片 ──
            if (extraNeed > 0) {
                it = liftedTiles.iterator();
                while (it.hasNext() && extraNeed > 0) {
                    Tile tile = it.next();
                    if (keepTiles != null && keepTiles.contains(tile)) continue;

                    if (!tile.isValid()) {        // ← 死瓦片
                        it.remove();
                        continue;
                    }

                    if (isOutdate(tile)) continue;  // 脏的已在第一遍处理

                    GLAtlas.GLTileDataImpl gl =
                            tile.queryData(GLAtlas.GLTileDataImpl.class);

                    if (gl == null
                            || !gl.isValid()) {
                        it.remove();
                        continue;
                    }

                    if (!GLAtlas.isExclusive(gl)) {
                        continue;
                    }

                    if (GLAtlas.pageOut(tile)) {
                        extraNeed--;
                        currentFree++;
                    }
                    it.remove();
                }
            }

            return currentFree;
        }


        /**
         * 瓦片是否脏——脏 = GPU 数据已过期，驱逐代价最小。
         */
        private boolean isOutdate(Tile tile) {
            GLAtlas.GLTileData uploadableTile = tile.queryData(GLAtlas.GLTileData.class);
            return uploadableTile == null || uploadableTile.isDirty();
        }

        @Override
        public GLAtlas[] getAtlases() {
            return atlases;
        }

        @Override
        public boolean allocateAt(TextureTileSlot slot, TileRef ref) {
            int idx = slot.getUnit();
            GLAtlas atlas = atlases[idx];
            if (atlas == null) {
                throw new IllegalArgumentException("No atlas with idx  " + idx);
            }

            int layer = slot.getLayer();
            int dataColumn = slot.getDataColumn();
            int dataRow = slot.getDataRow();

            if (atlas.isOccupiedAt(layer, dataColumn, dataRow)) {
                return false;
            }

            TileFactory factory = new SlottedTileFactory(atlases, (tx, ty) -> slot);
            pageIn(ref, factory);

            // 记录提升的瓦片，方便换下
            liftedTiles.add(ref.getTile());
            return true;
        }

        @Override
        public boolean allocate(TileRef ref) {
            GLAtlas idleAtlas = null;
            for (GLAtlas atlas : atlases) {
                if (!atlas.isFull()) {
                    idleAtlas = atlas;
                    break;
                }
            }
            if (idleAtlas == null) {
                return false;
            }
            AtlasTileFactory factory = new AtlasTileFactory(idleAtlas);
            pageIn(ref, factory);

            // 记录提升的瓦片，方便换下
            liftedTiles.add(ref.getTile());
            return true;
        }

        @Override
        public AtlasLayerRange allocateContinuously(List<TileRef> tiles,
                                                    List<TileRef> restTiles) {
            if (tiles == null) throw new NullPointerException("tiles");
            if (restTiles == null) throw new NullPointerException("restTiles");

            if (tiles.isEmpty()) {
                return null;
            }

            // ── 1. 找空闲层最多的 atlas ──
            int bestAtlasIdx = -1;
            Range bestRange = Range.EMPTY;

            for (int i = 0; i < atlases.length; i++) {
                GLAtlas atlas = atlases[i];
                if (atlas == null) continue;
                Range free = atlas.findMaxContiguousFreeLayers();
                if (free.getCount() > bestRange.getCount()) {
                    bestAtlasIdx = i;
                    bestRange = free;
                }
            }

            if (bestAtlasIdx < 0) {
                restTiles.addAll(tiles);
                return null;
            }

            GLAtlas atlas = atlases[bestAtlasIdx];
            int tilesPerLayer = atlas.getLayout().getTilesPerLayer();
            int tilesPerEdge = atlas.getLayout().getTilesPerEdge();

            // ── 2. 计算能填充的整层数 ──
            int fullLayers = Math.min(tiles.size() / tilesPerLayer,
                    bestRange.getCount());
            if (fullLayers == 0) {
                restTiles.addAll(tiles);
                return null;
            }

            int toAllocate = fullLayers * tilesPerLayer;

            // ── 3. 用 allocateAt 精确放置 ──
            for (int i = 0; i < toAllocate; i++) {
                int layerOffset = i / tilesPerLayer;
                int local = i % tilesPerLayer;
                int dataColumn = local % tilesPerEdge;
                int dataRow = local / tilesPerEdge;

                int layer = bestRange.getStart() + layerOffset;

                TextureTileSlot slot = new TextureTileSlot(
                        bestAtlasIdx, layer, dataRow, dataColumn);

                if (!allocateAt(slot, tiles.get(i))) {
                    throw new IllegalStateException(
                            "slot unexpectedly occupied: atlas=" + bestAtlasIdx
                                    + ", layer=" + layer
                                    + ", col=" + dataColumn
                                    + ", row=" + dataRow);
                }
            }

            // ── 4. 剩余写回 ──
            for (int i = toAllocate; i < tiles.size(); i++) {
                restTiles.add(tiles.get(i));
            }

            // ── 5. 返回分配范围 ──
            return new AtlasLayerRange(atlas, bestRange.getStart(), fullLayers);
        }

        /**
         * 用给定 factory 把瓦片换入。
         *
         * <p>通过临时 generator 画布创建新 tile（factory 在此换入），
         * 再 merge 回原画布。generator 关闭时释放临时引用。
         */
        private void pageIn(TileRef ref, TileFactory factory) {
            TiledCanvas canvas = ref.getCanvas();
            try (TiledCanvas generator = new TiledCanvas(
                    canvas.getTileSize(),
                    canvas.getDefaultPixel(),
                    factory)) {
                generator.replaceTile(ref.getTile());
                canvas.mergeCanvas(generator);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            if (!freeze.compareAndSet(true, false)) {
                System.err.println("reenter GLAtlasPoolPage close");
            }
        }
    }
}