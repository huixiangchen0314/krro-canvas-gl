(ns composite.composite
  "合成公式。

   所有公式的 src / dst 都是预乘 alpha 的 vec4——rgb 和 a 都已
   乘过 alpha。调用方负责预乘，公式只做混合。

   统一合成式（W3C Compositing）：
     co = (1 - da)·S + αs·αb·B(Cb, Cs) + (1 - sa)·D
     αo = sa + da·(1 - sa)
   其中 sa = src.a、da = dst.a、Cb = D/da、Cs = S/sa 为非预乘分量，
   B 为下列模式函数；各公式只改 B，over 部分由 compose 统一处理。

   ── 公式列表 ──────────────────────────────────────────

   ▸ normal        普通 / over
                   B(Cb, Cs) = Cs
                   result = S + D·(1 - sa)

   ▸ multiply      正片叠底
                   B = Cb · Cs

   ▸ screen        滤色
                   B = Cb + Cs - Cb·Cs

   ▸ darken        变暗
                   B = min(Cb, Cs)

   ▸ lighten       变亮
                   B = max(Cb, Cs)

   ▸ difference    差值
                   B = |Cb - Cs|

   ▸ exclusion     排除
                   B = Cb + Cs - 2·Cb·Cs

   ▸ overlay       叠加（以 backdrop 为准的 hard-light）
                   B = 2·Cb·Cs                  (Cb ≤ 0.5)
                       1 - 2·(1-Cb)·(1-Cs)     (Cb > 0.5)

   ▸ hard-light    强光（以 source 为准的 overlay）
                   B = 2·Cb·Cs                  (Cs ≤ 0.5)
                       1 - 2·(1-Cb)·(1-Cs)     (Cs > 0.5)

   ▸ soft-light    柔光
                   B = Cb - (1-2Cs)·Cb·(1-Cb)              (Cs ≤ 0.5)
                       Cb + (2Cs-1)·(D(Cb) - Cb)           (Cs > 0.5)
                   D(Cb) = ((16Cb - 12)·Cb + 4)·Cb         (Cb ≤ 0.25)
                           sqrt(Cb)                         (Cb > 0.25)

   ▸ color-dodge   颜色减淡
                   B = min(1, Cb / (1 - Cs))    (Cs < 1)
                       1                         (Cs ≥ 1)

   ▸ color-burn    颜色加深
                   B = 1 - min(1, (1 - Cb) / Cs)  (Cs > 0)
                       0                          (Cs = 0)

   ▸ linear-light  线性光
                   B = clamp(Cb + 2·Cs - 1, 0, 1)

   ▸ vivid-light   鲜艳光
                   B = color-burn(Cb, 2·Cs)         (Cs ≤ 0.5)
                       color-dodge(Cb, 2Cs - 1)     (Cs > 0.5)

   ▸ pin-light     点光
                   B = min(Cb, 2·Cs)         (Cs ≤ 0.5)
                       max(Cb, 2Cs - 1)      (Cs > 0.5)

   ▸ hard-mix      硬混合
                   B = (Cb + Cs ≥ 1) ? 1 : 0

   ▸ subtract      减去
                   B = clamp(Cb - Cs, 0, 1)

   ▸ divide        划分
                   B = clamp(Cb / Cs, 0, 1)    (Cs > 0)
                       1                       (Cs = 0)

   ▸ linear-burn   线性加深
                   B = clamp(Cb + Cs - 1, 0, 1)

   ▸ linear-dodge  线性减淡（加法）
                   B = clamp(Cb + Cs, 0, 1)

   注：dissolve 需要逐像素随机数，纯函数无法实现，本 ns 不提供。"
  )

(defn ^:inline bcast4
  "把标量 x 广播成 float4。"
  [x]
  (float4 x x x x))

(defn ^:inline compose
  [src dst mix]
  (let [sa (:w src)
        da (:w dst)]
    (float4
      (+ (+
           (* (:x src) (- 1.0 da))
           (:x mix))
         (* (:x dst) (- 1.0 sa)))
      (+ (+
           (* (:y src) (- 1.0 da)) (:y mix))
         (* (:y dst) (- 1.0 sa)))
      (+ (+
           (* (:z src) (- 1.0 da)) (:z mix))
         (* (:z dst) (- 1.0 sa)))
      (+ sa (* da (- 1.0 sa))))))

(defn ^:inline normal [src backdrop]
  (let [src-alpha (:w src) backdrop-alpha (:w backdrop)]
    (float4
      (+ (:x src)
         (* (:x backdrop) (- 1.0 src-alpha)))
      (+ (:y src)
         (* (:y backdrop) (- 1.0 src-alpha)))
      (+ (:z src)
         (* (:z backdrop) (- 1.0 src-alpha)))
      (+ src-alpha
         (* backdrop-alpha (- 1.0 src-alpha))))))

(defn ^:inline multiply [src dst]
  (compose src dst (* src dst)))

(defn ^:inline screen [src dst]
  (let [sa (:w src) da (:w dst)]
    (compose src dst (- (+ (* da src) (* sa dst)) (* src dst)))))

(defn ^:inline darken [src dst]
  (let [sa (:w src) da (:w dst)]
    (compose src dst (min (* sa dst) (* da src)))))

(defn ^:inline lighten [src dst]
  (let [sa (:w src) da (:w dst)]
    (compose src dst (max (* sa dst) (* da src)))))

(defn ^:inline difference [src dst]
  (let [sa (:w src) da (:w dst)]
    (compose src dst (abs (- (* sa dst) (* da src))))))

(defn ^:inline exclusion [src dst]
  (let [sa (:w src) da (:w dst)]
    (compose src dst (- (+ (* da src) (* sa dst)) (* 2.0 (* src dst))))))

(defn ^:inline overlay [src dst]
  (let [sa (:w src) da (:w dst)
        low  (* 2.0 (* src dst))
        high (- (bcast4 (* sa da))
                (* 2.0 (* (- (bcast4 sa) src) (- (bcast4 da) dst))))
        m    (step (bcast4 da) (* 2.0 dst))]      ; 2D ≥ da → high
    (compose src dst (lerp low high m))))

(defn ^:inline hard-light [src dst]
  (let [sa (:w src) da (:w dst)
        low  (* 2.0 (* src dst))
        high (- (bcast4 (* sa da))
                (* 2.0 (* (- (bcast4 sa) src) (- (bcast4 da) dst))))
        m    (step (bcast4 sa) (* 2.0 src))]      ; 2S ≥ sa → high
    (compose src dst (lerp low high m))))

(defn ^:inline soft-light [src dst]
  (let [sa  (:w src) da (:w dst) eps 1e-6
        cb  (/ dst (bcast4 (max da eps)))
        cs  (/ src (bcast4 (max sa eps)))
        one (bcast4 1.0)
        ;; 分支 1：B1 = Cb - (1-2Cs)·Cb·(1-Cb)
        b1  (- cb (* (* (- one (* 2.0 cs)) cb) (- one cb)))
        ;; 分支 2：B2 = Cb + (2Cs-1)·(D(Cb) - Cb)
        mcb (step (bcast4 0.25) cb)                    ; Cb ≥ 0.25 → 1
        dp  (* cb (+ (* 16.0 cb) (bcast4 -12.0)))      ; 16Cb² - 12Cb
        dp  (* cb (+ dp (bcast4 4.0)))                 ; 16Cb³ - 12Cb² + 4Cb
        dv  (+ (* (- one mcb) dp) (* mcb (sqrt cb)))
        b2  (+ cb (* (- (* 2.0 cs) one) (- dv cb)))
        mcs (step (bcast4 0.5) cs)                     ; Cs ≥ 0.5 → 1
        b   (lerp b1 b2 mcs)]
    (compose src dst (* (* sa da) b))))

(defn ^:inline color-dodge [src dst]
  (let [sa   (:w src) da (:w dst) eps 1e-6
        den  (- (bcast4 sa) src)                       ; sa - S
        den' (max den (bcast4 eps))
        low  (min (bcast4 (* sa da))
                  (/ (* (* sa sa) dst) den'))          ; min(sa·da, sa²·D/(sa-S))
        m    (step (bcast4 eps) den)]                  ; sa-S ≥ eps → low
    (compose src dst (lerp (bcast4 (* sa da)) low m))))

(defn ^:inline color-burn [src dst]
  (let [sa  (:w src) da (:w dst) eps 1e-6
        s'  (max src (bcast4 eps))
        low (min (bcast4 (* sa da))
                 (/ (* (* sa sa) (- (bcast4 da) dst)) s'))
        m   (step (bcast4 eps) src)]
    (compose src dst (lerp (bcast4 0.0)
                           (- (bcast4 (* sa da)) low)
                           m))))

(defn ^:inline linear-light [src dst]
  (let [sa  (:w src) da (:w dst)
        raw (- (+ (* sa dst) (* 2.0 (* da src)))
               (bcast4 (* sa da)))]
    (compose src dst (clamp raw (bcast4 0.0) (bcast4 (* sa da))))))

(defn ^:inline vivid-light [src dst]
  (let [sa  (:w src) da (:w dst) eps 1e-6
        cb  (/ dst (bcast4 (max da eps)))
        cs  (/ src (bcast4 (max sa eps)))
        one (bcast4 1.0)
        ;; 分支 1：color-burn(Cb, 2Cs)
        s1  (* 2.0 cs)
        b1  (- one (min one (/ (- one cb) (max s1 (bcast4 eps)))))
        ;; 分支 2：color-dodge(Cb, 2Cs - 1)
        s2  (- (* 2.0 cs) one)
        b2  (min one (/ cb (max (- one s2) (bcast4 eps))))
        m   (step (bcast4 0.5) cs)
        b   (lerp b1 b2 m)]
    (compose src dst (* (* sa da) b))))

(defn ^:inline pin-light [src dst]
  (let [sa (:w src) da (:w dst)
        cb-term  (* sa dst)                            ; sa·D
        cs1-term (* 2.0 (* da src))                    ; 2·da·S
        cs2-term (- cs1-term (bcast4 (* sa da)))       ; 2·da·S - sa·da
        low  (min cb-term cs1-term)
        high (max cb-term cs2-term)
        m    (step (bcast4 sa) (* 2.0 src))]           ; 2S ≥ sa → high
    (compose src dst (lerp low high m))))


(defn ^:inline hard-mix [src dst]
  (let [sa  (:w src) da (:w dst)
        thr (bcast4 (* sa da))
        sum (+ (* sa dst) (* da src))                  ; sa·D + da·S
        m   (step thr sum)]                            ; sum ≥ thr → 1
    (compose src dst (* m thr))))


(defn ^:inline subtract [src dst]
  (let [sa  (:w src) da (:w dst)
        raw (- (* sa dst) (* da src))]                 ; sa·D - da·S
    (compose src dst (clamp raw (bcast4 0.0) (bcast4 (* sa da))))))

(defn ^:inline divide [src dst]
  (let [sa  (:w src) da (:w dst) eps 1e-6
        s'  (max src (bcast4 eps))
        raw (/ (* (* sa sa) dst) s')                   ; sa²·D/S
        low (min (bcast4 (* sa da)) raw)
        m   (step (bcast4 eps) src)]
    (compose src dst (lerp (bcast4 (* sa da)) low m))))

(defn ^:inline linear-burn [src dst]
  (let [sa  (:w src) da (:w dst)
        raw (- (+ (* sa dst) (* da src)) (bcast4 (* sa da)))]
    (compose src dst (clamp raw (bcast4 0.0) (bcast4 (* sa da))))))

(defn ^:inline linear-dodge [src dst]
  (let [sa  (:w src) da (:w dst)
        raw (+ (* sa dst) (* da src))]
    (compose src dst (clamp raw (bcast4 0.0) (bcast4 (* sa da))))))

;; ── 附：dissolve 需要逐像素随机数，纯函数无法实现，略。