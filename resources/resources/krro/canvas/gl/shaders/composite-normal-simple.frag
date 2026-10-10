#version 330 core

in vec2 vPixel;
flat in int vOffset;
flat in int vCount;
// 屏幕瓦片=>该瓦片的可能投影的瓦片表范围
uniform usamplerBuffer uIndexTable;
// 瓦片表 => 图层瓦片信息
uniform samplerBuffer  uTileTable;
// 图层表 =>图层详细信息
uniform samplerBuffer  uLayerTable;

uniform sampler2DArray uTexture0;
uniform sampler2DArray uTexture1;
uniform sampler2DArray uTexture2;
uniform sampler2DArray uTexture3;

// 每个 bindable 的 (1/edgeU, 1/edgeV)——单位瓦片在纹理 UV 空间中的占比。
// 索引对齐 bindable 的单元号；atlas 两项相等，FBO 两项各自按视口尺寸算。
uniform vec2 uTextureScales[4];

uniform float uTileSize;

uniform vec2  uViewport;

out vec4 fragColor;

vec4 sampleTexture(int unit, vec3 uv) {
    if (unit == 0) return texture(uTexture0, uv);
    if (unit == 1) return texture(uTexture1, uv);
    if (unit == 2) return texture(uTexture2, uv);
    if (unit == 3) return texture(uTexture3, uv);
    return vec4(0);
}

void main() {
    if (vCount <= 0) {
        fragColor = vec4(0);
        return;
    }

    vec4 acc = vec4(0);

    for (int i = 0; i < vCount; i++) {
        uint tileIdx = texelFetch(uIndexTable, vOffset + i).r;

        vec4 e0 = texelFetch(uTileTable, int(tileIdx) * 2 + 0);
        vec4 e1 = texelFetch(uTileTable, int(tileIdx) * 2 + 1);

        vec2 uv0        = e0.xy;
        int  unit       = int(e0.z + 0.5);
        int  texLayer   = int(e0.w + 0.5);
        vec2 tileOrigin = e1.xy;
        int  layerEntry = int(e1.z + 0.5);

        vec4 l0 = texelFetch(uLayerTable, layerEntry * 2 + 0);
        vec4 l1 = texelFetch(uLayerTable, layerEntry * 2 + 1);

        // 屏幕像素 → 图层局部像素（画布空间，y 向下）
        vec2 layerPos = vec2(
                l0.x * vPixel.x + l0.z * vPixel.y + l1.x,
                l0.y * vPixel.x + l0.w * vPixel.y + l1.y
        );

        // 图层局部像素 → 瓦片内局部 UV（画布空间，y 向下）
        vec2 localUv = (layerPos - tileOrigin) / uTileSize;

        // 画布空间 y 向下，图集存储已翻转为 GL 空间（v 向上）——
        // 采样 UV 需要一次 y 反向。
        localUv.y = 1.0 - localUv.y;

        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        // per-unit 缩放——atlas 与 FBO 的 edgeU/edgeV 不一定相同，
        // 甚至同一纹理的 edgeU 与 edgeV 也可能不同（非正方形视口）。
        vec2 scale = uTextureScales[unit];
        vec2 uv = uv0 + localUv * scale;

        vec4 c = sampleTexture(unit, vec3(uv, texLayer));

        c.rgb *= c.a;

        float layerAlpha = l1.z;
        c.rgb *= layerAlpha;
        c.a   *= layerAlpha;

        // premultiplied alpha 的 source-over
        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}