package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.AbstractGLTiledTexture;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按独立纹理数量上限切分 bundle 的装饰器。
 *
 * <p><b>职责</b>：delegate 完成 shader 分组后，把每组内累计使用
 * 独立纹理数超过 {@code maxTextureCount} 的图层序列切成多段。
 *
 * <p><b>只统计独立纹理</b>：{@code AbstractGLTiledTexture} 的瓦片
 * 每层独占纹理，需要参与计数。atlas 是打包纹理——多个图层的瓦片
 * 共享同一 atlas，且 atlas 数量由框架全局固定，不随图层增减。
 * 规划阶段的 bindables 长度校验会单独处理 atlas 的占位——本装饰器
 * 只约束独立纹理的分布。
 *
 * <p><b>单图层超限</b>：某个图层单独使用的独立纹理数超过上限时，
 * 该图层独占一段——不再细分。是否可接受由 planner 的 bindables
 * 长度检查决定。
 *
 * <p><b>顺序契约</b>：不改变 delegate 输出的 bundle 顺序；同一
 * bundle 内切分出的段按图层原顺序排列。
 */
public final class TextureCountLimitDrawBundler implements ShaderDrawBundler {

    private final int maxTextureCount;
    private final ShaderDrawBundler delegate;

    public TextureCountLimitDrawBundler(ShaderDrawBundler delegate, int maxTextureCount) {
        this.maxTextureCount = maxTextureCount;
        this.delegate = delegate;
    }

    @Override
    public List<ShaderLayerBundle> bundle(List<ILayer> layers) {
        List<ShaderLayerBundle> bundles = delegate.bundle(layers);
        List<ShaderLayerBundle> result = new ArrayList<>(bundles.size());
        for (ShaderLayerBundle bundle : bundles) {
            split(bundle, result);
        }
        return result;
    }

    private void split(ShaderLayerBundle bundle, List<ShaderLayerBundle> out) {
        List<ILayer> layers = bundle.getLayers();
        String shaderKey = bundle.getShaderKey();

        List<ILayer> current = new ArrayList<>();
        Set<GLTexture> textures = new HashSet<>();

        for (ILayer layer : layers) {
            Set<GLTexture> layerTextures = collectTextures(layer);

            Set<GLTexture> merged = new HashSet<>(textures);
            merged.addAll(layerTextures);

            if (merged.size() > maxTextureCount && !current.isEmpty()) {
                out.add(new ShaderLayerBundle(shaderKey, current));
                current = new ArrayList<>();
                textures = new HashSet<>();
            }
            current.add(layer);
            textures.addAll(layerTextures);
        }
        if (!current.isEmpty()) {
            out.add(new ShaderLayerBundle(shaderKey, current));
        }
    }

    /**
     * 收集图层 canvas 中用到的独立纹理（{@code AbstractGLTiledTexture}）。
     * atlas 不属于独立纹理——不在此统计。
     */
    private Set<GLTexture> collectTextures(ILayer layer) {
        Set<GLTexture> result = new HashSet<>();
        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return result;

        for (Long tileKey : canvas.getTiles()) {
            Tile tile = canvas.getTile(tileKey);
            if (tile == null) continue;

            AbstractGLTiledTexture.GLTextureTileData td =
                    tile.queryData(AbstractGLTiledTexture.GLTextureTileData.class);
            if (td != null) {
                result.add(td.getTexture());
            }
        }
        return result;
    }
}