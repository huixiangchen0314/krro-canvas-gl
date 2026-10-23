# build/build_templates.rb
#
# 模板：把多个 cljh 项目共享的编译参数抽出来。
# 模板只注册，不生成 Rake 任务，也不校验 entries / output 是否齐全。

require_relative "lib/build_dsl"

# 公共基础：所有目标都以 src 作为库搜索路径；
# output 延迟到项目实例化时按项目名展开为 out/<name>。
cljh_template :common do
  lib_paths "src"
  configs   "conf/common.edn"
  output    { "out/#{name}" }
  # 等价写法：output "out/:name"
end

# 合成 shader 一套模板
cljh_template :composite, template: :common do
  configs "conf/composite.edn"
end

cljh_template :composite_one16, template: :composite do
  macro_ns "composite.compo-one16-helper"
end