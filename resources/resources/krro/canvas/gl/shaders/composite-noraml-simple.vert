// composite-normal-simple.vert
#version 330 core

// ═══════════════════════════════════════════════
// 逐顶点 attribute
// ═══════════════════════════════════════════════
layout(location = 0) in vec2 aPos;       // 单位四边形 [0,1]²

// ═══════════════════════════════════════════════
// 逐实例 attribute
// ═══════════════════════════════════════════════
layout(location = 1) in vec4 aInstance;  // [tileX, tileY, offset, count]

// ═══════════════════════════════════════════════
// 全局 uniform
// ═══════════════════════════════════════════════
uniform vec2  uViewport;                 // 视口尺寸（已对齐到 tileSize 倍数）
uniform float uTileSize;                 // 瓦片边长

// ═══════════════════════════════════════════════
// 传给 fragment
// ═══════════════════════════════════════════════
out vec2  vPixel;
flat out int vOffset;
flat out int vCount;

void main() {
    vec2 world = aInstance.xy * uTileSize + aPos * uTileSize;

    vec2 ndc = world / uViewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);

    vPixel  = world;
    vOffset = int(aInstance.z + 0.5);
    vCount  = int(aInstance.w + 0.5);
}