package org.droidmate.droidgram.reporter

import org.droidmate.droidgram.grammar.reachedTerminals
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

object ResultBuilder {
    @JvmStatic
    private val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }
    private const val nrSeeds = 11

    /**
     * Pattern: inputs00.txt, inputs01.txt, ..., inputs10.txt
     */
    private fun getFilesInputs(inputDir: Path): List<Path> {
        return Files.walk(inputDir)
            .filter { it.fileName.toString().startsWith("inputs") }
            .filter { it.fileName.toString().endsWith(".txt") }
            .filter { it.fileName.toString().length == 12 }
            .toList()
    }

    /**
     * Pattern: reachedTerminals.txt
     */
    private fun getFilesReachedWidgets(explorationResultDir: Path): List<Path> {
        return Files.walk(explorationResultDir)
            .filter { it.fileName.toString() == reachedTerminals }
            .toList()
    }

    /**
     * Pattern *\coverage\*-statements-*
     */
    private fun getFilesReachedStatements(explorationResultDir: Path): List<Path> {
        return Files.walk(explorationResultDir)
            .filter { it.toAbsolutePath().toString().contains("/coverage/") }
            .filter { it.fileName.toString().contains("-statements-") }
            .toList()
    }

    private fun getReachedTerminals(baseDir: Path): Set<String> {
        return getFilesReachedWidgets(baseDir)
            .flatMap { Files.readAllLines(it) }
            .filter { it.isNotEmpty() }
            .map { stmt ->
                val res = stmt.substringBefore(");").trim() + ")"

                check(res.contains('(')) { "Invalid reached statement $stmt" }
                check(res.endsWith(')')) { "Invalid reached statement $stmt" }

                res
            }.toSet()
    }

    private fun getReachedStatements(baseDir: Path): Set<Long> {
        val files = getFilesReachedStatements(baseDir)
        val fileCount = files.size
        val coverages = files.mapIndexed { index, file ->
            val lineSet = mutableSetOf<Long>()
            log.debug("Processing file $file ($index/$fileCount)")

            if (index % 100 == 0) {
                log.info("Processing file $file ($index/$fileCount)")
            }

            Files.newBufferedReader(file).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotEmpty()) {
                        val id = line.takeWhile { it != ';' }.toLong()
                        lineSet.add(id)
                    }
                }
            }
            lineSet
        }

        return if (coverages.isNotEmpty())
            coverages.reduce { acc, mutableSet ->
                acc.addAll(mutableSet)
                acc
            } else {
            emptySet()
        }
    }

    private fun getCodeCoverage(allStatements: Set<Long>, dir: Path): Result<Long> {
        return calculateCodeCoverage(allStatements, dir)
    }

    fun generateCodeCoverage(allStatements: Set<Long>, dir: Path) {
        val coverage = getCodeCoverage(allStatements, dir)
        val coverageFile = dir.resolve("codeCoverage.txt")

        coverage.save(coverageFile)
    }

    private fun calculateCodeCoverage(allStatements: Set<Long>, dir: Path): Result<Long> {
        val reached = getReachedStatements(dir)
        val missing = allStatements - reached


        val res = Result(allStatements.toList(), reached, missing)

        localCheck(res.coverage in 0.0..1.0) { "Expected code coverage between 0 and 1. Found ${res.coverage}" }

        return res
    }

    fun generateGrammarCoverage(input: String, dir: Path) {
        generateGrammarCoverage(listOf(input), dir)
    }

    private fun getGrammarCoverage(allTerminals: List<String>, dir: Path): Result<String> {
        return calculateGrammarCoverage(allTerminals, dir)
    }

    fun generateGrammarCoverage(allTerminals: List<String>, dir: Path) {
        val coverage = getGrammarCoverage(allTerminals, dir)
        val coverageFile = dir.resolve("grammarCoverage.txt")

        coverage.save(coverageFile)
    }

    private fun getInputSize(allTerminals: List<String>): List<Int> {
        return allTerminals.map { input ->
            val inputs = input
                .split(" ")
                .filter { it.isNotEmpty() }

            inputs.size
        }
    }

    fun generateInputSize(allTerminals: List<String>, dir: Path) {
        val inputSize = getInputSize(allTerminals)
        val totalSize = inputSize.sum()

        val sb = StringBuilder()
        sb.appendln("Total input size: $totalSize")

        inputSize.forEachIndexed { idx, size ->
            sb.appendln("$idx\t$size")
        }

        val inputSizeFile = dir.resolve("inputSize.txt")
        Files.write(inputSizeFile, sb.toString().toByteArray())
    }

    // @Throws(IOException::class)
    // val instrFile = Paths.get("/home/leon/HiWi/droidgram/gut/droidgram/apks/com.carlo_2.3.1.apk.json")
    // readInstrumentationFile(instrFile)
    fun readInstrumentationFile(instrumentationFile: Path?): Map<Long, String> {
        val jsonData = String(Files.readAllBytes(instrumentationFile))
        val jObj = JSONObject(jsonData)

        val jMap = jObj.getJSONObject("allMethods")
        val statements = mutableMapOf<Long, String>()

        jMap.keys()
            .asSequence()
            .forEach { key ->
                val keyId = key.toLong()
                val value = jMap[key]
                statements[keyId] = value.toString()
            }

        return statements
    }


    fun generateSummary(inputs: List<List<String>>, allStatements: Set<Long>, dir: Path, seedNr: Int) {
        val sb = StringBuilder()
        // sb.appendln("Seed\tInput Size\tGrammarReached\tGrammarMissed\tGrammarCov\tCodeReached\tCodeMissed\tCodeCov\tNewCodeReached")
        sb.appendln("Seed\tInput Size\tGrammarReached\tGrammarMissed\tGrammarCov\tCodeReached\tCodeMissed\tCodeCov\tNewCodeReached\tTotalCov")


        // TODO: get all statements in program
        val allTerminals = inputs.flatten()
        var totalCodeCoverageOfAllSeeds: Set<Long> = emptySet()
        var totalNew: Set<Long> = emptySet()
        // var totalReached: Set<Long> = emptySet()

        Files.list(dir)
            .filter { Files.isDirectory(it) }
            .filter { it.fileName.toString().startsWith("seed") }
            .sorted()
            .forEach { seedDir ->
                log.info("Writing seed ${seedDir.fileName} into summary")

                val grammarCoverage =
                    getGrammarCoverage(allTerminals, seedDir)
                val codeCoverage =
                    getCodeCoverage(allStatements, seedDir)

                totalCodeCoverageOfAllSeeds = totalCodeCoverageOfAllSeeds + codeCoverage.reached // accumulate reached
                totalNew = totalNew + codeCoverage.new

                var debugIntersect = totalNew.intersect(totalCodeCoverageOfAllSeeds)
                log.info("debugIntersect size: " + debugIntersect.size)


                val index = seedDir.fileName.toString().removePrefix("seed").toInt()
                val inputSize = if (seedNr >= 0) {
                    getInputSize(inputs[0])
                } else {
                    getInputSize(inputs[index])
                }

                // val instrFile = Paths.get("/home/leon/HiWi/droidgram/gut/droidgram/apks/com.carlo_2.3.1.apk.json")
                // val instrFile = Paths.get("/home/leon/HiWi/droidgram/gut/droidgram/apks/org.berlin_vegan.bvapp_26.apk.json")
                // val instrFile = Paths.get("/home/leon/HiWi/droidgram/orig/fork/droidgram-1/apks/com.mirkoo.apps.mislibros_231_apps.evozi.com.apk.json")
                val instrFile = Paths.get("/home/leon/HiWi/droidgram/orig/fork/droidgram-1/apks/com.ajaxmobiletech.pocketlibrary_16020002_apps.evozi.com.apk.json")
                val totalSize = readInstrumentationFile(instrFile).size
                val totalCodeCov = (codeCoverage.reached.size + codeCoverage.new.size) / totalSize.toDouble()

                sb.append(index)
                    .append("\t")
                    .append(inputSize.sum())
                    .append("\t")
                    .append(grammarCoverage.reached.size)
                    .append("\t")
                    .append(grammarCoverage.missed.size)
                    .append("\t")
                    .append(grammarCoverage.coverage)
                    .append("\t")
                    .append(codeCoverage.reached.size)
                    .append("\t")
                    .append(codeCoverage.missed.size)
                    .append("\t")
                    .append(codeCoverage.coverage)
                    .append("\t")
                    .append(codeCoverage.new.size)
                    .append("\t")
                    .append(totalCodeCov)
                    .appendln()
            }

        sb.append("total # of unique covered statements of all seeds: " + totalCodeCoverageOfAllSeeds.size).appendln()

        // accumulate: set of all NEW
        // accumulate: set of all REACHED(known)
        // calculate set diff: alle new, die nicht in reached sind. d.h. NEW - REACHED(known)
        totalNew = totalNew - totalCodeCoverageOfAllSeeds
        sb.append("# of new statements that were not covered during initial exploration: " + totalNew.size).appendln()



        val summaryFile = dir.resolve("summary.txt")
        Files.write(summaryFile, sb.toString().toByteArray())
    }

    private fun calculateGrammarCoverage(inputs: List<String>, dir: Path): Result<String> {
        val allTerminals = inputs
            .flatMap {
                it.split(" ")
                    .toList()
                    .filter { p -> p.isNotEmpty() }
            }.toSet()

        val reached = getReachedTerminals(dir)
        val missing = allTerminals - reached

        val res = Result(allTerminals.toList(), reached, missing)

        localCheck(res.coverage in 0.0..1.0) { "Expected terminal coverage between 0 and 1. Found ${res.coverage}" }

        return res
    }

    private fun Path.getInputs(): List<List<String>> {
        val files = getFilesInputs(this)

        localCheck(files.isNotEmpty()) { "Input directory $this doesn't contain any input file (inputs*.txt)" }

        val data = files.map { inputFile ->
            Files.readAllLines(inputFile)
                .filter { it.isNotEmpty() }
        }

        localCheck(data.size == nrSeeds) { "Expecting $nrSeeds seeds per app. Found ${data.size}" }

        return data
    }

    private fun Path.getSeedDirs(): List<Path> {
        val seedDirs = Files.list(this)
            .sorted()
            .filter { Files.isDirectory(it) }
            .filter { it.fileName.toString().startsWith("seed") }
            .toList()

        localCheck(seedDirs.size == nrSeeds) { "Expecting $nrSeeds seed results. Found ${seedDirs.size}" }

        return seedDirs
    }

    private fun Path.findApkJSON(apk: String): Path {
        val jsonFile = Files.walk(this)
            .toList()
            .firstOrNull { it.fileName.toString().startsWith(apk) &&
                    it.fileName.toString().endsWith(".apk.json")
            }

        check(jsonFile != null && Files.exists(jsonFile)) { "Instrumentation file $jsonFile not found" }

        return jsonFile
    }

    private fun getTotalLOC(jsonFile: Path): Set<Long> {
        val jsonData = String(Files.readAllBytes(jsonFile))
        val jObj = JSONObject(jsonData)

        val jMap = jObj.getJSONObject("allMethods")

        return jMap.keys()
            .asSequence()
            .map { it.toLong() }
            .toSet()
    }

    private fun localCheck(value: Boolean, lazyMessage: () -> String): Boolean {
        if (!value) {
            log.warn(lazyMessage())
        }

        return value
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val experimentRootDir = if (args.isNotEmpty()) {
            Paths.get(args.first())
        } else {
            Paths.get("/Users/nataniel/Documents/saarland/repositories/test/droidgram/out/colossus")
        }

        val sb = StringBuilder()

        Files.list(experimentRootDir)
            .forEach { rootDir ->
                val inputDir = rootDir.resolve("input").resolve("apks")
                val resultDir = rootDir.resolve("output")
                val apksDir = rootDir.resolve("apks")
                val modelDir = inputDir.resolve("droidMate")
                try {
                    log.debug("Processing input dir: $inputDir")
                    log.debug("Processing output dir: $resultDir")

                    localCheck(Files.exists(modelDir)) {
                        "Droidmate dir not found in $modelDir"
                    }

                    val jsonFile = apksDir.findApkJSON(rootDir.fileName.toString())
                    log.debug("Processing instrumentation file: $jsonFile")
                    val loc = getTotalLOC(jsonFile)

                    val translationTableFile = inputDir.resolve("translationTable.txt")
                    if (localCheck(Files.exists(translationTableFile)) {
                            "Input directory $inputDir missing translation table file"
                        }) {
                        log.debug("Processing translation table file: $translationTableFile")

                        val translationTable = Files.readAllLines(translationTableFile)
                            .filter { it.isNotEmpty() }

                        val inputs = inputDir.getInputs()
                        val originalStatements =
                            getReachedStatements(inputDir)

                        val seedDirs = resultDir.getSeedDirs()

                        val result =
                            AppData(
                                rootDir.fileName.toString(),
                                translationTable,
                                loc,
                                originalStatements
                            )

                        seedDirs.forEachIndexed { idx, seedDir ->
                            val input = inputs[idx]
                            val code = calculateCodeCoverage(
                                originalStatements,
                                seedDir
                            )
                            val grammar =
                                calculateGrammarCoverage(input, seedDir)

                            result.addRun(input, grammar, code)
                        }

                        log.debug("Processed $inputDir generating result")
                        val str = result.toString()
                        sb.appendln(str)
                        println(str)
                    }
                } catch (e: IllegalStateException) {
                    log.error("${rootDir.fileName} - ${e.message}")
                }
            }

        sb.lineSequence().forEach { line ->
            println(line)
        }
    }
}

/*

com.dougkeen.bart_33                            Total LOC       8095    Test Case LOC   6020    Test Case Coverage      0.74366893143916        Grammar States  180     Widgets 52      Runs    Input size      199     208     Grammar (Terminals)     0,439999525852337
com.alaskalinuxuser.justcraigslist_10           Total LOC       6738    Test Case LOC   4958    Test Case Coverage      0.7358266547937073      Grammar States  20      Widgets 42      Runs    Input size      131     134     Grammar (Terminals)     0,9647058823529412      0,9647058823529412      Code    0,8402581686163776      0,8402581686163776
com.ilm.sandwich_35                             Total LOC       6467    Test Case LOC   2879    Test Case Coverage      0.4451832379774238      Grammar States  66      Widgets 50      Runs    Input size      287     287     Grammar (Terminals)     0,7936507936507937      0,8650793650793651      Code    0,9444251476207016      0,9718652309829802
com.vlath.beheexplorer_20064                    Total LOC       4524    Test Case LOC   3200    Test Case Coverage      0.7073386383731212      Grammar States  414     Widgets 460     Runs    Input size      1074    1038    Grammar (Terminals)     0,3926056338028169      0,375                   Code    0,8049999999999999      0,8049999999999999
com.vlille.checker_723                          Total LOC       3552    Test Case LOC   2327    Test Case Coverage      0.6551238738738738      Grammar States  28      Widgets 37      Runs    Input size                      Grammar (Terminals)                                                     Code
de.baumann.sieben_35                            Total LOC       18516   Test Case LOC   590     Test Case Coverage      0.03186433354936272     Grammar States  9       Widgets 16      Runs    Input size      22      23      Grammar (Terminals)     1,0                     1,0                     Code    0,9084745762711864      0,9084745762711864
de.christinecoenen.code.zapp_31                 Total LOC       6432    Test Case LOC   10      Test Case Coverage      0.001554726368159204    Grammar States  1       Widgets 2       Runs    Input size      6       6       Grammar (Terminals)     1,0                     1,0                     Code    1,0                     1,0
de.grobox.liberario_113                         Total LOC       21124   Test Case LOC   9145    Test Case Coverage      0.43291990153380044     Grammar States  83      Widgets 75      Runs    Input size      251     238     Grammar (Terminals)     0,10185185185185186     0,10185185185185186     Code    0,5234554401312193      0,5234554401312193
de.koelle.christian.trickytripper_23            Total LOC       16677   Test Case LOC   6319    Test Case Coverage      0.3789050788511123      Grammar States  162     Widgets 104     Runs    Input size      428     440     Grammar (Terminals)     0,6199095022624435      0,6199095022624435      Code    0,7350846652951417      0,7350846652951417
de.thecode.android.tazreader_3090203            Total LOC       26134   Test Case LOC   11327   Test Case Coverage      0.4334200658146476      Grammar States  36      Widgets 33      Runs    Input size      218     219     Grammar (Terminals)     0,3893805309734514      0,4070796460176991      Code    0,9818133662929284      0,9845501898119537
de.vibora.viborafeed_28                         Total LOC       2276    Test Case LOC   1318    Test Case Coverage      0.5790861159929701      Grammar States  50      Widgets 42      Runs    Input size      118     118     Grammar (Terminals)     0,3648648648648649      0,45945945945945943     Code    0,9203338391502276      0,9484066767830046
edu.cmu.cs.speech.tts.flite_4                   Total LOC       1493    Test Case LOC   1208    Test Case Coverage      0.8091091761553918      Grammar States  96      Widgets 43      Runs    Input size      146     138     Grammar (Terminals)     0,45977011494252873     0,5287356321839081      Code    0,810430463576159       0,8096026490066225
fr.ybo.transportsrennes_413                     Total LOC       19951   Test Case LOC   2050    Test Case Coverage      0.10275174176732996     Grammar States  2       Widgets 0       Runs    Input size                      Grammar (Terminals)                                                     Code
info.metadude.android.bitsundbaeume.schedule_51 Total LOC       4434    Test Case LOC   1429    Test Case Coverage      0.32228236355435275     Grammar States  39      Widgets 51      Runs    Input size                      Grammar (Terminals)                                                     Code
it.mn.salvi.linuxDayOSM_6                       Total LOC       2972    Test Case LOC   1596    Test Case Coverage      0.5370121130551817      Grammar States  6       Widgets 11      Runs    Input size                      Grammar (Terminals)                                                     Code
net.justdave.nwsweatheralertswidget_10          Total LOC       2647    Test Case LOC   1385    Test Case Coverage      0.5232338496411031      Grammar States  118     Widgets 46      Runs    Input size                      Grammar (Terminals)                                                     Code
net.sf.times_37                                 Total LOC       12444   Test Case LOC   6441    Test Case Coverage      0.5175988428158148      Grammar States  122     Widgets 74      Runs    Input size      175     178     Grammar (Terminals)     0,7272727272727273      0,6704545454545454      Code    0,9448843347306319      0,9205092376960099
org.asdtm.fas_3                                 Total LOC       6813    Test Case LOC   5129    Test Case Coverage      0.7528254806986643      Grammar States  207     Widgets 216     Runs    Input size      532     543     Grammar (Terminals)     0,4386617100371747      0,379182156133829       Code    0,9391694287385455      0,8960811074283486
org.coolreader_2091                             Total LOC       53883   Test Case LOC   24693   Test Case Coverage      0.4582706976226268      Grammar States  131     Widgets 112     Runs    Input size                      Grammar (Terminals)                                                     Code
org.fossasia.openevent_101                      Total LOC       13983   Test Case LOC   6419    Test Case Coverage      0.45905742687549167     Grammar States  68      Widgets 81      Runs    Input size      246     226     Grammar (Terminals)     0,5798319327731092      0,5798319327731092      Code    0,8995170587318897      0,8995170587318897
org.jamienicol.episodes_12                      Total LOC       4441    Test Case LOC   903     Test Case Coverage      0.20333258275163252     Grammar States  16      Widgets 20      Runs    Input size      50      47      Grammar (Terminals)     1,0                     1,0                     Code    0,8981173864894795      0,8981173864894795
org.quantumbadger.redreader_87                  Total LOC       43357   Test Case LOC   20245   Test Case Coverage      0.46693728809650115     Grammar States  301     Widgets 272     Runs    Input size      631     627     Grammar (Terminals)     0,3894389438943895      0,5544554455445545      Code    0,571054581378118       0,7248703383551494
org.schabi.newpipe_730                          Total LOC       49460   Test Case LOC   18106   Test Case Coverage      0.3660735948241003      Grammar States  190     Widgets 117     Runs    Input size      313     326     Grammar (Terminals)     0,6764705882352942      0,6764705882352942      Code    0,8362421296807688      0,8334253838506572
org.thosp.yourlocalweather_123                  Total LOC       26781   Test Case LOC   13424   Test Case Coverage      0.5012508868227474      Grammar States  169     Widgets 136     Runs    Input size      392     380     Grammar (Terminals)     0,5595238095238095      0,5595238095238095      Code    0,6490613825983313      0,6495083432657927
org.totschnig.myexpenses_369                    Total LOC       72056   Test Case LOC   22414   Test Case Coverage      0.3110636171866326      Grammar States  291     Widgets 366     Runs    Input size      863     920     Grammar (Terminals)     0,27442827442827444     0,2723492723492723      Code    0,7118765057553316      0,7116088159186222
android.nachiketa.ebookdownloader_4             Total LOC       1375    Test Case LOC   555     Test Case Coverage      0.4036363636363636      Grammar States  102     Widgets 167     Runs    Input size      384     363     Grammar (Terminals)     0,15508021390374327     0,1336898395721925      Code    0,8018018018018018      0,8018018018018018
be.digitalia.fosdem_1600162                     Total LOC       11843   Test Case LOC   7226    Test Case Coverage      0.6101494553744828      Grammar States  179     Widgets 247     Runs    Input size      872     893     Grammar (Terminals)     0,6082191780821917      0,7068493150684931      Code    0,9846388043177415      0,9972322169941876
cc.echonet.coolmicapp_10                        Total LOC       2750    Test Case LOC   1995    Test Case Coverage      0.7254545454545455      Grammar States  53      Widgets 37      Runs    Input size      100     100     Grammar (Terminals)     0,423728813559322       0,423728813559322       Code    0,9032581453634085      0,9037593984962407
cl.coders.faketraveler_6                        Total LOC       1347    Test Case LOC   284     Test Case Coverage      0.21083890126206384     Grammar States  3       Widgets 16      Runs    Input size      436     436     Grammar (Terminals)     0,9381443298969072      1,0                     Code    0,795774647887324       0,9577464788732395
com.ames.books_7                                Total LOC       1438    Test Case LOC   235     Test Case Coverage      0.16342141863699583     Grammar States  2       Widgets 2       Runs    Input size      17      17      Grammar (Terminals)     1,0                     1,0                     Code    1,0                     1,0
com.aptasystems.dicewarepasswordgenerator_9     Total LOC       1873    Test Case LOC   577     Test Case Coverage      0.30806193272824345     Grammar States  455     Widgets 15      Runs    Input size      45      38      Grammar (Terminals)     0,8260869565217391      0,6956521739130435      Code    0,9826689774696707      0,9722703639514731
com.bmco.cratesiounofficial_6                   Total LOC       3478    Test Case LOC   1679    Test Case Coverage      0.48274870615296145     Grammar States  52      Widgets 39      Runs    Input size      195     195     Grammar (Terminals)     0,9666666666666667      0,9666666666666667      Code    0,6069088743299583      0,6319237641453246
com.gabm.fancyplaces_9                          Total LOC       3731    Test Case LOC   1956    Test Case Coverage      0.5242562315733047      Grammar States  5       Widgets 13      Runs    Input size      113     111     Grammar (Terminals)     0,5921052631578947      0,5921052631578947      Code    0,9565439672801636      0,9565439672801636
com.iamtrk.androidexplorer_1                    Total LOC       1509    Test Case LOC   20      Test Case Coverage      0.013253810470510271    Grammar States  2       Widgets 1       Runs    Input size      6               Grammar (Terminals)     1,0                                             Code    1,0
com.kgurgul.cpuinfo_40200                       Total LOC       14283   Test Case LOC   7784    Test Case Coverage      0.544983546873906       Grammar States  37      Widgets 25      Runs    Input size      54      52      Grammar (Terminals)     0,8709677419354839      0,8709677419354839      Code    0,8908016443987667      0,8908016443987667
com.totsp.bookworm_19                           Total LOC       8187    Test Case LOC   255     Test Case Coverage      0.031146940271161598    Grammar States  3       Widgets 0       Runs    Input size                      Grammar (Terminals)                                                     Code
com.workingagenda.democracydroid_43             Total LOC       3544    Test Case LOC   2361    Test Case Coverage      0.666196388261851       Grammar States  56      Widgets 97      Runs    Input size                      Grammar (Terminals)                                                     Code
cz.jiriskorpil.amixerwebui_8                    Total LOC       2633    Test Case LOC   508     Test Case Coverage      0.19293581466008355     Grammar States  6       Widgets 8       Runs    Input size      12      16      Grammar (Terminals)     1,0                     1,0                     Code    0,984251968503937       0,984251968503937
ee.ioc.phon.android.speak_1698                  Total LOC       16150   Test Case LOC   864     Test Case Coverage      0.0534984520123839      Grammar States  1       Widgets 0       Runs    Input size                      Grammar (Terminals)                                                     Code
eu.siacs.conversations.legacy_258               Total LOC       64624   Test Case LOC   7495    Test Case Coverage      0.11597858380787324     Grammar States  59      Widgets 25      Runs    Input size      396     392     Grammar (Terminals)     0,7936507936507937      0,7936507936507937      Code    0,9602401601067379      0,9602401601067379
eu.uwot.fabio.altcoinprices_78                  Total LOC       6191    Test Case LOC   3362    Test Case Coverage      0.543046357615894       Grammar States  147     Widgets 126     Runs    Input size                      Grammar (Terminals)                                                     Code
github.vatsal.easyweatherdemo_11                Total LOC       1763    Test Case LOC   402     Test Case Coverage      0.2280204197390811      Grammar States  1       Widgets 3       Runs    Input size      20      20      Grammar (Terminals)     0,75                    1,0                     Code    1,0                     1,0
me.kuehle.carreport_79                          Total LOC       17417   Test Case LOC   6681    Test Case Coverage      0.3835907446747431      Grammar States  168     Widgets 133     Runs    Input size      465     458     Grammar (Terminals)     0,18518518518518523     0,2407407407407407      Code    0,41625505163897625     0,4388564586139799
mobi.boilr.boilr_9                              Total LOC       10955   Test Case LOC   4599    Test Case Coverage      0.41980830670926517     Grammar States  100     Widgets 75      Runs    Input size      246     243     Grammar (Terminals)     0,5661764705882353      0,5661764705882353      Code    0,4990215264187867      0,4990215264187867
org.basketbuilddownloader_13                    Total LOC       1913    Test Case LOC   987     Test Case Coverage      0.5159435441714585      Grammar States  19      Widgets 35      Runs    Input size                      Grammar (Terminals)                                                     Code

 */