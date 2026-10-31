package dev.owl.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.owl.app.appContainer
import dev.owl.app.sync.SyncTrigger
import kotlinx.coroutines.launch

/**
 * `adb shell am broadcast -a dev.owl.app.debug.FAKE -p dev.owl.app --es cmd disconnect --el arg 40`
 * Commands are listed in [dev.owl.app.device.fake.FakeDemo.command]; `sync` starts a pass,
 * `register` registers the fake pendant.
 * Results go to logcat (tag OwlFake). Runs in the app scope because a sync outlives a receiver.
 */
class FakeDeviceCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = if (intent.hasExtra("arg")) intent.getLongExtra("arg", 0) else null
        val c = context.appContainer
        c.appScope.launch {
            val result = when (cmd) {
                "sync" -> "sync ok=${c.syncRunner.sync(SyncTrigger.MANUAL)}"
                // Registers the fake pendant without going through the UI (scripted checks).
                "register" -> c.deviceRegistrar.registerFakeDevice().also { c.onAppOpened() }.toString()
                else -> c.fakeCommand(cmd, arg)
            }
            Log.i("OwlFake", "$cmd → $result")
        }
    }
}
