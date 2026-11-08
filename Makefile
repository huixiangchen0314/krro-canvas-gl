.PHONY: clean compile test jar uberjar repl


all: clean compile-shaders install

compile-shaders:
	 cd shaders && make

clean:
	cd shaders && make clean
	clj -T:build clean

jar:
	clj -T:build jar

install:
	clj -T:build install
