.PHONY: clean jar install

clean:
	clojure -T:build clean

jar:
	clojure -T:build jar

install: jar
	mvn install:install-file -Dfile=target/krro-plugin-painting-gl-renderer-0.1.0.jar -DpomFile=pom.xml