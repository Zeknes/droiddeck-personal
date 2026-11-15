package com.droiddeck.launcher.ui

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

fun createRobolectricComposeRule(): ComposeContentTestRule {
    val rule = createComposeRule()
    return object : ComposeContentTestRule by rule {
        override fun apply(base: Statement, description: Description): Statement {
            val wrapped = rule.apply(base, description)
            return object : Statement() {
                override fun evaluate() {
                    val handler = Handler(Looper.getMainLooper())
                    val observer = Snapshot.registerGlobalWriteObserver {
                        handler.post { Snapshot.sendApplyNotifications() }
                    }
                    try {
                        RingVeil.reset()
                        val context = RuntimeEnvironment.getApplication()
                        shadowOf(context.packageManager).addActivityIfNotPresent(
                            ComponentName(context, ComponentActivity::class.java),
                        )
                        Snapshot.sendApplyNotifications()
                        wrapped.evaluate()
                    } finally {
                        observer.dispose()
                    }
                }
            }
        }
    }
}
