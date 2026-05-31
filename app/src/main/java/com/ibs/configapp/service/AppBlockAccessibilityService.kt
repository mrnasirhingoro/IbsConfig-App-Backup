package com.ibs.configapp.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ibs.configapp.util.CallBlockManager
import com.ibs.configapp.util.PrefsHelper

class AppBlockAccessibilityService : AccessibilityService() {

    companion object {
        private val BLOCKED_PACKAGES = setOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.instagram.android",
            "com.whatsapp",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube",
            "com.snapchat.android"
        )

        private val CALL_UI_PACKAGES = setOf(
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.miui.incallui",
            "com.android.server.telecom"
        )

        private val DECLINE_LABELS = listOf(
            "decline",
            "reject",
            "end call",
            "رد",
            "منسوخ"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        if (PrefsHelper.isCallsBlocked(this)) {
            handleCallBlocking(event, packageName)
        }

        if (!PrefsHelper.isAppsBlocked(this)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (BLOCKED_PACKAGES.contains(packageName)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun handleCallBlocking(event: AccessibilityEvent, packageName: String) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        if (!CALL_UI_PACKAGES.any { packageName.contains(it, ignoreCase = true) }) return

        val root = rootInActiveWindow ?: return
        if (clickDeclineButton(root)) {
            return
        }
        CallBlockManager.rejectIncomingCall(this)
    }

    private fun clickDeclineButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (node.isClickable && DECLINE_LABELS.any { label ->
                text.contains(label) || desc.contains(label)
            }
        ) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = clickDeclineButton(child)
            child.recycle()
            if (clicked) return true
        }
        return false
    }

    override fun onInterrupt() { }
}
