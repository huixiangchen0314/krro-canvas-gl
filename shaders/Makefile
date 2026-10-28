CLJ ?= clj

# make 的默认目标；显式声明，避免依赖文件顺序
.DEFAULT_GOAL := compile

.PHONY: all compile clean

all: compile

compile: compile-composite-shaders

compile-composite-shaders:
	$(CLJ) -T:build compile-composite-shaders

clean:
	$(CLJ) -T:build clean