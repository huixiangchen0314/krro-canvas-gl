#version 330 core

// ═══════════════════════════════════════════════
// 从 vertex 接收
// ═══════════════════════════════════════════════
in vec2 vPixel;
flat in int vOffset;
flat in int vCount;

// ═══════════════════════════════════════════════
// 瓦片表：samplerBuffer，每条目 3 个 vec4
// ═══════════════════════════════════════════════
uniform samplerBuffer uTileTable;

// ═══════════════════════════════════════════════
// 索引表：usamplerBuffer，扁平 int
// ═══════════════════════════════════════════════
uniform usamplerBuffer uIndexTable;

// ═══════════════════════════════════════════════
// 各 atlas 纹理数组
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

// ── 按 unit 选 atlas ─────────────────────────────
vec4 sampleAtlas(int unit, vec3 uv) {
    if (unit == 0) return texture(uAtlas0, uv);
    if (unit == 1) return texture(uAtlas1, uv);
    if (unit == 2) return texture(uAtlas2, uv);
    return texture(uAtlas3, uv);
}

// ── 2D 仿射求逆 ──────────────────────────────────
// 输入 [a b c d tx ty]，返回 [A B C D TX TY] 使得
//   [A C TX]   [a c tx]⁻¹
//   [B D TY] = [b d ty]
mat3x2 invertMat2d(vec4 m, vec2 t) {
    float det = m.x * m.w - m.y * m.z;
    float invDet = 1.0 / det;

    float A =  m.w * invDet;
    float B = -m.y * invDet;
    float C = -m.z * invDet;
    float D =  m.x * invDet;
    float TX = -(A * t.x + C * t.y);
    float TY = -(B * t.x + D * t.y);

    return mat3x2(A, B, C, D, TX, TY);
}

void main() {
    // ── 1. 读本实例覆盖的图层瓦片 ──
    vec4 acc = vec4(0.0);

    for (int i = 0; i < vCount; i++) {
        // 从索引表读出瓦片表条目下标
        uint entryIdx = texelFetch(uIndexTable, vOffset + i).r;

        // 从瓦片表读 3 个 vec4
        vec4 e0 = texelFetch(uTileTable, int(entryIdx) * 3 + 0);
        vec4 e1 = texelFetch(uTileTable, int(entryIdx) * 3 + 1);
        vec4 e2 = texelFetch(uTileTable, int(entryIdx) * 3 + 2);

        // ── 2. 解包字段 ──
        // e0 = [a, b, c, d]              —— 图层正向变换
        // e1 = [tx, ty, u0, v0]          —— 平移 + atlas uv 起点
        // e2 = [unit, layer, alpha, pad] —— 纹理单元、层、透明度

        vec4 matPart = e0;
        vec2 trans   = e1.xy;
        vec2 uv0     = e1.zw;
        int  unit    = int(e2.x + 0.5);
        float layer  = e2.y;
        float alpha  = e2.z;

        // ── 3. 视口坐标 → 瓦片本地 uv [0,1] ──
        mat3x2 inv = invertMat2d(matPart, trans);
        vec2 localUv = inv * vPixel + vec2(0.0);
        // 注：mat3x2 * vec2 只取前两列和第三列平移
        // 更明确写法：
        // vec2 localUv = vec2(
        //     inv[0].x * vPixel.x + inv[0].y * vPixel.y + inv[0].z, ...

        // ── 4. 精确命中判断 ──
        if (any(lessThan(localUv, vec2(0.0))) ||
            any(greaterThan(localUv, vec2(1.0)))) {
            continue;
        }

        // ── 5. 本地 uv → atlas uv ──
        vec2 atlasUv = uv0 + localUv * uTileScale;

        // ── 6. 采样 atlas ──
        vec4 c = sampleAtlas(unit, vec3(atlasUv, layer));

        // ── 7. 采样后立即预乘 ──
        c.rgb *= c.a;
        c     *= alpha;

        // ── 8. 预乘 source-over 叠加 ──
        acc = c + acc * (1.0 - c.a);
    }

    fragColor = acc;
}