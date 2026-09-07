package com.localfeed.app.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TaskState { QUEUED, RUNNING, DONE, FAILED }

data class MediaTask(
    val id: String,
    val title: String,
    val detail: String,
    val state: TaskState,
    val progress: Int,
    val total: Int,
    val thumbnailUri: String,
    val updatedAt: Long
)

class TaskCenter(context: Context) {
    private val prefs = context.getSharedPreferences("localfeed_tasks", Context.MODE_PRIVATE)
    private val tasks = load().toMutableList()
    var listener: ((List<MediaTask>) -> Unit)? = null

    @Synchronized fun start(title: String, detail: String = "", thumbnailUri: String = ""): String {
        val id = UUID.randomUUID().toString()
        tasks.add(0, MediaTask(id, title, detail, TaskState.RUNNING, 0, 0, thumbnailUri, System.currentTimeMillis()))
        commit(); return id
    }

    @Synchronized fun update(id: String, detail: String, progress: Int = 0, total: Int = 0) = mutate(id) {
        it.copy(detail = detail, state = TaskState.RUNNING, progress = progress, total = total, updatedAt = System.currentTimeMillis())
    }

    @Synchronized fun finish(id: String, detail: String) = mutate(id) {
        it.copy(detail = detail, state = TaskState.DONE, progress = it.total, updatedAt = System.currentTimeMillis())
    }

    @Synchronized fun fail(id: String, detail: String) = mutate(id) {
        it.copy(detail = detail, state = TaskState.FAILED, updatedAt = System.currentTimeMillis())
    }

    @Synchronized fun snapshot(): List<MediaTask> = tasks.toList()

    @Synchronized fun clearFinished() {
        tasks.removeAll { it.state == TaskState.DONE || it.state == TaskState.FAILED }
        commit()
    }

    private fun mutate(id: String, block: (MediaTask) -> MediaTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) { tasks[index] = block(tasks[index]); commit() }
    }

    private fun commit() {
        while (tasks.size > 80) tasks.removeLast()
        val array = JSONArray()
        tasks.forEach { t -> array.put(JSONObject().apply {
            put("id", t.id); put("title", t.title); put("detail", t.detail); put("state", t.state.name)
            put("progress", t.progress); put("total", t.total); put("thumbnailUri", t.thumbnailUri); put("updatedAt", t.updatedAt)
        }) }
        prefs.edit().putString("history", array.toString()).apply()
        listener?.invoke(tasks.toList())
    }

    private fun load(): List<MediaTask> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]").orEmpty())
        buildList { for (i in 0 until array.length()) array.getJSONObject(i).let { o -> add(MediaTask(
            o.getString("id"), o.getString("title"), o.optString("detail"),
            runCatching { TaskState.valueOf(o.optString("state")) }.getOrDefault(TaskState.FAILED),
            o.optInt("progress"), o.optInt("total"), o.optString("thumbnailUri"), o.optLong("updatedAt")
        )) } }
    }.getOrDefault(emptyList())
}
