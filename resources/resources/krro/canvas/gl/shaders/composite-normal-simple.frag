#version 330 core

in vec2 vPixel;
flat in int vOffset;
flat in int vCount;

// ═══════════════════════════════════════════════
// 瓦片表：texture buffer，每瓦片 2 个 vec4
//   e0 = [u0, v0, unit, texLayer]
//   e1 = [tileX, tileY, layerEntry, pad]
// ═══════════════════════════════════════════════
uniform samplerBuffer uTileTable;

// ═══════════════════════════════════════════════
// 图层表：texture buffer，每图层 2 个 vec4
//   l0 = [invA, invB, invC, invD]      逆变换线性部分
//   l1 = [invTx, invTy, alpha, pad]    逆变换平移 + 图层 alpha
// ═══════════════════════════════════════════════
uniform samplerBuffer uLayerTable;

// ═══════════════════════════════════════════════
// 索引表：usamplerBuffer，扁平 uint
// ═══════════════════════════════════════════════
uniform usamplerBuffer uIndexTable;

// ═══════════════════════════════════════════════
// 纹理采样器。shader 只认识纹理，不关心背后是
// atlas 还是独立纹理——那是 CPU 侧的资源组织方式。
// ═══════════════════════════════════════════════
uniform sampler2DArray uTexture0;
uniform sampler2DArray uTexture1;
uniform sampler2DArray uTexture2;
uniform sampler2DArray uTexture3;

// ═══════════════════════════════════════════════
// 几何参数
// ═══════════════════════════════════════════════
uniform float uTileScale;   // 单瓦片在纹理层内的 uv 尺寸 = 1 / tilesPerEdge
uniform float uTileSize;    // 瓦片边长（像素）

uniform vec2  uViewport;


out vec4 fragColor;

// ── 按 unit 选纹理 ──────────────────────────────
vec4 sampleTexture(int unit, vec3 uv) {
    if (unit == 0) return texture(uTexture0, uv);
    if (unit == 1) return texture(uTexture1, uv);
    if (unit == 2) return texture(uTexture2, uv);
    if (unit == 3) return texture(uTexture3, uv);
    return vec4(0.0);
}


void main() {
    if (vCount <= 0) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 acc = vec4(0.0);

    for (int i = 0; i < vCount; i++) {
        uint tileIdx = texelFetch(uIndexTable, vOffset + i).r;

        vec4 e0 = texelFetch(uTileTable, int(tileIdx) * 2 + 0);
        vec4 e1 = texelFetch(uTileTable, int(tileIdx) * 2 + 1);

        vec2 uv0        = e0.xy;
        int  unit       = int(e0.z + 0.5);
        int  texLayer   = int(e0.w + 0.5);
        vec2 tileXY     = e1.xy;
        int  layerEntry = int(e1.z + 0.5);

        vec4 l0 = texelFetch(uLayerTable, layerEntry * 2 + 0);
        vec4 l1 = texelFetch(uLayerTable, layerEntry * 2 + 1);

        vec2 layerPos = vec2(
                l0.x * vPixel.x + l0.z * vPixel.y + l1.x,
                l0.y * vPixel.x + l0.w * vPixel.y + l1.y
        );

        vec2 localUv = (layerPos - tileXY * uTileSize) / uTileSize;

        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        // 瓦片内 uv → 纹理层内 uv
        // 上传原样：data[0] 落在纹理 v 低处
        // 采样翻转：localUv.y = 0（瓦片顶）→ 取 v 高处
        vec2 uv = uv0 + vec2(localUv.x, 1.0 - localUv.y) * uTileScale;

        vec4 c = sampleTexture(unit, vec3(uv, texLayer));

        // 预乘 alpha
        c.rgb *= c.a;

        // 图层 alpha
        float layerAlpha = l1.z;
        c.rgb *= layerAlpha;
        c.a   *= layerAlpha;

        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}