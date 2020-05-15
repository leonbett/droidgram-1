#!/usr/bin/env bash

OUTPUT_DIR=./out
INPUT_DIR=./input
APKS_DIR=apks/
# NR_SEEDS=1
ACTION_LIMIT=500
RESET_EVERY=50

#i=111 # same seed, 5 times
for i in {1..5} #111..111} #3..5} #1..1} #3..3}
do
    echo "next iteration: $i"
    ./gradlew run --args="explore --Exploration-apksDir=${APKS_DIR} --Exploration-launchActivityDelay=3000 --Exploration-widgetActionDelay=800 --Selectors-actionLimit=${ACTION_LIMIT} --Selectors-resetEvery=${RESET_EVERY} --Deploy-installApk=true --Deploy-uninstallApk=true --Selectors-pressBackProbability=0.05 --StatementCoverage-enableCoverage=true --Selectors-randomSeed=$i --Output-outputDir=merge-input-dir/seed$i"
done

echo "Done"
