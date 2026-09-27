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

uniform vec2  uViewport;

out vec4 fragColor;

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
        vec2 tileOrigin     = e1.xy;
        int  layerEntry = int(e1.z + 0.5);

        vec4 l0 = texelFetch(uLayerTable, layerEntry * 2 + 0);
        vec4 l1 = texelFetch(uLayerTable, layerEntry * 2 + 1);

        vec2 layerPos = vec2(
                l0.x * vPixel.x + l0.z * vPixel.y + l1.x,
                l0.y * vPixel.x + l0.w * vPixel.y + l1.y
        );

        vec2 localUv = (layerPos - tileOrigin) / uTileSize;

        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        vec2 uv = uv0 + localUv * uTileScale;

        vec4 c = sampleTexture(unit, vec3(uv, texLayer));

        c.rgb *= c.a;

        float layerAlpha = l1.z;
        c.rgb *= layerAlpha;
        c.a   *= layerAlpha;

        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}