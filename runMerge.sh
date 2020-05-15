#!/usr/bin/env bash

echo "Now merging grammars"
./gradlew run --args="merge neuerversuch/ neuerversuch/" #merge-input-dir/ temptest2" #merge-input-dir/"
