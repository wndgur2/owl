package dev.owl.app.location

/** Where LocationReceiver puts fixes (implemented by the data layer). */
fun interface LocationSink {
    suspend fun record(timeMs: Long, latitude: Double, longitude: Double, accuracyM: Float?)
}
