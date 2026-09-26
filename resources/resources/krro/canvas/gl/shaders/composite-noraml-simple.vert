#version 330 core

layout(location = 0) in vec2 aPos;       // 单位四边形 [0,1]²
layout(location = 1) in vec4 aInstance;  // [tileX, tileY, offset, count]

uniform vec2  uViewport;                 // 视口尺寸（已对齐到 tileSize 倍数）
uniform float uTileSize;                 // 瓦片边长

out vec2  vPixel;
flat out int vOffset;
flat out int vCount;

void main() {
    // 屏幕像素坐标：左上角 (0,0)，y 向下
    vec2 world = aInstance.xy * uTileSize + aPos * uTileSize;

    // 像素 → NDC；NDC.y 向上，屏幕 y 向下——翻转
    vec2 ndc = world / uViewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, -ndc.y, 0.0, 1.0);

    vPixel  = world;
    vOffset = int(aInstance.z + 0.5);
    vCount  = int(aInstance.w + 0.5);
}