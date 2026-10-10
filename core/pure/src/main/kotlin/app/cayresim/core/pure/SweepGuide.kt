package app.cayresim.core.pure

import kotlin.math.ceil

/**
 * S-014: fester Ablauf der Schwenk-Messung, damit jeder Lauf gleich geschwenkt wird und die Anleitung zu jeder Sekunde
 * die passende Bewegung zeigt. Zeiten ab Beginn der Aufnahme; die Klopfphase entspricht `SweepUseCase.TAP_SECONDS`.
 */
object SweepGuide {
    /** TAP zweimal auf die Rueckseite tippen; YAW links und rechts drehen; ROLL auf hochkant kippen und zurueck;
     *  PITCH nach oben und unten; CIRCLE langsam kreisen. */
    enum class Move { TAP, YAW, ROLL, PITCH, CIRCLE }

    data class Phase(val move: Move, val fromSeconds: Double, val toSeconds: Double)

    /** Aktuelle Bewegung, volle Sekunden bis zum Wechsel (aufgerundet) und die naechste Bewegung (null am Ende). */
    data class Step(val move: Move, val secondsLeft: Int, val next: Move?)

    const val TAP_SECONDS = 3.0
    const val TOTAL_SECONDS = 25.0

    val PHASES: List<Phase> = listOf(
        Phase(Move.TAP, 0.0, TAP_SECONDS),
        Phase(Move.YAW, TAP_SECONDS, 9.0),
        Phase(Move.ROLL, 9.0, 15.0),
        Phase(Move.PITCH, 15.0, 21.0),
        Phase(Move.CIRCLE, 21.0, TOTAL_SECONDS),
    )

    fun at(elapsedSeconds: Double): Step? {
        val i = PHASES.indexOfFirst { elapsedSeconds >= it.fromSeconds && elapsedSeconds < it.toSeconds }
        if (i < 0) return null
        val p = PHASES[i]
        return Step(p.move, ceil(p.toSeconds - elapsedSeconds - 1e-9).toInt().coerceAtLeast(1), PHASES.getOrNull(i + 1)?.move)
    }
}
