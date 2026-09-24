// composite-normal-simple.frag
#version 330 core

in vec2 vPixel;
flat in int vOffset;
flat in int vCount;

// ═══════════════════════════════════════════════
// 瓦片表：texture buffer，每条目 3 个 vec4
// ═══════════════════════════════════════════════
uniform samplerBuffer uTileTable;

// ═══════════════════════════════════════════════
// 索引表：usamplerBuffer，扁平 int
// ═══════════════════════════════════════════════
uniform usamplerBuffer uIndexTable;

// ═══════════════════════════════════════════════
// atlas 采样器。下标即单元，规划阶段对齐
// ═══════════════════════════════════════════════
uniform sampler2DArray uAtlas0;
uniform sampler2DArray uAtlas1;
uniform sampler2DArray uAtlas2;
uniform sampler2DArray uAtlas3;

// ═══════════════════════════════════════════════
// 几何参数
// ═══════════════════════════════════════════════
uniform float uTileScale;   // tileSize / layerSize

out vec4 fragColor;

// ── 按 atlasIndex 选 atlas ──────────────────────
vec4 sampleAtlas(int idx, vec3 uv) {
    if (idx == 0) return texture(uAtlas0, uv);
    if (idx == 1) return texture(uAtlas1, uv);
    if (idx == 2) return texture(uAtlas2, uv);
    return texture(uAtlas3, uv);
}

// ── 2D 仿射求逆 ─────────────────────────────────
// 输入 [a b c d tx ty]，输出 [A B C D TX TY]
// 满足 (A,C,TX; B,D,TY) = (a,c,tx; b,d,ty)⁻¹
void invertMat2d(in vec4 m, in vec2 t,
                 out vec2 m0, out vec2 m1, out vec2 tr) {
    float det = m.x * m.w - m.y * m.z;
    float invDet = 1.0 / det;

    float A =  m.w * invDet;
    float B = -m.y * invDet;
    float C = -m.z * invDet;
    float D =  m.x * invDet;
    float TX = -(A * t.x + C * t.y);
    float TY = -(B * t.x + D * t.y);

    m0 = vec2(A, B);
    m1 = vec2(C, D);
    tr = vec2(TX, TY);
}

void main() {
    vec4 acc = vec4(0.0);

    for (int i = 0; i < vCount; i++) {
        // 从索引表读瓦片表条目下标
        uint entryIdx = texelFetch(uIndexTable, vOffset + i).r;

        // 从瓦片表读 3 个 vec4
        vec4 e0 = texelFetch(uTileTable, int(entryIdx) * 3 + 0);
        vec4 e1 = texelFetch(uTileTable, int(entryIdx) * 3 + 1);
        vec4 e2 = texelFetch(uTileTable, int(entryIdx) * 3 + 2);

        // ── 解包字段 ──
        vec4 mat    = e0;              // [a, b, c, d]
        vec2 trans  = e1.xy;           // [tx, ty]
        vec2 uv0    = e1.zw;           // [u0, v0]
        int  aIdx   = int(e2.x + 0.5); // atlasIndex
        int  layer  = int(e2.y + 0.5); // 层
        float alpha = e2.z;            // 透明度

        // ── 视口坐标 → 瓦片本地 uv ──
        vec2 m0, m1, tr;
        invertMat2d(mat, trans, m0, m1, tr);
        vec2 localUv = vec2(
            m0.x * vPixel.x + m1.x * vPixel.y + tr.x,
            m0.y * vPixel.x + m1.y * vPixel.y + tr.y
        );

        // ── 精确命中判断 ──
        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        // ── 本地 uv → atlas uv ──
        vec2 atlasUv = uv0 + localUv * uTileScale;

        // ── 采样 ──
        vec4 c = sampleAtlas(aIdx, vec3(atlasUv, layer));

        // ── 预乘 ──
        c.rgb *= c.a;
        c     *= alpha;

        // ── 预乘 source-over ──
        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}