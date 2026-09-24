package com.autumn.douyin.liquidglass.ui

import android.view.SurfaceControl
import com.autumn.douyin.liquidglass.ModuleLog
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.WeakHashMap

/**
 * Read-only metadata for host-created SurfaceControls.
 *
 * Android can expose several valid player surfaces at once. Besides object
 * identity, the registry keeps the current parent link and buffer activity so
 * a player can be matched to the SurfaceView that is actually beneath the
 * glass. It never mutates or releases a host layer.
 */
object CapturedLayerRegistry {
    private val entries = LinkedHashMap<String, CapturedLayerEntry>()
    private var activeControl: SurfaceControl? = null
    private var nextSequence = 1L
    private var lastReparentLogTime = 0L
    private val builderParents = WeakHashMap<Any, SurfaceControl>()

    data class CapturedLayer(
        val control: SurfaceControl,
        val key: String,
        val sequence: Long,
        val parentKey: String?,
        val visible: Boolean,
        val lastBufferAt: Long,
        val bufferCount: Long,
    )

    private class CapturedLayerEntry(
        var reference: WeakReference<SurfaceControl>,
        val key: String,
        val sequence: Long,
    ) {
        var parentKey: String? = null
        var parentReference: WeakReference<SurfaceControl> = WeakReference(null)
        var visible: Boolean = true
        var lastBufferAt: Long = 0L
        var bufferCount: Long = 0L
    }

    fun recordBuilderParent(builder: Any, parent: SurfaceControl?) {
        synchronized(builderParents) {
            if (parent == null) builderParents.remove(builder) else builderParents[builder] = parent
        }
    }

    fun consumeBuilderParent(builder: Any): SurfaceControl? {
        synchronized(builderParents) {
            return builderParents.remove(builder)
        }
    }

    fun register(
        control: SurfaceControl?,
        builderParent: SurfaceControl? = null,
    ): CapturedLayer? {
        if (control == null || !control.isValid) return null
        val key = keyOf(control)
        val parentKey = builderParent?.takeIf { it.isValid }?.let { keyOf(it) }
        val result: CapturedLayer?
        synchronized(entries) {
            pruneLocked()
            val existing = entries[key]
            if (existing == null) {
                val entry = CapturedLayerEntry(WeakReference(control), key, nextSequence++)
                entry.parentKey = parentKey
                entry.parentReference = WeakReference(builderParent)
                entries[key] = entry
                result = entry.toCapturedLayer()
                ModuleLog.info { "captured surfacecontrol registered: $control total=${entries.size}" }
            } else {
                // A framework call can hand us another wrapper for the same native layer.
                existing.reference = WeakReference(control)
                if (builderParent != null && builderParent.isValid) {
                    existing.parentKey = parentKey
                    existing.parentReference = WeakReference(builderParent)
                }
                result = existing.toCapturedLayer()
            }
        }
        if (builderParent != null && builderParent.isValid && control.toString().contains("bbq-wrapper", ignoreCase = true)) {
            ModuleLog.info { "captured buffer surfacecontrol: $control parent=$builderParent" }
        }
        return result
    }

    fun recordReparent(child: SurfaceControl?, parent: SurfaceControl?) {
        if (child == null || !child.isValid) return
        register(child)
        val parentKey = parent?.takeIf { it.isValid }?.let { keyOf(it) }
        if (parent != null && parent.isValid) register(parent)

        val childKey = keyOf(child)
        synchronized(entries) {
            entries[childKey]?.let { entry ->
                entry.parentKey = parentKey
                entry.parentReference = WeakReference(parent)
            }
        }

        val now = System.currentTimeMillis()
        val childName = child.toString()
        val parentName = parent?.toString() ?: "null"
        if (
            (childName.contains("ttPlayer", ignoreCase = true) ||
                childName.contains("live-player", ignoreCase = true) ||
                parentName.contains("SurfaceView[", ignoreCase = true)) &&
            now - lastReparentLogTime >= 1_000L
        ) {
            lastReparentLogTime = now
            ModuleLog.info { "captured layer reparent: child=$childName parent=$parentName" }
        }
    }

    fun recordVisibility(control: SurfaceControl?, visible: Boolean) {
        val registered = register(control) ?: return
        synchronized(entries) {
            entries[registered.key]?.visible = visible
        }
    }

    fun noteBuffer(control: SurfaceControl?) {
        val registered = register(control) ?: return
        val now = System.nanoTime()
        synchronized(entries) {
            val entry = entries[registered.key] ?: return
            entry.bufferCount += 1
            entry.lastBufferAt = now

            // Buffer updates usually land on a bbq-wrapper below the player.
            // Propagate that activity upward so the selectable ttPlayer/live
            // container knows which subtree is currently receiving frames.
            var key = entry.parentKey
            var hops = 0
            while (key != null && hops < 12) {
                val parent = entries[key] ?: break
                if (parent.lastBufferAt < now) parent.lastBufferAt = now
                key = parent.parentKey
                hops += 1
            }
        }
    }

    fun markSuccessful(control: SurfaceControl) {
        synchronized(entries) {
            if (!control.isValid) return
            activeControl = control
        }
    }

    fun activeControl(): SurfaceControl? {
        synchronized(entries) {
            val control = activeControl?.takeIf { it.isValid }
            if (control == null) activeControl = null
            return control
        }
    }

    fun clearActive() {
        synchronized(entries) {
            activeControl = null
        }
    }

    fun keyOf(control: SurfaceControl): String {
        // SurfaceControl#toString is "Surface(name=...#layerId)/@wrapper".
        // The part before /@ is stable across wrappers for the same layer.
        return control.toString().substringBefore("/@")
    }

    fun isDescendantOf(child: SurfaceControl?, ancestor: SurfaceControl?): Boolean {
        if (child == null || ancestor == null || !child.isValid || !ancestor.isValid) return false
        val ancestorKey = keyOf(ancestor)
        var key = keyOf(child)
        var hops = 0
        synchronized(entries) {
            pruneLocked()
            while (hops < 16) {
                if (key == ancestorKey) return true
                val parentKey = entries[key]?.parentKey ?: return false
                if (parentKey == ancestorKey) return true
                key = parentKey
                hops += 1
            }
        }
        return false
    }

    fun snapshot(): List<SurfaceControl> {
        return snapshotEntries().map { it.control }
    }

    fun snapshotEntries(): List<CapturedLayer> {
        synchronized(entries) {
            pruneLocked()
            return entries.values.mapNotNull { it.toCapturedLayer() }
        }
    }

    fun entry(control: SurfaceControl): CapturedLayer? {
        val key = keyOf(control)
        synchronized(entries) {
            pruneLocked()
            return entries[key]?.toCapturedLayer()
        }
    }

    fun parentSurfaceControl(control: SurfaceControl): SurfaceControl? {
        val key = keyOf(control)
        synchronized(entries) {
            pruneLocked()
            return entries[key]?.parentReference?.get()?.takeIf { it.isValid }
        }
    }

    private fun pruneLocked() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            val control = entry.reference.get()
            if (control == null || !control.isValid) {
                if (activeControl?.let { keyOf(it) } == entry.key) activeControl = null
                iterator.remove()
            }
        }
    }

    private fun CapturedLayerEntry.toCapturedLayer(): CapturedLayer? {
        val control = reference.get()?.takeIf { it.isValid } ?: return null
        return CapturedLayer(
            control = control,
            key = key,
            sequence = sequence,
            parentKey = parentKey,
            visible = visible,
            lastBufferAt = lastBufferAt,
            bufferCount = bufferCount,
        )
    }
}
