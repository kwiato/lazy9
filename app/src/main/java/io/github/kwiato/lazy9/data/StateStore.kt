package io.github.kwiato.lazy9.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The whole launcher layout as one small JSON file. */
class StateStore(context: Context) {
    private val file = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): LauncherState = try {
        if (file.exists()) json.decodeFromString<LauncherState>(file.readText()) else LauncherState()
    } catch (e: Exception) {
        LauncherState()
    }

    fun save(state: LauncherState) {
        try {
            val tmp = File(file.parentFile, "state.json.tmp")
            tmp.writeText(json.encodeToString(state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            // keep running with the in-memory state
        }
    }
}
