package org.droidmate.droidgram.reporter

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

data class Result<T>(val originalInputs: List<T>, private val _reached: Set<T>, private val _missed: Set<T>) {
    companion object {
        @JvmStatic
        private val log: Logger by lazy { LoggerFactory.getLogger(this::class.java) }
    }

    val reached: Set<T> by lazy {
        val res = _reached
            .filter { originalInputs.contains(it) }
            .toSet()

        val diff = _reached - res

        if (diff.isNotEmpty()) {
            log.debug("Found ${diff.size} elements which were not on the originalInputs exploration")
        }

        res
    }

    val missed: Set<T> by lazy {
        val res = _missed
            .filter { originalInputs.contains(it) }
            .toSet()

        val diff = _missed - res

        if (diff.isNotEmpty()) {
            log.debug("Found ${diff.size} elements which were not on the originalInputs exploration")
        }

        res
    }

    val new: Set<T> by lazy {
        val res = _reached
            .filter { !originalInputs.contains(it) }
            .toSet()
        res
    }

    val coverage: Double by lazy {
        val total = reached + missed

        if (missed.size > total.size) {
            throw IllegalArgumentException("Missed more than total arguments. Missed: $missed Reached: $reached")
        }

        1 - (missed.size.toDouble() / total.size)
    }

    fun save(outputFile: Path) {
        val sb = StringBuilder()
        sb.appendln("Coverage: $coverage")
            .appendln("NrReached: ${reached.size}")
            .appendln("NrMissed: ${missed.size}")
            .appendln("")
            .appendln("Reached: ${reached.joinToString(" ")}")
            .appendln("Missed: ${missed.joinToString(" ")}")

        Files.write(outputFile, sb.toString().toByteArray())
    }

    override fun toString(): String {
        return "Coverage $coverage\tReached ${reached.size}\tMissed ${missed.size}"
    }
}