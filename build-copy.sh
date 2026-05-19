#!/bin/sh
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk build -x test --no-daemon
EXTENSION_ID='dontshavetheyak.groovy-guru-0.6.0'
cp build/libs/groovy-language-server-all.jar ~/.vscode/extensions/$EXTENSION_ID/bin/groovy-language-server-all.jar