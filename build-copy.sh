#!/bin/sh
set -e
./gradlew build -x test
cp build/libs/groovy-language-server-all.jar ~/.vscode/extensions/publisher.groovy-0.0.0/bin/groovy-language-server-all.jar