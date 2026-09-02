package io.github.kaminarios.whip

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.util.Date

class AgentStatusWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { appWidgetId ->
      updateWidget(context, appWidgetManager, appWidgetId)
    }
  }

  companion object {
    private const val PREFS_NAME = "whip_agent_widget"
    private const val SNAPSHOT_KEY = "snapshot_json"
    private const val STALE_AFTER_MS = 5 * 60 * 1000L

    private val rowIds = intArrayOf(
      R.id.widget_agent_row_1,
      R.id.widget_agent_row_2,
      R.id.widget_agent_row_3,
      R.id.widget_agent_row_4,
    )
    private val nameIds = intArrayOf(
      R.id.widget_agent_name_1,
      R.id.widget_agent_name_2,
      R.id.widget_agent_name_3,
      R.id.widget_agent_name_4,
    )
    private val statusIds = intArrayOf(
      R.id.widget_agent_status_1,
      R.id.widget_agent_status_2,
      R.id.widget_agent_status_3,
      R.id.widget_agent_status_4,
    )
    private val detailIds = intArrayOf(
      R.id.widget_agent_detail_1,
      R.id.widget_agent_detail_2,
      R.id.widget_agent_detail_3,
      R.id.widget_agent_detail_4,
    )

    fun storeSnapshot(context: Context, snapshotJson: String) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(SNAPSHOT_KEY, snapshotJson)
        .apply()
      updateAllWidgets(context)
    }

    private fun updateAllWidgets(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val component = ComponentName(context, AgentStatusWidgetProvider::class.java)
      manager.getAppWidgetIds(component).forEach { appWidgetId ->
        updateWidget(context, manager, appWidgetId)
      }
    }

    private fun updateWidget(
      context: Context,
      manager: AppWidgetManager,
      appWidgetId: Int,
    ) {
      val views = RemoteViews(context.packageName, R.layout.widget_agent_status)
      val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(SNAPSHOT_KEY, null)
      val parsed = raw?.let(::parseSnapshot)

      if (parsed == null) {
        views.setTextViewText(R.id.widget_summary, "Open Whip to load agent status")
        views.setTextViewText(R.id.widget_updated, "No cached state")
        hideRows(views)
      } else {
        bindSnapshot(context, views, parsed)
      }

      context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        val pendingIntent = PendingIntent.getActivity(
          context,
          0,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
      }
      manager.updateAppWidget(appWidgetId, views)
    }

    private fun bindSnapshot(
      context: Context,
      views: RemoteViews,
      snapshot: WidgetSnapshot,
    ) {
      val counts = snapshot.rows.groupingBy { it.status }.eachCount()
      val summary = listOf(
        "Working" to (counts["working"] ?: 0),
        "Blocked" to (counts["blocked"] ?: 0),
        "Idle" to (counts["idle"] ?: 0),
        "Done" to (counts["done"] ?: 0),
      ).filter { it.second > 0 }
        .joinToString(" · ") { "${it.first} ${it.second}" }
        .ifEmpty { "No agents currently reported" }
      views.setTextViewText(
        R.id.widget_summary,
        if (snapshot.staleHostCount > 0) {
          "$summary · Stale hosts ${snapshot.staleHostCount}"
        } else {
          summary
        },
      )

      val ageMs = (System.currentTimeMillis() - snapshot.updatedAtMs).coerceAtLeast(0L)
      val formattedTime = android.text.format.DateFormat.getTimeFormat(context)
        .format(Date(snapshot.updatedAtMs))
      val freshness = if (ageMs > STALE_AFTER_MS) {
        "Cached · $formattedTime"
      } else {
        "Updated $formattedTime"
      }
      val hiddenCount = (snapshot.rows.size - rowIds.size).coerceAtLeast(0)
      views.setTextViewText(
        R.id.widget_updated,
        if (hiddenCount > 0) "$freshness · +$hiddenCount more" else freshness,
      )
      bindRows(views, snapshot.rows)
    }

    private fun bindRows(views: RemoteViews, rows: List<WidgetAgentRow>) {
      rowIds.indices.forEach { index ->
        val row = rows.getOrNull(index)
        if (row == null) {
          views.setViewVisibility(rowIds[index], View.GONE)
        } else {
          views.setViewVisibility(rowIds[index], View.VISIBLE)
          views.setTextViewText(nameIds[index], row.label)
          views.setTextViewText(
            statusIds[index],
            if (row.stale) "STALE" else row.status.uppercase(),
          )
          views.setTextColor(statusIds[index], statusColor(row))
          views.setTextViewText(detailIds[index], row.detail)
        }
      }
    }

    private fun statusColor(row: WidgetAgentRow): Int {
      if (row.stale) return Color.parseColor("#FCA5A5")
      return when (row.status) {
        "blocked" -> Color.parseColor("#FBBF24")
        "working" -> Color.parseColor("#86EFAC")
        "done" -> Color.parseColor("#93C5FD")
        "idle" -> Color.parseColor("#D1D5DB")
        else -> Color.parseColor("#CBD5E1")
      }
    }

    private fun hideRows(views: RemoteViews) {
      rowIds.forEach { views.setViewVisibility(it, View.GONE) }
    }

    private fun parseSnapshot(raw: String): WidgetSnapshot? {
      return try {
        val root = JSONObject(raw)
        val updatedAtMs = root.optLong("updatedAtMs", 0L)
        if (root.optInt("schemaVersion", 0) != 1 || updatedAtMs <= 0L) return null
        val hosts = root.optJSONArray("hosts") ?: return null
        val rows = mutableListOf<WidgetAgentRow>()
        var staleHostCount = 0

        for (hostIndex in 0 until hosts.length()) {
          val host = hosts.getJSONObject(hostIndex)
          val connectionStatus = host.optString("connectionStatus")
          val freshness = host.optString("freshness")
          val hostStale = freshness != "fresh" ||
            connectionStatus !in setOf("connected", "ready") ||
            !host.optBoolean("serverRunning", false)
          if (hostStale) staleHostCount += 1

          val hostLabel = host.optString("label", "Host")
          val agents = host.optJSONArray("agents") ?: continue
          for (agentIndex in 0 until agents.length()) {
            val agent = agents.getJSONObject(agentIndex)
            rows += WidgetAgentRow(
              label = agent.optString("label", "Agent"),
              status = agent.optString("status", "unknown"),
              detail = "$hostLabel · ${agent.optString("workspaceLabel")} / ${agent.optString("tabLabel")}",
              stale = hostStale,
            )
          }
        }

        val priority = mapOf(
          "blocked" to 0,
          "working" to 1,
          "idle" to 2,
          "done" to 3,
          "unknown" to 4,
        )
        rows.sortWith(
          compareBy<WidgetAgentRow> { if (it.stale) 0 else 1 }
            .thenBy { priority[it.status] ?: 5 }
            .thenBy { it.label.lowercase() },
        )
        WidgetSnapshot(updatedAtMs, staleHostCount, rows)
      } catch (_: Throwable) {
        null
      }
    }
  }
}

private data class WidgetSnapshot(
  val updatedAtMs: Long,
  val staleHostCount: Int,
  val rows: List<WidgetAgentRow>,
)

private data class WidgetAgentRow(
  val label: String,
  val status: String,
  val detail: String,
  val stale: Boolean,
)
