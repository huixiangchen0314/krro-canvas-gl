.PHONY: clean compile test jar uberjar repl


all: clean compile-shaders compile-java jar install

compile-shaders:
	 cd shaders && make

clean:
	cd shaders && make clean
	clj -T:build clean

compile-java:
	clj -T:build compile-java

jar:
	clj -T:build jar

install:
	clj -T:build install
