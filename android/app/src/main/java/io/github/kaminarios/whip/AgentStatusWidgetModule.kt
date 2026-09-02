package io.github.kaminarios.whip

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class AgentStatusWidgetModule(
  private val context: ReactApplicationContext,
) : ReactContextBaseJavaModule(context) {
  override fun getName(): String = "AgentStatusWidget"

  @ReactMethod
  fun updateSnapshot(snapshotJson: String, promise: Promise) {
    try {
      AgentStatusWidgetProvider.storeSnapshot(context, snapshotJson)
      promise.resolve(null)
    } catch (error: Throwable) {
      promise.reject("E_AGENT_WIDGET_UPDATE", error)
    }
  }
}
