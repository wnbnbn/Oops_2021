package com.localfeed.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskState { QUEUED, RUNNING, DONE, FAILED }

enum class TaskOperation { NONE, PERMANENT_DELETE, MOVE_TO_TRASH }

data class MediaTask(
    val id: String,
    val title: String,
    val detail: String,
    val state: TaskState,
    val progress: Int,
    val total: Int,
    val thumbnailUri: String,
    val updatedAt: Long,
    val operation: TaskOperation = TaskOperation.NONE,
    val targetIds: List<Long> = emptyList(),
    val completedIds: Set<Long> = emptySet()
)

class TaskCenter(context: Context) {
    private val prefs = context.getSharedPreferences("localfeed_tasks", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tasks = load().toMutableList()
    private var lastPersistAt = 0L
    private var lastNotifyAt = 0L
    var listener: ((List<MediaTask>) -> Unit)? = null

    @Synchronized fun start(title: String, detail: String = "", thumbnailUri: String = ""): String {
        val id = UUID.randomUUID().toString()
        tasks.add(0, MediaTask(id, title, detail, TaskState.RUNNING, 0, 0, thumbnailUri, System.currentTimeMillis()))
        commit(forcePersist = true, forceNotify = true); return id
    }

    /** Starts file work whose exact targets survive process death and can be resumed safely. */
    @Synchronized fun startPersistent(
        title: String,
        detail: String,
        thumbnailUri: String,
        operation: TaskOperation,
        targetIds: List<Long>
    ): String {
        val id = UUID.randomUUID().toString()
        val uniqueTargets = targetIds.distinct()
        tasks.add(0, MediaTask(
            id, title, detail, TaskState.QUEUED, 0, uniqueTargets.size, thumbnailUri,
            System.currentTimeMillis(), operation, uniqueTargets
        ))
        commit(forcePersist = true, durable = true, forceNotify = true)
        return id
    }

    @Synchronized fun markRunning(id: String, detail: String? = null) = mutate(id, forcePersist = true, forceNotify = true) {
        it.copy(
            detail = detail ?: it.detail,
            state = TaskState.RUNNING,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Synchronized fun markTargetComplete(id: String, targetId: Long, detail: String) = mutate(id) {
        val completed = it.completedIds + targetId
        it.copy(
            detail = detail,
            state = TaskState.RUNNING,
            progress = completed.size.coerceAtMost(it.targetIds.size),
            total = it.targetIds.size,
            completedIds = completed,
            updatedAt = System.currentTimeMillis()
        )
    }

    @Synchronized fun resumable(): List<MediaTask> = tasks.filter {
        it.operation != TaskOperation.NONE && it.state in setOf(TaskState.QUEUED, TaskState.RUNNING)
    }

    @Synchronized fun update(id: String, detail: String, progress: Int = 0, total: Int = 0) = mutate(id) {
        it.copy(detail = detail, state = TaskState.RUNNING, progress = progress, total = total, updatedAt = System.currentTimeMillis())
    }

    @Synchronized fun finish(id: String, detail: String) = mutate(id, forcePersist = true, durable = true, forceNotify = true) {
        it.copy(detail = detail, state = TaskState.DONE, progress = it.total, updatedAt = System.currentTimeMillis(), targetIds = emptyList(), completedIds = emptySet())
    }

    @Synchronized fun fail(id: String, detail: String) = mutate(id, forcePersist = true, durable = true, forceNotify = true) {
        it.copy(detail = detail, state = TaskState.FAILED, updatedAt = System.currentTimeMillis(), targetIds = emptyList(), completedIds = emptySet())
    }

    @Synchronized fun snapshot(): List<MediaTask> = tasks.toList()

    @Synchronized fun clearFinished() {
        tasks.removeAll { it.state == TaskState.DONE || it.state == TaskState.FAILED }
        commit(forcePersist = true, durable = true, forceNotify = true)
    }

    private fun mutate(
        id: String,
        forcePersist: Boolean = false,
        durable: Boolean = false,
        forceNotify: Boolean = false,
        block: (MediaTask) -> MediaTask
    ) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) {
            tasks[index] = block(tasks[index])
            commit(forcePersist, durable, forceNotify)
        }
    }

    /**
     * Progress can arrive dozens of times per second during a large scan or delete. Serialising a
     * target list with thousands of IDs and synchronously fsyncing SharedPreferences for every
     * tick used to stall the UI and could trigger an ANR exactly when a scan completed.
     */
    private fun commit(forcePersist: Boolean, durable: Boolean = false, forceNotify: Boolean = false) {
        while (tasks.size > 80) tasks.removeLast()
        val now = System.currentTimeMillis()
        if (forcePersist || now - lastPersistAt >= 1_000L) {
            val array = JSONArray()
            tasks.forEach { t -> array.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("detail", t.detail); put("state", t.state.name)
                put("progress", t.progress); put("total", t.total); put("thumbnailUri", t.thumbnailUri); put("updatedAt", t.updatedAt)
                put("operation", t.operation.name)
                put("targetIds", JSONArray(t.targetIds))
                put("completedIds", JSONArray(t.completedIds.toList()))
            }) }
            val editor = prefs.edit().putString("history", array.toString())
            if (durable) editor.commit() else editor.apply()
            lastPersistAt = now
        }
        if (forceNotify || now - lastNotifyAt >= 200L) {
            val snapshot = tasks.toList()
            val callback = Runnable { listener?.invoke(snapshot) }
            if (Looper.myLooper() == Looper.getMainLooper()) callback.run() else mainHandler.post(callback)
            lastNotifyAt = now
        }
    }

    private fun load(): List<MediaTask> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]").orEmpty())
        buildList { for (i in 0 until array.length()) array.getJSONObject(i).let { o ->
            val operation = runCatching { TaskOperation.valueOf(o.optString("operation", "NONE")) }.getOrDefault(TaskOperation.NONE)
            val savedState = runCatching { TaskState.valueOf(o.optString("state")) }.getOrDefault(TaskState.FAILED)
            val state = when {
                savedState != TaskState.RUNNING -> savedState
                operation != TaskOperation.NONE -> TaskState.QUEUED
                else -> TaskState.FAILED
            }
            val targets = o.optJSONArray("targetIds").toLongList()
            val completed = o.optJSONArray("completedIds").toLongList().toSet()
            val detail = if (savedState == TaskState.RUNNING && operation != TaskOperation.NONE) "上次中断 · 等待自动继续" else if (savedState == TaskState.RUNNING) "应用中断 · 此任务未完成" else o.optString("detail")
            add(MediaTask(
                o.getString("id"), o.getString("title"), detail, state,
                completed.size.coerceAtLeast(o.optInt("progress")), targets.size.coerceAtLeast(o.optInt("total")),
                o.optString("thumbnailUri"), o.optLong("updatedAt"), operation, targets, completed
            ))
        } }
    }.getOrDefault(emptyList())

    private fun JSONArray?.toLongList(): List<Long> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) add(optLong(i)) }
    }
}
