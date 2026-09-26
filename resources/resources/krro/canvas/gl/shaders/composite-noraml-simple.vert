#version 330 core

layout(location = 0) in vec2 aPos;       // 单位四边形 [0,1]²
layout(location = 1) in vec4 aInstance;  // [tileX, tileY, offset, count]

uniform vec2  uViewport;
uniform float uTileSize;

out vec2  vPixel;
flat out int vOffset;
flat out int vCount;

void main() {
    vec2 world = aInstance.xy * uTileSize + aPos * uTileSize;

    vec2 ndc = world / uViewport * 2.0 - 1.0;
    gl_Position = vec4(ndc.x, ndc.y, 0.0, 1.0);

    vPixel  = world;
    vOffset = int(aInstance.z + 0.5);
    vCount  = int(aInstance.w + 0.5);
}