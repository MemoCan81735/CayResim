package app.cayresim

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.cayresim.core.audio.AudioRecordMicrophoneAdapter
import app.cayresim.core.boundary.CameraStatus
import app.cayresim.core.camera.CameraXCameraAdapter
import app.cayresim.core.control.SoundCameraEvent
import app.cayresim.core.control.SoundCameraUseCase
import app.cayresim.core.control.SoundImageUseCase
import app.cayresim.core.pure.Clock
import app.cayresim.core.sensors.MotionSensorAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S-021 K13: Tonstrom und Kamera gleichzeitig mit den echten Adaptern auf dem Emulator. Das virtuelle Mikrofon liefert
 * meist Stille und die Lagesensoren stehen still; geprueft werden Start, Stopp und Freigabe, nicht das Urteil.
 */
@RunWith(AndroidJUnit4::class)
class SoundWithCameraDeviceTest {
    @get:Rule val permission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val camera = CameraXCameraAdapter(context, Dispatchers.Main.immediate)
    private val mic = AudioRecordMicrophoneAdapter(context, Dispatchers.IO)
    private val sensors = MotionSensorAdapter(context)
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private val useCase = SoundCameraUseCase(SoundImageUseCase(mic, sensors, clock, Dispatchers.Default), camera, clock, Dispatchers.Default)

    @After fun tearDown() = runBlocking { camera.stop() }

    @Test fun s021TonstromMitKamera() = runBlocking {
        withTimeout(150_000) {
            // fuenf Messungen mit kurzen Phasen: zehnmal Kamera an und aus, waehrend der Ton laeuft
            val reports = (0 until 5).map {
                val events = useCase.run(phaseMillis = 600).toList()
                assertEquals(6, events.count { it is SoundCameraEvent.Phase })
                val r = assertIs<SoundCameraEvent.Done>(events.last()).report
                // Werte vor den Pruefungen ausgeben, damit sie auch im roten Lauf im Protokoll stehen
                println("S-021 K13 Lauf $it: Dauer ${r.durationMillis} ms, Kamerafehler ${r.cameraError}, Bilder ${r.cameraFrames}, Bilder je Sekunde " +
                    "${r.phases.map { p -> p.previewFps }}, Bildwinkel ${r.viewWideDeg} / ${r.viewHighDeg}, Lage ${r.motionAvailable}, " +
                    "Urteile ${r.verdicts.map { v -> v.verdict }}, Aufnahme ${r.phases.map { p -> p.failure }}")
                assertEquals(CameraStatus.IDLE, camera.state.value.status, "Kamera nach der Messung gestoppt")
                assertNull(camera.state.value.previewFps, "Bildzaehlung nach dem Stopp zurueckgesetzt")
                assertEquals(0, mic.openRecordings, "Mikrofon nach der Messung frei")
                assertEquals(0, sensors.activeListeners, "Sensoren nach der Messung abgemeldet")
                r
            }
            for (r in reports) {
                assertEquals(6, r.phases.size)
                // sechs Phasen zu 0,6 s; der Rest ist Start und Stopp der Kamera (auf dem Geraet: 24 s plus dieser Rest, K7)
                assertTrue(r.durationMillis - 6 * 600 <= 12_000, "Start und Stopp der Kamera dauern ${r.durationMillis - 6 * 600} ms")
                assertNull(r.cameraError, "Kamera gestartet")
                assertTrue(r.cameraFrames, "Kamera liefert ohne Sucher Bilder")
                assertTrue(r.phases.filter { it.cameraOn }.all { (it.previewFps ?: 0) > 0 }, "Bilder je Sekunde gemessen")
                assertTrue(r.phases.filter { !it.cameraOn }.all { it.previewFps == null })
                // Bildwinkel gesetzt und plausibel, oder ausdruecklich unbekannt
                val wide = r.viewWideDeg; val high = r.viewHighDeg
                assertTrue((wide == null && high == null) || (wide != null && high != null && wide in 20.0..150.0 && high in 10.0..wide), "Bildwinkel $wide / $high")
            }
        }
    }
}
