package org.droidmate.droidgram.runner

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import org.droidmate.deviceInterface.exploration.ActionType
import org.droidmate.deviceInterface.exploration.ExplorationAction
import org.droidmate.deviceInterface.exploration.GlobalAction
import org.droidmate.deviceInterface.exploration.Swipe
import org.droidmate.droidgram.exploration.CustomModel
import org.droidmate.droidgram.exploration.CustomState
import org.droidmate.droidgram.exploration.CustomWidget
import org.droidmate.droidgram.grammar.notReachedTerminals
import org.droidmate.droidgram.grammar.reachedTerminals
import org.droidmate.exploration.ExplorationContext
import org.droidmate.exploration.actions.click
import org.droidmate.exploration.actions.clickEvent
import org.droidmate.exploration.actions.longClick
import org.droidmate.exploration.actions.longClickEvent
import org.droidmate.exploration.actions.pressBack
import org.droidmate.exploration.actions.resetApp
import org.droidmate.exploration.actions.setText
import org.droidmate.exploration.actions.terminateApp
import org.droidmate.exploration.actions.tick
import org.droidmate.exploration.modelFeatures.ModelFeature
import java.lang.IllegalStateException
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.CoroutineContext

class GrammarReplayMF(
    generatedInput: String,
    private val translationTable: Map<String, UUID>,
    private val delay: Long
) : ModelFeature() {
    override val coroutineContext: CoroutineContext = CoroutineName("GrammarReplayMF") + Job()

    private var currIndex: Int = -1 // -2
    private lateinit var context: ExplorationContext<CustomModel, CustomState, CustomWidget>

    private val missedInputs = mutableListOf<GrammarInput>()
    private val reachedInputs = mutableListOf<GrammarInput>()

    private val inputList by lazy {
        val inputs = generatedInput
            .split("ÿ") // was: " ", but problem for example with "United States" -> split
            .filter { it.isNotEmpty() }
            .filter { it.contains("(") }
            .flatMap {
                val actionInput =
                    GrammarInput.fromString(it, translationTable)
                val fetchInput = GrammarInput.createFetch(actionInput)

                listOf(fetchInput, actionInput)
            }

        log.info("Produced action list: ${inputs.joinToString { it.toString() }}")

        inputs
    }

    @Suppress("UNCHECKED_CAST")
    override fun onAppExplorationStarted(context: ExplorationContext<*, *, *>) {
        this.context = context as ExplorationContext<CustomModel, CustomState, CustomWidget>
    }

    private fun CustomWidget.toAction(): ExplorationAction {
        val input = inputList[currIndex]

        return when {
            input.isClick() -> this.click(delay, isVisible = true, ignoreClickable = true)
            input.isClickEvent() -> this.clickEvent(delay, ignoreClickable = true)

            input.isLongClick() -> this.longClick(delay, true)
            input.isLongClickEvent() -> this.longClickEvent(delay, ignoreVisibility = true)

            input.isTick() -> this.tick(delay, ignoreVisibility = true)

            input.isTextInsert() -> this.setText(input.textualInput, ignoreVisibility = true)

            input.isSwipe() -> {
                val payload = input.textualInput
                val srcPointStr = payload.split("TO").first().trim()
                val dstPointStr = payload.split("TO").last().trim()
                val srcPoint = Pair(srcPointStr.split(";").first().trim().toInt(), srcPointStr.split(";").last().trim().toInt())
                val dstPoint = Pair(dstPointStr.split(";").first().trim().toInt(), dstPointStr.split(";").last().trim().toInt())
                Swipe(srcPoint, dstPoint)
            }

            else -> throw IllegalStateException("Unsupported action type: $input")
        }
    }

    @JvmOverloads
    fun nextAction(state: CustomState, printLog: Boolean = false): ExplorationAction {
        currIndex++
        log.info("nextAction: currIndex: $currIndex")

        val target = if (currIndex >= 0 && currIndex < inputList.size)
            inputList[currIndex]
        else
            GrammarInput.empty

        val targetUID = target.widget
        val targetWidget = state.actionableWidgets.firstOrNull { it.uid == targetUID }
        val currState = context.getCurrentState()

        // log.info("all widgets in state: " + state.actionableWidgets)
        // log.info("targetUID: " + targetUID)

        val action = when {
            currIndex < 0 -> {
                log.info("resetApp()")
                context.resetApp()
            }

            currIndex >= inputList.size -> {
                log.info("terminateApp()")
                ExplorationAction.terminateApp()
            }

            target.isBack() -> {
                log.info("isBack()")
                reachedInputs.add(target)
                ExplorationAction.pressBack()
            }

            // Left the app for some reason (crash, exit, etc)
            !currState.hasActionableWidgets || currState.isHomeScreen -> {
                log.warn("Exploration is outside of the app. Restarting the app to try to continue")
                currIndex--
                context.resetApp()
            }

            // Sequence of actions is Fetch -> Execute, if the widget is already here, skip the fetch
            (targetWidget != null) && (targetWidget.isInteractive) && target.isFetch() -> {
                log.info("nextAction(state, false)")
                nextAction(state, false)
            }

            // Widget is on screen, interact with it
            (targetWidget != null) -> {
                println("toAction!")
                reachedInputs.add(target)
                targetWidget.toAction()
            }

            // No widget on screen and is fetch... try fetching
            (target.isFetch()) -> GlobalAction(ActionType.FetchGUI)

            // Widget not on screen, skip and see what happens
            else -> {
                log.warn("Widget is ID: $targetUID was not found, proceeding with input")
                missedInputs.add(target)
                nextAction(state, false)
            }
        }

        if (printLog) {
            log.info("Generating action: $action")
        }

        return action
    }

    private fun writeReport(reportName: String, data: List<GrammarInput>) {
        val sb = StringBuilder()

        data.forEach { input ->
            sb.appendln("${input.grammarId};$input")
        }

        val modelDir = context.model.config.baseDir
        val reportFile = modelDir.resolve(reportName).toAbsolutePath()

        log.info("Writing $reportName report to $reportFile")
        Files.write(reportFile, sb.toString().toByteArray())
    }

    override suspend fun dump(context: ExplorationContext<*, *, *>) {
        writeReport(notReachedTerminals, missedInputs)
        writeReport(reachedTerminals, reachedInputs)
    }
}