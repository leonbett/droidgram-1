package org.droidmate.droidgram

import com.natpryce.konfig.CommandLineOption
import kotlinx.coroutines.runBlocking
import org.droidmate.api.ExplorationAPI
import org.droidmate.droidgram.fuzzer.Fuzzer
import org.droidmate.droidgram.runner.GrammarExploration
import org.droidmate.droidgram.runner.DefaultExploration
import org.droidmate.droidgram.mining.GrammarExtractor
import org.droidmate.droidgram.reporter.ResultBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

object ExperimentMain {
    @JvmStatic
    private val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }

    @JvmStatic
    private fun extraCmdOptions() = arrayOf(
        CommandLineOption(
            CommandLineConfig.inputDir,
            description = "Path to a directory with all inputs files to be used.",
            short = "i",
            metavar = "Path"
        ),
        CommandLineOption(
            CommandLineConfig.seedNr,
            description = "Number of the seed to be processed.",
            short = "s",
            metavar = "Int"
        ),
        CommandLineOption(
            CommandLineConfig.inputFilePrefix,
            description = "Prefix for the input file name.",
            short = "f",
            metavar = "String"
        )
    )

    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            // Grammar coverage relative to current input
            // Code coverage relative to the overall grammar
            // Grammar coverage relative to the overall grammar
            // Code coverage relative to the overall grammar
            when {
                args.contains("result") -> {
                    ResultBuilder.main(args.filterNot { it.contains("result") }.toTypedArray())
                    exitProcess(0)
                }
                args.contains("extract") -> {
                    val extractorArgs = args.filterNot { it.equals("extract") }.toTypedArray()
                    val grammar = GrammarExtractor.extract(extractorArgs, true)
                    Fuzzer(grammar, extractorArgs)
                        .apply { fuzzAllSeeds() }
                    exitProcess(0)
                }
                args.contains("merge") -> {
                    val extractorArgs = args.filterNot { it.equals("merge") }.toTypedArray()
                    val grammar = GrammarExtractor.merge(extractorArgs, true)
                    Fuzzer(grammar, extractorArgs)
                        .apply { fuzzAllSeeds() }
                    exitProcess(0)
                }
                args.contains("loc") -> {
                    val extractorArgs = args.filterNot { it.contains("loc") }.toTypedArray()
                    val grammar = GrammarExtractor.extract(extractorArgs, true)
                    Fuzzer(grammar, extractorArgs)
                        .apply { fuzzRandomSymbolsGrammar() }
                    exitProcess(0)
                }
                args.contains("explore") -> {
                    DefaultExploration.explore(
                        args
                            .filterNot {
                                it.contains("explore") }.toTypedArray()
                    )
                    exitProcess(0)
                }
            }

            val mainCfg = ExplorationAPI.config(args, *extraCmdOptions())

            val data = InputConfig(mainCfg)

            log.info("Reading inputs from: ${data.inputDir}")

            val seedList = data.inputs

            seedList.forEachIndexed { seed, inputs ->
                val seedDir = if (data.seedNr == -1) {
                    mainCfg.droidmateOutputDirPath.resolve("seed$seed")
                } else {
                    mainCfg.droidmateOutputDirPath.resolve("seed${data.seedNr}")
                }
                val seedArgs = arrayOf(*args, "--Output-outputDir=$seedDir")
                val seedCfg = ExplorationAPI.config(seedArgs, *extraCmdOptions())

                inputs.forEachIndexed { index, input ->
                    val experimentDir = seedCfg.droidmateOutputDirPath.resolve("input$index")
                    val experimentArgs = arrayOf(*args,
                        "--UiAutomatorServer-imgQuality=10",
                        "--Output-outputDir=$experimentDir")

                    log.info("Exploring input: $input (idx $seed)")

                    log.info("TRANSLATIONTABLE: " + data.translationTable)

                    val experimentCfg = ExplorationAPI.config(experimentArgs, *extraCmdOptions())
                    GrammarExploration.exploreWithGrammarInput(experimentCfg, input, data.translationTable)

                    // Grammar coverage relative to current input
                    ResultBuilder.generateGrammarCoverage(input, experimentCfg.droidmateOutputDirPath)
                    // Code coverage relative to the overall grammar
                    ResultBuilder.generateCodeCoverage(data.coverage, experimentCfg.droidmateOutputDirPath)
                }

                ResultBuilder.generateInputSize(inputs, seedCfg.droidmateOutputDirPath)
                // Grammar coverage relative to the overall grammar
                ResultBuilder.generateGrammarCoverage(inputs, seedCfg.droidmateOutputDirPath)
                // Code coverage relative to the overall grammar
                ResultBuilder.generateCodeCoverage(data.coverage, seedCfg.droidmateOutputDirPath)
            }

            ResultBuilder.generateSummary(data.inputs, data.coverage, mainCfg.droidmateOutputDirPath, data.seedNr)
        }
    }
}