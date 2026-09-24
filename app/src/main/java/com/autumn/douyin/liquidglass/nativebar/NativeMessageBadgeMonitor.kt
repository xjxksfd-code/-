package com.autumn.douyin.liquidglass.nativebar

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.autumn.douyin.liquidglass.ModuleLog
import kotlin.math.min

class NativeMessageBadgeMonitor(
    private val messagesButton: View,
    private val onCountChanged: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastCount: Int? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            mainHandler.postDelayed(this, SamplingIntervalMs)
        }
    }

    fun start() {
        if (running) return
        running = true
        ModuleLog.info("native message badge monitor started")
        sample()
        mainHandler.postDelayed(poller, SamplingIntervalMs)
    }

    fun stop() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(poller)
        lastCount = null
        ModuleLog.info("native message badge monitor stopped")
    }

    private fun sample() {
        if (!messagesButton.isAttachedToWindow) return
        val value = Reader.read(messagesButton)
        val count = value?.count ?: 0
        if (count == lastCount) return

        lastCount = count
        onCountChanged(count)
        ModuleLog.info { "native message badge count=$count source=${value?.source ?: "none"}" }
    }

    private object Reader {
        fun read(button: View): Value? {
            var best: Candidate? = null
            collectTextCandidates(button) { view, text, sourceKind ->
                if (view.visibility != View.VISIBLE) return@collectTextCandidates
                if (view.width <= 0 || view.height <= 0) return@collectTextCandidates
                val count = parseCount(text) ?: return@collectTextCandidates
                val candidate = Candidate(
                    count = count,
                    sourceKind = sourceKind,
                    area = view.width.coerceAtLeast(1) * view.height.coerceAtLeast(1),
                    description = describe(view, text, sourceKind),
                )
                if (best == null || candidate.rank < best!!.rank) best = candidate
            }
            return best?.let { Value(it.count, it.description) }
        }

        private fun collectTextCandidates(
            view: View,
            action: (View, String, SourceKind) -> Unit,
        ) {
            if (view is TextView) {
                view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    action(view, it, SourceKind.TEXT)
                }
            }
            view.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                action(view, it, SourceKind.DESCRIPTION)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    view.getChildAt(index)?.let { collectTextCandidates(it, action) }
                }
            }
        }

        private fun parseCount(raw: String): Int? {
            val direct = Regex("^(\\d{1,4})(\\+|\\s*(?:条|个|项))?$").find(raw)
            val leading = Regex("^(?:未读[消息\\s]*)?(\\d{1,4})(?:\\+|\\s*条|\\s*个|\\s*项)")
                .find(raw)
            val match = direct ?: leading ?: return null
            val parsedCount = match.groupValues[1].toIntOrNull() ?: return null
            val count = if (match.groupValues[2] == "+") parsedCount + 1 else parsedCount
            return if (count in 1..9_999) count else null
        }

        private fun describe(view: View, text: String, sourceKind: SourceKind): String =
            "${sourceKind.name.lowercase()}=\"$text\" class=${view.javaClass.simpleName} " +
                "size=${view.width}x${view.height} vis=${visibilityName(view.visibility)}"

        private fun visibilityName(value: Int): String = when (value) {
            View.VISIBLE -> "visible"
            View.INVISIBLE -> "invisible"
            View.GONE -> "gone"
            else -> value.toString()
        }

        private data class Candidate(
            val count: Int,
            val sourceKind: SourceKind,
            val area: Int,
            val description: String,
        ) {
            val rank: Int
                get() = sourceKind.ordinal * 1_000_000 + min(area, 1_000_000)
        }

        data class Value(val count: Int, val source: String)

        private enum class SourceKind { TEXT, DESCRIPTION }
    }

    private companion object {
        const val SamplingIntervalMs = 100L
    }
}
