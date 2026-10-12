package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.AbstractGLTiledTexture;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.canvas.gl.tile.GLTile;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.*;

public final class CompositePlanner {

    private CompositePlanner() {}

    // ═══════════════════════════════════════════════
    // 主流程
    // ═══════════════════════════════════════════════

    public static CompositeRequest plan(
            List<ILayer> layers,
            GLCompositeContext context,
            GLFramebuffer fboA,
            GLFramebuffer fboB,
            Set<Long> dirtyTiles) {

        long t0 = System.nanoTime();

        int viewWidth  = context.getViewWidth();
        int viewHeight = context.getViewHeight();
        int tileSize   = context.getTileSize();

        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalStateException(
                    "view size not set; call adjustViewSize first");
        }
        if (layers == null) {
            throw new IllegalArgumentException("layers must not be null");
        }
        if (dirtyTiles == null) {
            throw new IllegalArgumentException("dirtyTiles must not be null");
        }
        if (layers.isEmpty() || dirtyTiles.isEmpty()) {
            return null;
        }
        if (fboA.getColorAttachment() == fboB.getColorAttachment()) {
            throw new IllegalStateException(
                    "ping-pong requires distinct color attachments; "
                            + "pool returned two FBOs sharing the same texture: "
                            + fboA.getColorAttachment());
        }
        List<Long> dirtyList = new ArrayList<>(dirtyTiles);
        Collections.sort(dirtyList);

        int minSingleDrawnTiles = context.getMinSingleDrawnTiles();
        SerialExecutor glExecutor = context.getGlExecutor();
        AtlasPoolPage page = context.getAtlasPoolPage();
        page.purge();

        ILayer fboALayer = FBOUtils.wrapLayer(fboA, tileSize, glExecutor);
        ILayer fboBLayer = FBOUtils.wrapLayer(fboB, tileSize, glExecutor);

        ViewTileInfo viewTileInfo = buildViewGrid(
                dirtyList, viewWidth, viewHeight, tileSize);

        long t1 = System.nanoTime();   // 准备工作完成

        // ── 按 shader 分组 ──
        ShaderDrawBundler bundler = new TextureCountLimitDrawBundler(
                MockShaderDrawBundler.INSTANCE,
                ShaderConstants.MAX_LAYER_TEXTURE_UNIT_COUNT - 1
        );
        List<ShaderLayerBundle> bundles = bundler.bundle(layers);
        if (bundles.isEmpty()) {
            return null;
        }

        long t2 = System.nanoTime();   // bundle 分组完成

        // ── 每批：注入 backdrop，收集可见瓦片 ──
        int maxSingleTiles = 0;
        List<ShaderBatchBundle> batchBundles = new ArrayList<>();

        for (ShaderLayerBundle shaderLayerBundle : bundles) {
            String shaderKey = shaderLayerBundle.getShaderKey();
            List<ILayer> bundledLayers = shaderLayerBundle.getLayers();

            ShaderLayerTileSet layerTileSet = TileProjector.projectLayersInto(
                    bundledLayers, dirtyList, tileSize,
                    ShaderLayerTileSet.create());

            if (layerTileSet.layerTileRefs.isEmpty()) {
                continue;
            }

            int size = layerTileSet.layerTileRefs.size();
            if (size > maxSingleTiles) {
                maxSingleTiles = size;
            }

            ShaderLayerBundle rebuilt = new ShaderLayerBundle(shaderKey, bundledLayers);
            batchBundles.add(new ShaderBatchBundle(rebuilt, layerTileSet));
        }

        if (maxSingleTiles < 1) {
            return null;
        }

        long t3 = System.nanoTime();   // 投影完成

        // ── 收集每批瓦片需求 + 全局并集 ──
        List<Set<Tile>> demandPerBatch = new ArrayList<>(batchBundles.size());
        Set<Tile> allDemands = new HashSet<>();
        for (ShaderBatchBundle batchBundle : batchBundles) {
            Set<Tile> demand = new HashSet<>();
            for (ShaderLayerTileRef layerTileRef : batchBundle.tileSet.layerTileRefs) {
                demand.add(layerTileRef.getTile());
            }
            demandPerBatch.add(demand);
            allDemands.addAll(demand);
        }

        long t4 = System.nanoTime();   // demand 收集完成

        // ── 确保 atlas 有足够空闲槽位 ──
        int needTiles = Math.min(minSingleDrawnTiles, maxSingleTiles);

        // 第一轮：全局保留所有批次的瓦片
        int ensured = page.ensureSlotsKeep(needTiles, allDemands);

        // 逆序降级：从最后一批开始，逐批解除保留
        if (ensured < needTiles) {
            for (int b = demandPerBatch.size() - 1; b > 1 && ensured < needTiles; b--) {
                allDemands.removeAll(demandPerBatch.get(b));
                ensured = page.ensureSlotsKeep(needTiles, allDemands);
            }
        }

        long t5 = System.nanoTime();   // 槽位确保完成

        GLAtlas[] atlases = page.getAtlases();
        GLTexture texA = fboA.getColorAttachment();
        GLTexture texB = fboB.getColorAttachment();

        List<ShaderDraw> draws = new ArrayList<>();
        int splitCount = 0;

        for (ShaderBatchBundle batchBundle : batchBundles) {
            Shader shader;
            List<ILayer> batchLayers = batchBundle.bundle.getLayers();
            String shaderKey = batchBundle.bundle.getShaderKey();
            ShaderLayerTileSet tileSet = batchBundle.tileSet;

            // ── 本批次实际用到的外部纹理（非 atlas） ──
            Set<GLTexture> batchTextures = LayerUtils.collectTextures(batchLayers);

            int baseLen = atlases.length + batchTextures.size();

            GLBindable[] bindables = new GLBindable[baseLen];
            System.arraycopy(atlases, 0, bindables, 0, atlases.length);
            {
                int bi = atlases.length;
                for (GLTexture t : batchTextures) {
                    bindables[bi++] = t;
                }
            }

            GLBindable[] bindablesA = appendBindable(bindables, baseLen, texA);
            GLBindable[] bindablesB = appendBindable(bindables, baseLen, texB);

            List<ShaderLayerTileSet> tileSets = new ArrayList<>();
            List<ShaderLayerTileRef> tiles = tileSet.layerTileRefs;

            int tileCount = tiles.size();
            if (tileCount < 1) {
                continue;
            }
            if (tileCount <= ensured || ensured == 0) {
                tileSets.add(tileSet);
            } else {
                ArrayList<ShaderLayerTileRef> tileRefs = new ArrayList<>(ensured);
                int preBatch = 0;
                int uploaded = 0;
                for (int idx = 0; idx < tileCount; idx++) {
                    if (tiles.get(idx).getTile().queryData(GLTile.class) != null) {
                        uploaded++;
                    }
                    int batch = (idx - uploaded) / ensured;
                    if (batch > preBatch) {
                        preBatch = batch;
                        tileSets.add(tileSet.withRefs(tileRefs));
                        tileRefs = new ArrayList<>();
                    }
                    tileRefs.add(tiles.get(idx));
                }
                tileSets.add(tileSet.withRefs(tileRefs));
            }
            splitCount += tileSets.size();

            // Shader 构建 策略
            for (ShaderLayerTileSet tilesToDraw : tileSets) {
                GLBindable[] resolvedBundables;
                ShaderFactoryContext factoryContext;

                if (draws.isEmpty()) {
                    resolvedBundables = bindables;
                } else {
                    if (draws.size() % 2 == 1) {
                        resolvedBundables = bindablesA;
                        TileProjector.projectLayerIntoFirst(fboALayer, dirtyList, tileSize, tilesToDraw);
                    } else {
                        resolvedBundables = bindablesB;
                        TileProjector.projectLayerIntoFirst(fboBLayer, dirtyList, tileSize, tilesToDraw);
                    }
                }
                factoryContext = new ShaderFactoryContext(tilesToDraw, viewTileInfo, resolvedBundables, context);

                if (Objects.equals(shaderKey, Blends.NORMAL)) {
                    shader = SimpleNormalShaderFactory.INSTANCE.create(factoryContext);
                } else {
                    throw new IllegalArgumentException("shader keys must match");
                }

                draws.add(new ShaderDraw(shader, resolvedBundables));
            }
        }

        long t6 = System.nanoTime();

        System.out.println("[plan]"
                + " layers=" + layers.size()
                + " dirty=" + dirtyTiles.size()
                + " batches=" + batchBundles.size()
                + " draws=" + draws.size()
                + " splits=" + splitCount
                + " | prep=" + (t1 - t0) / 1_000_000 + "ms"
                + " bundle=" + (t2 - t1) / 1_000_000 + "ms"
                + " project=" + (t3 - t2) / 1_000_000 + "ms"
                + " demand=" + (t4 - t3) / 1_000_000 + "ms"
                + " ensure=" + (t5 - t4) / 1_000_000 + "ms"
                + " build=" + (t6 - t5) / 1_000_000 + "ms"
                + " | total=" + (t6 - t0) / 1_000_000 + "ms");

        return CompositeRequest.of(context,
                viewTileInfo,
                draws,
                page,
                fboA, fboB);
    }

    /**
     * 在 {@code base} 末尾追加 {@code target}——若 {@code targetInBase}
     * 为 true，说明目标已经在基础数组中，直接返回基础数组本身（共享
     * 引用，避免重复条目）。
     *
     * <p><b>数组只读</b>：返回的数组在合成流程中不被修改——共享是
     * 安全的。{@code bindables} 数组仅用于：
     * <ul>
     *   <li>绘制前逐 unit 绑定纹理</li>
     *   <li>{@code allocateSlots} / {@code packBuffers} 通过
     *       {@code slotOf} 反查纹理的单元号</li>
     * </ul>
     * 两处都只读。
     */
    private static GLBindable[] appendBindable(
            GLBindable[] base, int offset, GLBindable target) {

        GLBindable[] result = new GLBindable[offset + 1];
        System.arraycopy(base, 0, result, 0, offset);
        result[offset] = target;
        return result;
    }

    // ═══════════════════════════════════════════════
    // 后续方法不变
    // ═══════════════════════════════════════════════

    private static ViewTileInfo buildViewGrid(List<Long> dirtyTiles,
                                              int viewWidth, int viewHeight,
                                              int tileSize) {
        int[] tileXY = new int[dirtyTiles.size() * 2];
        int cursor = 0;
        for (Long key : dirtyTiles) {
            tileXY[cursor++] = TiledCanvas.unpackTx(key);
            tileXY[cursor++] = TiledCanvas.unpackTy(key);
        }
        return ViewTileInfo.createPartial(
                viewWidth, viewHeight, tileSize, tileXY);
    }


}