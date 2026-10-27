(ns composite.tile-fold
  "tile 序列的折叠骨架。

   把\"遍历 tile 列表\"和\"处理单个 tile\"解耦：
   ▸ fold-tiles 负责循环骨架（索引递增、终止判断、tile-idx 加载）
   ▸ f 负责 per-tile 逻辑（拿到 tile-idx 和 acc，返回新 acc）

   ^:inline 让前端展开时把 f 的 body 内联到 fold-tiles 的 loop 内部，
   展开后与手写 loop 逐 token 等价。

   ── 循环控制约定 ────────────────────────────────────
   recur 只在 fold-tiles 内部。闭包 f 不含任何循环控制：
     - 返回 acc    → 当前 tile 被跳过（累积不变）
     - 返回 acc'   → 当前 tile 被累积（acc' 是新累加器）

   闭包纯粹化让内联器的责任缩小为\"表达式替换\"，不需要处理
   嵌套 recur 或 recur 目标消歧。"
  (:require
    [top.kzre.homunculus.backend.shader.dsl :refer :all]))

(defn ^:inline fold-tiles
  "遍历 tile 列表，对每个 tile 应用 f，累积结果。

   uIndexTable —— tile 索引 buffer（samplerBuffer）
   vOffset     —— tile 起始索引（int）
   vCount      —— tile 数量（int）
   f           —— (fn [tile-offset color-acc] → color-acc')
"
  [uIndexTable vOffset vCount f]
  (loop [i   0
         color-acc  (float4 0.0 0.0 0.0 0.0)]
    (if (>= i vCount)
      color-acc
      (let [^int tile-offset (:x (uload uIndexTable (+ vOffset i)))]
        (recur (+ i 1)
               (f tile-offset color-acc))))))