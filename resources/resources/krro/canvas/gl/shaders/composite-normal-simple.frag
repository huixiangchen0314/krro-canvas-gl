#version 330 core

in vec2 vPixel;
flat in int vOffset;
flat in int vCount;

uniform samplerBuffer  uTileTable;
uniform samplerBuffer  uLayerTable;
uniform usamplerBuffer uIndexTable;

uniform sampler2DArray uTexture0;
uniform sampler2DArray uTexture1;
uniform sampler2DArray uTexture2;
uniform sampler2DArray uTexture3;

uniform float uTileScale;
uniform float uTileSize;

out vec4 fragColor;

vec4 sampleTexture(int unit, vec3 uv) {
    if (unit == 0) return texture(uTexture0, uv);
    if (unit == 1) return texture(uTexture1, uv);
    if (unit == 2) return texture(uTexture2, uv);
    return texture(uTexture3, uv);
}

void main() {
    if (vCount <= 0) {
        fragColor = vec4(0.0);
        return;
    }

    int hitCount       = 0;
    int sampledNonZero = 0;

    vec2 firstUv0      = vec2(0.0);
    vec2 firstLocalUv  = vec2(0.0);
    int  firstTexLayer = -1;
    int  firstUnit     = -1;

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

        vec2 global = vec2(
                l0.x * vPixel.x + l0.z * vPixel.y + l1.x,
                l0.y * vPixel.x + l0.w * vPixel.y + l1.y
        );

        vec2 localUv = (global - tileXY * uTileSize) / uTileSize;

        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        if (hitCount == 0) {
            firstUv0      = uv0;
            firstLocalUv  = localUv;
            firstTexLayer = texLayer;
            firstUnit     = unit;
        }
        hitCount++;

        vec2 uv = uv0 + localUv * uTileScale;
        vec4 c  = sampleTexture(unit, vec3(uv, texLayer));
        if (any(greaterThan(c, vec4(0.0)))) {
            sampledNonZero++;
        }
    }

    if (hitCount == 0) {
        fragColor = vec4(1.0, 0.0, 0.0, 1.0);
        return;
    }

    if (sampledNonZero == 0) {
        // 全 0 采样——把第一个命中瓦片的元信息输出到颜色
        // R = uv0.x          （应在 [0, 1)，瓦片在层内的 u 起点）
        // G = uv0.y          （应在 [0, 1)，瓦片在层内的 v 起点）
        // B = texLayer / 8   （层索引，0=黑 1=白）
        fragColor = vec4(firstUv0.x, firstUv0.y, float(firstTexLayer) / 8.0, 1.0);
        return;
    }

    if (sampledNonZero == hitCount) {
        fragColor = vec4(0.0, 1.0, 0.0, 1.0);
    } else {
        fragColor = vec4(1.0, 1.0, 0.0, 1.0);
    }
}