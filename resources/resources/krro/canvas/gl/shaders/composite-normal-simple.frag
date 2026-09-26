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
    vec4 acc = vec4(0.0);

    fragColor = vec4(1.0, 0, 0, 0.8);
}