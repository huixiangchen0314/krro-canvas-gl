(ns composite.comp-one16
  "单组合成的纹理包变种——16 个纹理单元槽位：
   ▸ t0-t2   查找表（3 个 buffer）
   ▸ t3-t15  瓦片 atlas（13 个 sampler2DArray）

   公开接口（变种间约定）：
   ▸ uIndexTable / uTileTable / uLayerTable
   ▸ uTexture0..12 / uAtlasSampler / uTextureScales
   ▸ sample-atlas

   buffer 布局和 layer-transform 是通用约定——见
   composite.tile-layout。变种只改纹理槽位和采样分派。"
  (:require
    [top.kzre.homunculus.backend.shader.dsl :refer :all]))

;; ═══════════════════════════════════════════
;; 查找表 —— t0-t2
;; ═══════════════════════════════════════════

(deftexture uIndexTable :t0 :buffer uint4)
(deftexture uTileTable  :t1 :buffer float4)
(deftexture uLayerTable :t2 :buffer float4)

;; ═══════════════════════════════════════════
;; 瓦片 atlas —— t3-t15，13 个
;; ═══════════════════════════════════════════

(deftexture uTexture0  :t3  :2d-array float4)
(deftexture uTexture1  :t4  :2d-array float4)
(deftexture uTexture2  :t5  :2d-array float4)
(deftexture uTexture3  :t6  :2d-array float4)
(deftexture uTexture4  :t7  :2d-array float4)
(deftexture uTexture5  :t8  :2d-array float4)
(deftexture uTexture6  :t9  :2d-array float4)
(deftexture uTexture7  :t10 :2d-array float4)
(deftexture uTexture8  :t11 :2d-array float4)
(deftexture uTexture9  :t12 :2d-array float4)
(deftexture uTexture10 :t13 :2d-array float4)
(deftexture uTexture11 :t14 :2d-array float4)
(deftexture uTexture12 :t15 :2d-array float4)

;; ── 共用采样器 ──
(defsampler uAtlasSampler :s0)

;; ── 每个 atlas 的 (1/edgeU, 1/edgeV)——索引对齐 unit ──
(defuniform uTextureScales float2 13)

;; ═══════════════════════════════════════════
;; 采样分派 —— 13 路
;; ═══════════════════════════════════════════

(defn ^:inline sample-atlas
  "运行时 unit 索引 → 13 个 sampler2DArray 的分派。

   GLSL 330 不允许 sampler 数组的动态索引（4.0+ 才放开），
   13 路 if-else 是必须的。unit 越界返回透明黑。"
  [^int unit ^float3 uv]
  (if (= unit 0)
    (sample uTexture0  uAtlasSampler uv)
    (if (= unit 1)
      (sample uTexture1  uAtlasSampler uv)
      (if (= unit 2)
        (sample uTexture2  uAtlasSampler uv)
        (if (= unit 3)
          (sample uTexture3  uAtlasSampler uv)
          (if (= unit 4)
            (sample uTexture4  uAtlasSampler uv)
            (if (= unit 5)
              (sample uTexture5  uAtlasSampler uv)
              (if (= unit 6)
                (sample uTexture6  uAtlasSampler uv)
                (if (= unit 7)
                  (sample uTexture7  uAtlasSampler uv)
                  (if (= unit 8)
                    (sample uTexture8  uAtlasSampler uv)
                    (if (= unit 9)
                      (sample uTexture9  uAtlasSampler uv)
                      (if (= unit 10)
                        (sample uTexture10 uAtlasSampler uv)
                        (if (= unit 11)
                          (sample uTexture11 uAtlasSampler uv)
                          (if (= unit 12)
                            (sample uTexture12 uAtlasSampler uv)
                            (float4 0.0 0.0 0.0 0.0)))))))))))))))