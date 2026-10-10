package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.canvas.gl.tile.GLTile;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;
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

        // ── 按 shader 分组 ──
        ShaderDrwBundler bundler = MockShaderDrwBundler.INSTANCE;
        List<ShaderLayerBundle> bundles = bundler.bundle(layers);
        if (bundles.isEmpty()) {
            return null;
        }

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

        // ── 收集每批瓦片需求 + 全局并集 ──
        List<Set<Tile>> demandPerBatch = new ArrayList<>(batchBundles.size());
        Set<Tile> allDemands = new HashSet<>();
        for (ShaderBatchBundle batchBundle : batchBundles) {
            Set<Tile> demand = new HashSet<>();
            for (ShaderLayerTileRef layerTileRef : batchBundle.tileSet.layerTileRefs) {
                demand.add(layerTileRef.tile);
            }
            demandPerBatch.add(demand);
            allDemands.addAll(demand);
        }

        // ── 确保 atlas 有足够空闲槽位 ──
        int needTiles = Math.min(minSingleDrawnTiles, maxSingleTiles);

        // 第一轮：全局保留所有批次的瓦片
        int ensured = page.ensureSlotsKeep(needTiles, allDemands);

        // 逆序降级：从最后一批开始，逐批解除保留
        if (ensured < needTiles) {
            // 满足需求，或者仅仅剩下一个批次
            for (int b = demandPerBatch.size() - 1; b > 1 && ensured < needTiles; b--) {
                allDemands.removeAll(demandPerBatch.get(b));
                ensured = page.ensureSlotsKeep(needTiles, allDemands);
            }
        }

        GLAtlas[] atlases = page.getAtlases();
        // ── 每批：分配槽位 + 打包 + 构造 bundle ──
        List<ShaderDraw> draws = new ArrayList<>(batchBundles.size());
        GLBindable[] bindables = new GLBindable[atlases.length];
        System.arraycopy(atlases, 0, bindables, 0, atlases.length);

        GLBindable[] bindablesA = new GLBindable[atlases.length + 1];
        System.arraycopy(atlases, 0, bindablesA, 0, atlases.length);
        bindablesA[atlases.length] = fboA.getColorAttachment();

        GLBindable[] bindablesB = new GLBindable[atlases.length + 1];
        System.arraycopy(atlases, 0, bindablesB, 0, atlases.length);
        bindablesB[atlases.length] = fboB.getColorAttachment();

        for (ShaderBatchBundle batchBundle : batchBundles) {
            Shader shader;
            String shaderKey = batchBundle.bundle.getShaderKey();
            ShaderLayerTileSet tileSet = batchBundle.tileSet;

            List<ShaderLayerTileSet> tileSets = new ArrayList<>();
            List<ShaderLayerTileRef> tiles = tileSet.layerTileRefs;
            int tileCount = tiles.size();
            if (tileCount < 1){
                continue;
            }
            if (tileCount <= ensured
                    // 无空闲瓦片，就只能全清，全划分了，虽然不太可能
                    || ensured == 0){
                tileSets.add(tileSet);
            }else {
                // 批次划分，总是用尽确保的容量
                ArrayList<ShaderLayerTileRef> tileRefs = new ArrayList<>(ensured);
                int preBatch = 0;
                int uploaded = 0;
                for (int idx = 0; idx < tileCount; idx++) {
                    if (tiles.get(idx).tile.queryData(GLTile.class) != null) {
                        uploaded ++;
                    }
                    // 已经是gpu的的不占用批次
                    int batch = (idx - uploaded) / ensured;
                    if (batch > preBatch) {
                        preBatch = batch;
                        tileSets.add(tileSet.withRefs(tileRefs));
                        tileRefs = new ArrayList<>();
                    }

                    tileRefs.add(tiles.get(idx));
                }
                // 一定会有残留批次
                tileSets.add(tileSet.withRefs(tileRefs));
            }


            // Shader 构建 策略
            for (ShaderLayerTileSet tilesToDraw : tileSets) {
                GLBindable[] resolvedBundables;
                ShaderFactoryContext factoryContext;

                if (draws.isEmpty()){
                    resolvedBundables = bindables;
                } else {
                    if (draws.size() % 2 == 1){
                        resolvedBundables = bindablesA;
                        TileProjector.projectLayerIntoFirst(fboALayer, dirtyList, tileSize, tilesToDraw);
                    }else {
                        resolvedBundables = bindablesB;
                        TileProjector.projectLayerIntoFirst(fboBLayer, dirtyList, tileSize, tilesToDraw);
                    }
                }
                factoryContext = new ShaderFactoryContext(tilesToDraw, viewTileInfo, resolvedBundables, context);

                if (Objects.equals(shaderKey, Blends.NORMAL)){
                    shader = SimpleNormalShaderFactory.INSTANCE.create(factoryContext);
                }else {
                    throw new IllegalArgumentException("shader keys must match");
                }

                draws.add(new ShaderDraw(shader, resolvedBundables));
            }
        }

        return CompositeRequest.of(context,
                viewTileInfo,
                draws,
                page,
                fboA, fboB);
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