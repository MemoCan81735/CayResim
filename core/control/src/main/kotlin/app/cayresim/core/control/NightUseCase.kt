package app.cayresim.core.control

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.pure.NightPath
import app.cayresim.core.boundary.ProcessResult
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.NightPlan
import app.cayresim.core.pure.Clock
import app.cayresim.core.boundary.DebugOptionsBoundary
import app.cayresim.core.boundary.Frame
import app.cayresim.core.boundary.MotionSample
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.boundary.SeriesArchiveBoundary
import app.cayresim.core.boundary.SeriesArchiveSessionBoundary
import app.cayresim.core.boundary.SeriesArchiveSnapshot
import app.cayresim.core.pure.NightSeries
import app.cayresim.core.boundary.ComputeDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.flow.transformWhile
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Eigener Nacht-Kern statt Samsungs Night-Extension, wenn es dunkel ist (Bildqualitaets-Dossier N-P1).
 *
 * Ablauf: die ersten Bilder laufen noch mit der Automatik und dienen nur der Messung; daraus folgt eine
 * feste Belichtung (bis 1/10 s, ISO bis 3200). Nach einer kurzen Einschwingzeit werden die Bilder einzeln
 * an die Verarbeitung gereicht. Die vorherigen manuellen Werte werden immer wiederhergestellt.
 */
class NightUseCase @Inject constructor(
    private val camera: CameraBoundary,
    private val manual: ManualCameraBoundary,
    private val frames: FrameBoundary,
    private val processing: ProcessingBoundary,
    /** Wahl aus dem Selbsttest; ohne sie immer der 8-Bit-Weg. */
    private val nightPath: NightPathBoundary? = null,
    /** Misst die Dauer fuer den Hinweis (R2, R27); ohne Uhr keine Dauer. */
    private val clock: Clock? = null,
    /** S-011: Ablage der Nachtserie zum Nachmessen; nur bei eingeschaltetem [debug]-Schalter. */
    private val archive: SeriesArchiveBoundary? = null,
    private val debug: DebugOptionsBoundary? = null,
    /** S-011: Lage waehrend der Serie (R29). */
    private val sensors: MotionSensorBoundary? = null,
    /** S-011: Helligkeit rechnen und Lage sammeln abseits des Aufrufers (R16, R18); null in Tests. */
    @ComputeDispatcher private val compute: CoroutineDispatcher? = null,
) {
    /** Eigener Kern bei Dunkelheit oder wenn noch keine Messung vorliegt; bei gemessen hellem Licht Samsungs Modus. */
    fun shouldUseOwn(): Boolean {
        val l = camera.state.value.light
        return NightPlan.isDark(l?.exposureNs, l?.iso) != false
    }

    /** Nur gemessene Dunkelheit (fuer die Automatik; ohne Messung wird normal fotografiert). */
    fun isDark(): Boolean {
        val l = camera.state.value.light
        return NightPlan.isDark(l?.exposureNs, l?.iso) == true
    }

    /** [count]: feste Bildzahl (Tests); null = nach der Messung ([NightPlan.framesFor], S-006 bis 72 Bilder). */
    suspend operator fun invoke(count: Int? = null): StackOutcome {
        val caps = manual.manualCapabilities.value
        val expRange = caps?.exposureRangeNanos; val isoRange = caps?.isoRange
        if (caps?.canExpose != true || expRange == null || isoRange == null)
            return StackOutcome.Failed(StackOutcome.Stage.COLLECT, "NO_MANUAL_EXPOSURE")
        val start = clock?.nowMillis()
        if (nightPath?.load()?.path == NightPath.RAW) rawNight(count ?: NightPlan.FRAMES, expRange, isoRange, start)?.let { return it }
        val before = manual.manualState.value
        var plan: NightPlan.Exposure? = null
        var meter: app.cayresim.core.boundary.LightSnapshot? = null
        // Strom fuer die groesste Serie anfordern, nach der Messung auf die gewaehlte Zahl kuerzen
        var chosen = count ?: NightPlan.FRAMES_DEEP
        // S-011: Serie zum Nachmessen; scheitert das Speichern, geht das Nachtbild trotzdem vor (R14)
        val recording = debug?.saveNightSeries?.value == true && archive != null
        var rec: SeriesRecorder? = null
        try {
            // im try: auch eine beim Abbruch gerade geoeffnete Datei wird im finally geloescht (Zweitpruefung S-011, B4)
            if (recording) rec = SeriesRecorder(archive?.open(SERIES_PREFIX))
            val series = rec
            val stream = frames.frames(METER + SETTLE + (count ?: NightPlan.FRAMES_DEEP))
                .withIndex()
                .onEach { (i, _) ->
                    if (i == METER - 1) {
                        val l = camera.state.value.light
                        meter = l
                        if (count == null) chosen = NightPlan.framesFor(l?.exposureNs, l?.iso)
                        // Ohne Messung: wie bei voller Dunkelheit
                        val p = NightPlan.plan(l?.exposureNs ?: FALLBACK_NS, l?.iso ?: isoRange.last, expRange.last, isoRange.first, isoRange.last)
                        // Befund M6: nur melden, was wirklich eingestellt wurde
                        plan = if (manual.setExposure(p.exposureNs, p.iso)) p else null
                    }
                }
                // genau bis zum letzten gewaehlten Bild (kein zusaetzliches), hoechstens [CAPTURE_BUDGET_MS] (R27)
                .transformWhile { emit(it); it.index < METER + SETTLE + chosen - 1 && !overBudget(start) }
                .filter { it.index >= METER + SETTLE }
                .map { it.value }
                // S-011: nie warten; kommt der Speicher nicht nach, wird das Bild nur ausgelassen (Zweitpruefung S-011, B2)
                .onEach { f -> series?.offer(f, chosen) }
                .onCompletion { series?.endInput() }
            val samples = ArrayList<MotionSample>()
            val ctx = compute ?: EmptyCoroutineContext
            val r = coroutineScope {
                val active = series?.session != null
                // Lage nur beim Speichern der Serie; sofort anmelden, abgemeldet wird beim Abbruch des Sammlers (R17, R29).
                // Ein Sensorfehler beendet nur die Lage, nie das Nachtbild (R14).
                val motion = if (active && sensors != null)
                    launch(ctx, start = CoroutineStart.UNDISPATCHED) { sensors.samples().catch { }.collect { samples += it } } else null
                val writer = if (active) launch(ctx) { series?.drain() } else null
                processing.night(stream).also { series?.endInput(); writer?.join(); motion?.cancelAndJoin() }
            }
            return when (r) {
                is ProcessResult.Saved -> {
                    val used = r.night?.used ?: chosen
                    var report = NightReport(plan?.exposureNs, plan?.iso, used, r.night?.dropped ?: 0, r.night?.gain ?: 1f, durationMs = since(start),
                        meterExposureNs = meter?.exposureNs, meterIso = meter?.iso, shakePx = r.night?.maxShake,
                        shakeMeasurable = r.night?.shakeMeasurable ?: true, diagnosis = r.night?.diagnosis)
                    if (series != null) {
                        val info = NightSeries.Info(series.width, series.height, series.rotation, plan?.exposureNs, plan?.iso, meter?.exposureNs, meter?.iso,
                            report.durationMs, used, report.dropped, report.gain, r.night?.maxShake ?: 0, series.timestamps, series.archived, r.night?.diagnosis)
                        val records = r.night?.records.orEmpty()
                        // Texte (einige tausend Zeilen Lage) abseits des Aufrufers bauen (Zweitpruefung S-011, B9)
                        val (meta, lage) = withContext(ctx) { NightSeries.metaJson(info, records) to SweepUseCase.csv(samples) }
                        val saved = series.finish(meta, lage)
                        // S-011 Nachtrag (Arslan, 10.10.): eine Serie je Einschalten; nach einem Fehler bleibt er an
                        if (saved != null) debug?.setSaveNightSeries(false)
                        report = report.copy(seriesName = saved?.name, seriesBytes = saved?.bytes, seriesFailed = saved == null)
                    }
                    StackOutcome.Saved(r.uri, used, used < chosen * 3 / 4, report)
                }
                is ProcessResult.Failed -> StackOutcome.Failed(StackOutcome.Stage.PROCESS, r.reason.name)
            }
        } finally {
            // Auch bei Abbruch (Zurueck, Home): sonst bliebe die Nachtbelichtung im Singleton-Adapter haengen
            withContext(NonCancellable) {
                try {
                    manual.setExposure(before.exposureNanos, before.iso)
                } finally {
                    // S-011: eine nicht fertige Serie wird geloescht (R17), auch wenn das Zuruecksetzen scheitert
                    rec?.abortIfOpen()
                }
            }
        }
    }

    /**
     * RAW-Nachtweg: Belichtung aus der letzten Messung der Automatik, die RAW-Sitzung setzt sie selbst.
     * null = RAW gescheitert, der Aufrufer nimmt den 8-Bit-Weg (R14).
     */
    private suspend fun rawNight(count: Int, expRange: LongRange, isoRange: IntRange, start: Long?): StackOutcome? {
        val l = camera.state.value.light
        val p = NightPlan.plan(l?.exposureNs ?: FALLBACK_NS, l?.iso ?: isoRange.last, expRange.last, isoRange.first, isoRange.last)
        return when (val r = processing.nightRaw(frames.rawFrames(count, p.exposureNs, p.iso))) {
            is ProcessResult.Saved -> {
                val used = r.night?.used ?: count
                StackOutcome.Saved(r.uri, used, used < count * 3 / 4,
                    NightReport(p.exposureNs, p.iso, used, r.night?.dropped ?: 0, r.night?.gain ?: 1f, raw = true, durationMs = since(start),
                        meterExposureNs = l?.exposureNs, meterIso = l?.iso, shakePx = r.night?.maxShake,
                        shakeMeasurable = r.night?.shakeMeasurable ?: true))
            }
            is ProcessResult.Failed -> null
        }
    }

    /** Nie negativ, auch wenn die Systemuhr zurueckspringt. */
    /** S-006: die Aufnahme endet spaetestens nach [CAPTURE_BUDGET_MS], damit die Dauer unter 10 s bleibt. */
    private fun overBudget(start: Long?): Boolean = start != null && clock != null && clock.nowMillis() - start >= CAPTURE_BUDGET_MS

    private fun since(start: Long?): Long? = if (start == null || clock == null) null else (clock.nowMillis() - start).coerceAtLeast(0)

    /**
     * S-011: legt jedes Bild der Serie ab (Helligkeit, dazu erstes, mittleres und letztes abgelegtes in Farbe).
     * [offer] laeuft im Sammler der Verarbeitung und wartet nie: eine Warteschlange mit [SERIES_QUEUE] Plaetzen fuehrt
     * zu [drain], das parallel schreibt. Ist sie voll, wird das Bild ausgelassen und in `meta.json` so vermerkt; die
     * Serie selbst wird nicht gebremst (Zweitpruefung S-011, B2). Nach dem ersten Fehler wird nichts mehr geschrieben;
     * am Ende wird die halbe Datei geloescht. Unterwegs sind hoechstens [SERIES_QUEUE] + 1 Bilder (Verweise, R19).
     */
    private class SeriesRecorder(val session: SeriesArchiveSessionBoundary?) {
        @Volatile var ok = session != null; private set
        var finished = false; private set
        val timestamps = ArrayList<Long?>()
        val archived = ArrayList<Boolean>()
        var width = 0; var height = 0; var rotation = 0
        private var started = false
        private val queue = Channel<Item>(SERIES_QUEUE)

        private class Item(val index: Int, val frame: Frame, val mid: Boolean)

        fun offer(f: Frame, chosen: Int) {
            if (session == null) return
            if (!started) { started = true; width = f.width; height = f.height; rotation = f.rotationDegrees }
            // dieselbe Regel wie die Verarbeitung: Bilder anderer Groesse zaehlen nicht, sonst passen die Nummern nicht
            // mehr zu den Eintraegen der Zusammenfuehrung (Zweitpruefung S-011, B8)
            if (f.width != width || f.height != height || f.rgb.size != width * height * 3) return
            val i = timestamps.size
            timestamps += f.timestampNs
            // Bilder sind Kopien des Adapters und werden nicht veraendert: Verweis statt Kopie (R19)
            archived += ok && queue.trySend(Item(i, f, i == chosen / 2)).isSuccess
        }

        /** Keine weiteren Bilder; das zuletzt abgelegte geht noch in Farbe ins Archiv. Mehrfach aufrufbar. */
        fun endInput() { queue.close() }

        suspend fun drain() {
            val s = session ?: return
            var last: Frame? = null
            for (item in queue) {
                if (!ok) continue
                last = item.frame
                ok = write {
                    val f = item.frame
                    s.put(NightSeries.lumaName(item.index), NightSeries.luma(f.rgb, f.width * f.height)) &&
                        (item.index != 0 || s.put(NightSeries.RGB_FIRST, f.rgb)) && (!item.mid || s.put(NightSeries.RGB_MID, f.rgb))
                }
            }
            // gleich nach dem Ende des Stroms, nicht erst nach dem Zusammenrechnen (Speicherspitze, R19)
            val l = last ?: return
            if (ok) ok = write { s.put(NightSeries.RGB_LAST, l.rgb) }
        }

        suspend fun finish(meta: String, lage: String): SeriesArchiveSnapshot? {
            val s = session ?: return null
            ok = ok && write { s.put(NightSeries.META, meta.toByteArray(Charsets.UTF_8)) && s.put(NightSeries.LAGE, lage.toByteArray(Charsets.UTF_8)) }
            if (!ok) return null
            val snap = s.finish()
            finished = snap != null
            return snap
        }

        suspend fun abortIfOpen() { queue.close(); if (!finished) session?.abort() }

        /** Ein Fehler beim Ablegen beendet nur das Speichern, nie das Nachtbild (R14). */
        private inline fun write(block: () -> Boolean): Boolean =
            runCatching(block).onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
    }

    companion object {
        const val SERIES_PREFIX = "Nachtserie"
        /** S-011: Plaetze der Warteschlange zum Speicher; mehr Bilder warten nicht (R19). */
        const val SERIES_QUEUE = 2
        /** Bilder mit Automatik zum Messen. */
        const val METER = 4
        /** S-006: Zeitbudget der Aufnahme; danach rechnet der Kern mit den vorhandenen Bildern (Grenze 10 s mit Speichern). */
        const val CAPTURE_BUDGET_MS = 8_500L
        /** Bilder nach dem Umstellen, bis die neue Belichtung wirkt. */
        const val SETTLE = 3
        private const val FALLBACK_NS = 66_666_666L
    }
}
