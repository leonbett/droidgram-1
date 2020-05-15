#!/usr/bin/env bash

#SEED=2 #0
for SEED in {0..4} #2..5} # 0..0}
do
./gradlew run --args="-i /home/leon/HiWi/droidgram/orig/fork/droidgram-1/neuerversuch -s $SEED -f inputs --Strategies-explore=false --Exploration-apksDir=apks/ --Output-outputDir=output/ --Exploration-launchActivityDelay=3000 --Exploration-widgetActionDelay=800 --Selectors-randomSeed=$SEED --Deploy-installApk=true --Deploy-uninstallApk=true --Selectors-pressBackProbability=0.00 --StatementCoverage-enableCoverage=true"
done

echo "Done"
