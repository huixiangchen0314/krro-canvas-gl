CLJ ?= clj

# make 的默认目标；显式声明，避免依赖文件顺序
.DEFAULT_GOAL := all

.PHONY: all compile clean install

all: clean compile install

compile-composite-shaders:
	$(CLJ) -T:build compile-composite-shaders

compile: compile-composite-shaders

install:
	$(CLJ) -T:build install

clean:
	$(CLJ) -T:build clean