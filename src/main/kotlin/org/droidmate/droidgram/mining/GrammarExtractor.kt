package org.droidmate.droidgram.mining

import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.LaunchApp
import org.droidmate.deviceInterface.exploration.TextInsert
import org.droidmate.deviceInterface.exploration.isLaunchApp
import org.droidmate.deviceInterface.exploration.isPressBack
import org.droidmate.droidgram.grammar.Grammar
import org.droidmate.droidgram.grammar.MinedGrammar
import org.droidmate.droidgram.grammar.Symbol
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.NumberFormatException
import java.lang.StringBuilder
import java.nio.file.*
import java.util.function.BiPredicate
import kotlin.streams.toList

class GrammarExtractor(
    private val mModelDir: Path,
    private val mCoverageDir: Path?,
    private val translateNames: Boolean
) {
    private val mIdMapping = mutableMapOf<String, String>()
    private val mGrammar = MinedGrammar()

    private val mStatesDir by lazy {
        val statesDir = Files.find(
            mModelDir,
            5,
            BiPredicate { p, _ -> Files.isDirectory(p) && p.fileName.toString() == "states" },
            FileVisitOption.FOLLOW_LINKS
        )
            .findFirst()

        if (statesDir.isPresent) {
            statesDir.get()
        } else {
            throw IllegalArgumentException("Unable to find state directory in $mModelDir")
        }
    }

    private var mIsInitialized = false

    private val coveragePerAction by lazy {
        if (mCoverageDir == null) {
            emptyMap()
        } else {
            check(Files.exists(mCoverageDir)) { "Coverage dir $mCoverageDir does not exist" }
            check(Files.isDirectory(mCoverageDir)) { "Coverage dir $mCoverageDir is not a directory" }

            val coverageFiles = Files.list(mCoverageDir)
                .filter { it.fileName.toString().contains("-statements-") }
                .toList()

            coveragePerAction(coverageFiles)
        }
    }

    private fun actionCoverage(actionId: Int): Set<Long> {
        return coveragePerAction
            .getOrDefault(actionId, emptySet())
    }

    private fun String.getUUID(): String {
        return this.split("_").firstOrNull().orEmpty()
    }

    private fun String.getId(prefix: String): String {
        val uuid = this.getUUID()

        return if (uuid.isEmpty()) {
            ""
        } else {
            val newValue = if (!translateNames) {
                uuid
            } else {
                prefix + (mIdMapping.values.count { it.startsWith(prefix) }.toString().padStart(2, '0'))
            }

            mIdMapping.getOrPut(uuid) { newValue }
        }
    }

    private fun List<String>.removeQueues(): List<String> = this
        .asSequence()
        .filterNot { it.contains(";ActionQueue-START;") }
        .filterNot { it.contains(";ActionQueue-End;") }
        .filterNot { it.contains(";EnableWifi;") }
        // .filterNot { it.contains(";PressBack;") }
        // .filterNot { it.contains(";Terminate;") }
        .filterNot { it.contains(";CloseKeyboard;") }
        .filterNot { it.contains(";Back;") }
        .filterNot { it.contains(";FetchGUI;") }
        .filterNot { it.contains(";LongClickEvent;null;") }
        .toList()

    private fun getTraceFile(modelDir: Path): List<String> {
        val trace = Files.find(
            modelDir,
            5,
            BiPredicate { p, _ -> p.fileName.toString().contains("trace") && p.fileName.toString().endsWith(".csv") },
            FileVisitOption.FOLLOW_LINKS
        )
            .findFirst()

        if (!trace.isPresent) {
            throw FileNotFoundException("Unable to find trace file in $modelDir")
        } else {
            return Files.readAllLines(trace.get())
                .drop(1)
                .removeQueues()
        }
    }

    private fun String.isHomeScreen(): Boolean {
        return Files.exists(mStatesDir.resolve("${this}_HS.csv"))
    }

    private fun String.belongsToApp(): Boolean {
        if (this.isHomeScreen()) {
            return false
        }

        val stateFile = mStatesDir.resolve("$this.csv")
        check(Files.exists(stateFile)) { "File for state $this does not exist in $mStatesDir" }

        val apkName = mStatesDir.parent.fileName.toString()
        val itemsFromApp = Files.readAllLines(stateFile)
            .any { it.contains(apkName) }

        val permissionDialogs = Files.readAllLines(stateFile)
            .any { it.contains("com.android.packageinstaller:id") }

        return itemsFromApp || permissionDialogs
    }

    private fun createProduction(
        action: String,
        sourceStateConcreteId: String,
        resultStateConcreteId: String,
        widgetId: String,
        textualData: String,
        actionId: Int
    ) {
        val sourceStateUID = sourceStateConcreteId.getId("s")
        val sourceStateNonTerminal = "<$sourceStateUID>"

        val resultState = when {
            action.isLaunchApp() -> resultStateConcreteId.getId("s")
            resultStateConcreteId.belongsToApp() -> resultStateConcreteId.getId("s")
            else -> ""
        }

        val widgetUID = if (widgetId != "null") {
            widgetId.getId("w")
        } else {
            widgetId
        }

        val resultStateNonTerminal = if (resultState.isEmpty() || resultStateConcreteId.isHomeScreen()) {
            Symbol.epsilon.value
        } else {
            "<$resultState>"
        }

        check(resultStateNonTerminal != "<>") { "Invalid result state terminal" }

        when (action) {
            LaunchApp.name -> {
                // Launch app is a queue as follows: start queue, launch, enable wifi, close keyboard, end queue
                // Start needs +3 because it uses the action Queue ID which is +1[wifi] +1[keyboard +1[end queue].
                val coverage = actionCoverage(actionId + 3)
                mGrammar.addRule(Symbol.start, resultStateNonTerminal, coverage)
            }
            ActionType.PressBack.name -> {
                val terminal = "$action($sourceStateUID)"
                val nonTerminal = "<$action($sourceStateUID)>"
                val productionRule = arrayOf(terminal, nonTerminal)
                val coverage = actionCoverage(actionId)

                mGrammar.addRule(sourceStateNonTerminal, productionRule, coverage)
                mGrammar.addRule(nonTerminal, resultStateNonTerminal, emptySet())
            }
            ActionType.Terminate.name -> {
                val productionRule = "<$action($sourceStateUID)>"

                mGrammar.addRule(sourceStateNonTerminal, productionRule, emptySet())
                mGrammar.addRule(productionRule, Symbol.empty, emptySet())
            }
            else -> {
                val terminal = "$action($widgetUID$textualData)"
                val nonTerminal = "<$action($sourceStateUID.$widgetUID$textualData)>"
                val productionRule = arrayOf(terminal, nonTerminal)
                val coverage = actionCoverage(actionId)

                mGrammar.addRule(sourceStateNonTerminal, productionRule, coverage)
                mGrammar.addRule(nonTerminal, resultStateNonTerminal, emptySet())
            }
        }
    }

    private fun extractGrammar(): Grammar {
        check(!mIsInitialized) { "Grammar cannot be re-generated in the same instance" }
        mIsInitialized = true

        val trace = getTraceFile(mModelDir)

        var previousSourceStateConcreteId = ""

        trace.forEachIndexed { _, entry ->
            val data = entry.split(";")
            val sourceStateConcreteId = data[0]
            val action = data[1]

            val widgetId = data[2]
            val resultStateConcreteId = data[3]

            val payload = when (action) {
                // TextInsert.name -> ",${data.dropLast(1).last()}" // This is "Action-Id", which we would pass twice in this case to createProduction.
                TextInsert.name -> ",${data.dropLast(2).last()}" // This is "Data"

                "Swipe" -> ",${data.dropLast(1).last()
                    .replace(",", ";")
                    .replace(" TO ", "TO")}"

                else -> ""
            }

            // There are 2 versions of the model, one which ends with the action ID, other which ends
            // with a boolean for successful screenshot
            val actionId = try {
                data.last().toInt()
            } catch (e: NumberFormatException) {
                data.dropLast(1).last().toInt()
            }

            // Create only if action was in the app or is launch/back
            if (action.isLaunchApp() || action.isPressBack() || sourceStateConcreteId.belongsToApp()) {
                // Pressed back after a start.
                if (action.isPressBack() && previousSourceStateConcreteId == "") {
                    createProduction(LaunchApp.name, "", sourceStateConcreteId, "", "", actionId)
                }

                // If state was already used on the right side of an expression, it can be used.
                // Otherwise append to previous
                val sourceId = if (mIdMapping.containsKey(sourceStateConcreteId.getUUID())) {
                    sourceStateConcreteId
                } else {
                    previousSourceStateConcreteId
                }

                check(action.isLaunchApp() || sourceId.isNotEmpty()) {
                    "No source id identified for entry $entry"
                }

                createProduction(action, sourceId, resultStateConcreteId, widgetId, payload, actionId)
                previousSourceStateConcreteId = sourceId
            } else {
                log.warn("State $sourceStateConcreteId does not belong to the app. Ignoring it")
            }
        }

        mGrammar.cleanUp(mCoverageDir == null)

        check(mGrammar.isValid()) {
            "Grammar is invalid"
        }

        return mGrammar
    }

    val grammar by lazy {
        check(mIsInitialized) { "Grammar has not been initialized" }
        mGrammar
    }

    val mapping by lazy {
        check(mIsInitialized) { "Grammar has not been initialized" }

        mIdMapping
            .map { Pair(it.value, it.key) }
            .toMap()
    }

    private fun write(useCoverage: Boolean, outputDir: Path, printToConsole: Boolean) {
        val fileNameSuffix = if (useCoverage) {
            "WithCoverage"
        } else {
            ""
        }

        val grammarJSON = this.grammar.asJsonStr(useCoverage)
        val grammarFile = outputDir.resolve("grammar$fileNameSuffix.txt")
        Files.write(grammarFile, grammarJSON.toByteArray())

        val mapping = StringBuilder()
        this.mapping
            .toSortedMap()
            .forEach { (key, value) ->
                mapping.appendln("$key;$value")
            }

        val mappingFile = outputDir.resolve("translationTable$fileNameSuffix.txt")
        Files.write(mappingFile, mapping.toString().toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        if (printToConsole) {
            println("Grammar:")
            val grammarStr = this.grammar.asString(useCoverage)
            print(grammarStr)

            println("\nMapping: $mappingFile")
            print(mapping.toString())
        }
    }

    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }

        @JvmStatic
        private fun extractGrammar(
            inputDir: Path,
            outputDir: Path,
            coverageDir: Path,
            printToConsole: Boolean,
            translateNames: Boolean = false
        ): Grammar {
            val extractor = GrammarExtractor(inputDir, coverageDir, translateNames)
            val grammar = extractor.extractGrammar()

            extractor.write(false, outputDir, printToConsole)
            extractor.write(true, outputDir, printToConsole)

            return grammar
        }

        @JvmStatic
        fun merge(args: Array<String>, printToConsole: Boolean): Grammar {
            val baseDir = Paths.get(args.getOrNull(0) ?: throw IOException("Missing input dir path"))
                .toAbsolutePath()

            val outputDir = Paths.get(args.getOrNull(1) ?: throw IOException("Missing output dir path"))
                .toAbsolutePath()

            check(Files.isDirectory(baseDir)) { "Input path is not a directory" }

            val inputDirs = Files.list(baseDir)
                .filter { Files.isDirectory(it) }
                .toList()

            check(inputDirs.isNotEmpty()) { "Input path does not contain any directory" }

            val minedGrammars = inputDirs.map { inputDir ->
                val coverageDir = inputDir.resolve("coverage")
                check(Files.exists(coverageDir)) { "Coverage directory $coverageDir not found" }
                extract(inputDir, outputDir, coverageDir, printToConsole)
            }

            return GrammarMerger(outputDir, printToConsole).merge(minedGrammars)
        }

        @JvmStatic
        fun extract(inputDir: Path, outputDir: Path, coverageDir: Path, printToConsole: Boolean): Grammar {
            return extractGrammar(inputDir, outputDir, coverageDir, printToConsole)
        }

        @JvmStatic
        fun extract(args: Array<String>, printToConsole: Boolean): Grammar {
            check(args.isNotEmpty()) { "Missing model dir argument" }
            val inputDir = (
                    Files.list(Paths.get(args.first()))
                        .toList()
                        .sorted()
                        .firstOrNull { Files.isDirectory(it) } ?: throw IOException("Missing model dir path")
                    ).toAbsolutePath()

            val outputDir = Paths.get(args.getOrNull(1) ?: throw IOException("Missing output dir path"))
                .toAbsolutePath()

            val coverageDir = inputDir.parent.resolveSibling("coverage")
            check(Files.exists(coverageDir)) { "Coverage directory $coverageDir not found" }

            return extract(inputDir, outputDir, coverageDir, printToConsole)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            extract(args, true)
        }
    }
}
