package dev.owl.app.device

import kotlin.math.roundToInt

/**
 * Battery % and time left, computed on the phone (protocol §2.1: the curve lives in the app so a
 * battery swap does not need new firmware).
 *
 * The curve is a generic single-cell LiPo open-circuit curve, squeezed so that 0 % is the
 * pendant's own cut-off (3.5 V System OFF, decisions.md #8/#20). Calibrate against the real
 * TW502030 in M5.
 */
object Battery {
    private val curve: List<Pair<Int, Int>> = listOf(
        4200 to 100, 4150 to 95, 4110 to 90, 4080 to 85, 4020 to 80, 3980 to 75, 3950 to 70,
        3910 to 65, 3870 to 60, 3850 to 55, 3840 to 50, 3820 to 45, 3800 to 40, 3790 to 35,
        3770 to 30, 3750 to 25, 3730 to 20, 3710 to 15, 3690 to 10, 3610 to 5, 3500 to 0,
    )

    const val CAPACITY_MAH = 250.0

    /** Average draw used until there is enough history (docs/architecture.md: 2–5 mA estimated). */
    const val NOMINAL_CURRENT_MA = 3.5

    fun percent(mv: Int): Int {
        if (mv >= curve.first().first) return 100
        if (mv <= curve.last().first) return 0
        for (i in 0 until curve.size - 1) {
            val (hiMv, hiPct) = curve[i]
            val (loMv, loPct) = curve[i + 1]
            if (mv in loMv..hiMv) {
                return (loPct + (hiPct - loPct) * (mv - loMv).toDouble() / (hiMv - loMv)).roundToInt()
            }
        }
        return 0
    }

    /**
     * Hours left. [history] is (time ms, mV) oldest first. Uses the discharge rate over the
     * latest stretch without charging (no rise of more than 3 %) if it spans at least an hour and
     * dropped at least 2 %; otherwise the nominal average current.
     */
    fun hoursLeft(currentMv: Int, history: List<Pair<Long, Int>>): Int {
        val pct = percent(currentMv)
        if (pct == 0) return 0
        val run = ArrayList<Pair<Long, Int>>()
        for ((t, mv) in history) {
            val p = percent(mv)
            if (run.isNotEmpty() && p > run.last().second + 3) run.clear() // charged in between
            run += t to p
        }
        if (run.size >= 2) {
            val hours = (run.last().first - run.first().first) / 3_600_000.0
            val drop = run.first().second - run.last().second
            if (hours >= 1.0 && drop >= 2) return (pct / (drop / hours)).roundToInt()
        }
        return (CAPACITY_MAH * pct / 100.0 / NOMINAL_CURRENT_MA).roundToInt()
    }
}
