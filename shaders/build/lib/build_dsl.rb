# build/lib/build_dsl.rb
#
# cljh 构建 DSL。
# 一个 cljh_project 由以下部分组成：
#   - 若干入口文件        (entries)
#   - 若干宏命名空间      (macro_namespaces)
#   - 若干库搜索路径      (lib_paths, 每个生成 -L <path>)
#   - 若干配置文件        (configs, 用 ; 连接后传给 --config)
#   - 一个输出目录        (output)
# 最终调用 cljhc 生成产物，并注册成 Rake 任务。
#
# 所有访问器都是 "无参读取 / 有参追加 / 带 ! 覆盖" 的形式。
# output 支持延迟求值：output { "out/#{name}" } 或 output "out/:name"，
# 在项目实例化时才展开，name 是最终项目名。

require "rake/clean"

module BuildDSL
  class << self
    attr_writer :root

    def root
      @root ||=
        if Rake.application.rakefile
          File.dirname(File.expand_path(Rake.application.rakefile))
        else
          Rake.application.original_dir
        end
    end

    def resolve(path)
      File.expand_path(path, root)
    end
  end

  class Project
    attr_reader :name

    def initialize(name, defaults: nil, &blk)
      @name = name
      if defaults
        @entries          = defaults.entries.dup
        @macro_namespaces = defaults.macro_namespaces.dup
        @lib_paths        = defaults.lib_paths.dup
        @configs          = defaults.configs.dup
        @output           = defaults.raw_output
      else
        @entries          = []
        @macro_namespaces = []
        @lib_paths        = []
        @configs          = []
        @output           = nil
      end
      instance_eval(&blk) if blk
    end

    # ---- entries ----
    def entry(*paths)
      return @entries if paths.empty?
      @entries.concat(resolve_all(paths))
    end
    alias entries entry
    def entry!(*paths) ; @entries = resolve_all(paths) ; end
    alias entries! entry!

    # ---- macro namespaces ----
    def macro_ns(*names)
      return @macro_namespaces if names.empty?
      @macro_namespaces.concat(names.map(&:to_s))
    end
    alias macro_namespaces macro_ns
    def macro_ns!(*names) ; @macro_namespaces = names.map(&:to_s) ; end
    alias macro_namespaces! macro_ns!

    # ---- lib paths（每个生成一对 -L <path>） ----
    def lib_path(*paths)
      return @lib_paths if paths.empty?
      @lib_paths.concat(resolve_all(paths))
    end
    alias lib_paths lib_path
    def lib_path!(*paths) ; @lib_paths = resolve_all(paths) ; end
    alias lib_paths! lib_path!

    # ---- configs ----
    def config(*paths)
      return @configs if paths.empty?
      @configs.concat(resolve_all(paths))
    end
    alias configs config
    def config!(*paths) ; @configs = resolve_all(paths) ; end
    alias configs! config!

    # ---- output ----
    # 写法：
    #   output "out/foo"           直接字符串
    #   output "out/:name"         占位符，项目实例化时替换为项目名
    #   output { "out/#{name}" }   block，项目实例化时求值（self 为项目）
    #   output                     无参读取，返回展开后的绝对路径
    def output(path = nil, &blk)
      if blk
        @output = blk
        self
      elsif path.nil?
        resolved_output
      else
        @output = path
        self
      end
    end

    # 展开后的绝对路径（项目实例化后才确定）。
    def resolved_output
      v = @output
      return nil if v.nil?
      v = instance_exec(&v) if v.respond_to?(:call)
      return nil if v.nil?
      v = v.to_s.gsub(":name", name.to_s)
      BuildDSL.resolve(v)
    end

    def task_name
      @task_name ||= "compile:#{@name}"
    end

    def compile_args
      args = ["cljhc", "--target", "glsl"]
      @lib_paths.each { |p| args.push("-L", p) }
      args.push("--macro-ns", @macro_namespaces.join(";")) unless @macro_namespaces.empty?
      out = resolved_output
      args.push("--output", out) if out
      args.concat(@entries)
      args.push("--config", @configs.join(";")) unless @configs.empty?
      args
    end

    protected

    # 原始值（可能是 String 或 Proc），供模板/项目继承时使用。
    def raw_output
      @output
    end

    private

    def resolve_all(paths)
      paths.map { |p| BuildDSL.resolve(p) }
    end
  end

  module RakeHelpers
    include Rake::DSL

    def cljh_projects  ; @cljh_projects  ||= [] ; end
    def cljh_templates ; @cljh_templates ||= {} ; end

    def cljh_defaults(&blk)
      @cljh_defaults ||= Project.new("__defaults__")
      @cljh_defaults.instance_eval(&blk) if blk
      @cljh_defaults
    end

    def cljh_template(name, template: nil, &blk)
      base =
        if template
          cljh_templates.fetch(template.to_sym) {
            raise ArgumentError, "未知模板: #{template.inspect}"
          }
        else
          cljh_defaults
        end

      tpl = Project.new("__template__:#{name}", defaults: base, &blk)
      cljh_templates[name.to_sym] = tpl
      tpl
    end

    def cljh_project(name, template: nil, inherit: true, &blk)
      base =
        if template
          cljh_templates.fetch(template.to_sym) {
            raise ArgumentError, "未知模板: #{template.inspect}"
          }
        elsif inherit
          cljh_defaults
        end

      proj = Project.new(name, defaults: base, &blk)

      raise ArgumentError, "cljh_project(#{name}): 至少需要一个 entry" if proj.entries.empty?
      raise ArgumentError, "cljh_project(#{name}): 缺少 output"       if proj.resolved_output.nil?

      cljh_projects << proj
      CLOBBER.include(proj.resolved_output)

      desc "编译 cljh 项目: #{name}"
      task proj.task_name do
        puts ">> #{proj.compile_args.join(" ")}" if ENV["CLJH_DEBUG"]
        sh(*proj.compile_args)
      end

      task :compile => proj.task_name
      proj
    end
  end
end

extend BuildDSL::RakeHelpers