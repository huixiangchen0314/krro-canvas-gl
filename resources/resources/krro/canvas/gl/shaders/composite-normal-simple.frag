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
uniform sampler2DArray uTexture4;
uniform sampler2DArray uTexture5;
uniform sampler2DArray uTexture6;
uniform sampler2DArray uTexture7;
uniform sampler2DArray uTexture8;
uniform sampler2DArray uTexture9;
uniform sampler2DArray uTexture10;
uniform sampler2DArray uTexture11;
uniform sampler2DArray uTexture12;
uniform sampler2DArray uTexture13;
uniform sampler2DArray uTexture14;
uniform sampler2DArray uTexture15;

// 每个 bindable 的 (1/edgeU, 1/edgeV)——单位瓦片在纹理 UV 空间中的占比。
// 索引对齐 bindable 的单元号；atlas 两项相等，FBO 两项各自按视口尺寸算。
uniform vec2 uTextureScales[16];

uniform float uTileSize;

uniform vec2  uViewport;

uniform vec2 uImageMinPoint;
uniform vec2 uImageMaxPoint;

out vec4 fragColor;

vec4 sampleTexture(int unit, vec3 uv) {
    if (unit == 0)  return texture(uTexture0,  uv);
    if (unit == 1)  return texture(uTexture1,  uv);
    if (unit == 2)  return texture(uTexture2,  uv);
    if (unit == 3)  return texture(uTexture3,  uv);
    if (unit == 4)  return texture(uTexture4,  uv);
    if (unit == 5)  return texture(uTexture5,  uv);
    if (unit == 6)  return texture(uTexture6,  uv);
    if (unit == 7)  return texture(uTexture7,  uv);
    if (unit == 8)  return texture(uTexture8,  uv);
    if (unit == 9)  return texture(uTexture9,  uv);
    if (unit == 10) return texture(uTexture10, uv);
    if (unit == 11) return texture(uTexture11, uv);
    if (unit == 12) return texture(uTexture12, uv);
    if (unit == 13) return texture(uTexture13, uv);
    if (unit == 14) return texture(uTexture14, uv);
    if (unit == 15) return texture(uTexture15, uv);
    return vec4(0);
}

void main() {
    if (vCount <= 0) {
        fragColor = vec4(0);
        return;
    }

    if(any(lessThan(vPixel, uImageMinPoint)) ||
        any(greaterThan(vPixel, uImageMaxPoint))){
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

        vec2 layerPos = vec2(
                l0.x * vPixel.x + l0.z * vPixel.y + l1.x,
                l0.y * vPixel.x + l0.w * vPixel.y + l1.y
        );

        vec2 localUv = (layerPos - tileOrigin) / uTileSize;
        localUv.y = 1.0 - localUv.y;

        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        vec2 scale = uTextureScales[unit];
        vec2 uv = uv0 + localUv * scale;

        vec4 c = sampleTexture(unit, vec3(uv, texLayer));

        float layerAlpha = l1.z;
        c.rgb *= layerAlpha;
        c.a   *= layerAlpha;

        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}