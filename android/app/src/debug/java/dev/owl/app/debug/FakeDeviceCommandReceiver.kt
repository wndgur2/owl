package dev.owl.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import dev.owl.app.DEBUG_DEMO_STT
import dev.owl.app.appContainer
import dev.owl.app.stt.TranscribeWorker
import dev.owl.app.sync.SyncTrigger
import kotlinx.coroutines.launch
import java.io.File

/**
 * `adb shell am broadcast -a dev.owl.app.debug.FAKE -p dev.owl.app --es cmd disconnect --el arg 40`
 * Commands are listed in [dev.owl.app.device.fake.FakeDemo.command]; `sync` starts a pass,
 * `register` registers the fake pendant. QA over the real BLE path (docs/qa/) adds two that work
 * with any pendant: `import-locations` (GPS fixes from `files/qa_locations.csv`, written by
 * `tools/fake_owl.py --week --locations-out`) and `demo-stt` (arg 1/0: demo sentences instead of
 * "텍스트 추출 중").
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
                // Past days' fixes: the emulator's `geo fix` can only stamp "now".
                "import-locations" -> {
                    val file = File(context.filesDir, "qa_locations.csv")
                    var n = 0
                    for (line in file.readLines()) {
                        val f = line.split(',')
                        if (f.size < 3) continue
                        c.locationSink.record(f[0].toLong(), f[1].toDouble(), f[2].toDouble(), f.getOrNull(3)?.toFloatOrNull())
                        n++
                    }
                    c.resolvePlaces()
                    "imported $n fixes"
                }
                "demo-stt" -> {
                    val on = (arg ?: 1L) != 0L
                    c.debugPrefs.edit(commit = true) { putBoolean(DEBUG_DEMO_STT, on) }
                    if (on) TranscribeWorker.enqueue(context)
                    "demo stt ${if (on) "on" else "off"}"
                }
                else -> c.fakeCommand(cmd, arg)
            }
            Log.i("OwlFake", "$cmd → $result")
        }
    }
}
