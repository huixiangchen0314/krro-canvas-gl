# build/build_tasks.rb
#
# 项目实例：按模板实例化，只写差异（通常只有 entries）。

require_relative "lib/build_dsl"

# 20 个标准合成公式(除了dissolve 以外)
%w[normal16 multiply16 screen16 darken16 soft_light16 lighten16 difference16
   exclusion16 overlay16 hard_light16 soft_light16
   color_dodge16 color_burn16 linear_light16 vivid_light16
   pin_light16 hard_mix16 subtract16 divide16 linear_burn16 linear_dodge16].each do |name|
  cljh_project name, template: :composite_one16 do
    entries "src/composite/#{name}.clj"
  end
end