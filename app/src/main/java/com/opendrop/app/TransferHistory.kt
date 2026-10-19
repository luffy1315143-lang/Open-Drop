package com.opendrop.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

enum class TransferOutcome {
    SUCCESS,
    REJECTED,
    FAILED,
    INTERRUPTED
}

data class TransferHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val direction: String,
    val sizeBytes: Long?,
    val timestampMillis: Long = System.currentTimeMillis(),
    val outcome: TransferOutcome
) {
    fun formattedTimestamp(): String =
        DateFormat.getDateTimeInstance().format(Date(timestampMillis))
}

object TransferHistoryStore {
    private const val PREFS = "opendrop_transfer_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    @Synchronized
    fun load(context: Context): List<TransferHistoryEntry> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, "[]") ?: "[]"

        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val outcome = runCatching {
                        TransferOutcome.valueOf(item.getString("outcome"))
                    }.getOrNull() ?: continue

                    add(
                        TransferHistoryEntry(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            fileName = item.optString("fileName", "Unknown file"),
                            direction = item.optString("direction", "Unknown"),
                            sizeBytes = if (item.isNull("sizeBytes")) null
                                else item.optLong("sizeBytes"),
                            timestampMillis = item.optLong(
                                "timestampMillis", System.currentTimeMillis()
                            ),
                            outcome = outcome
                        )
                    )
                }
            }.sortedByDescending { it.timestampMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(context: Context, entry: TransferHistoryEntry) {
        val contextApp = context.applicationContext
        val entries = (listOf(entry) + load(contextApp))
            .distinctBy { it.id }
            .take(MAX_ENTRIES)

        val array = JSONArray()
        entries.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("fileName", item.fileName)
                    put("direction", item.direction)
                    if (item.sizeBytes == null) put("sizeBytes", JSONObject.NULL)
                    else put("sizeBytes", item.sizeBytes)
                    put("timestampMillis", item.timestampMillis)
                    put("outcome", item.outcome.name)
                }
            )
        }

        contextApp.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, array.toString())
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ENTRIES)
            .apply()
    }
}
