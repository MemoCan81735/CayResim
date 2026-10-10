package app.cayresim.shell.di

import app.cayresim.core.audio.AudioRecordMicrophoneAdapter
import app.cayresim.core.sensors.MotionSensorAdapter
import app.cayresim.core.boundary.AudioFileBoundary
import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.MicrophoneBoundary
import app.cayresim.core.boundary.MotionSensorBoundary
import app.cayresim.core.data.MediaStoreAudioFileAdapter
import app.cayresim.core.data.InMemoryDebugOptionsAdapter
import app.cayresim.core.data.MediaStoreSeriesAdapter
import app.cayresim.core.boundary.DebugOptionsBoundary
import app.cayresim.core.boundary.SeriesArchiveBoundary
import app.cayresim.core.boundary.CameraDispatcher
import app.cayresim.core.boundary.GpuDispatcher
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.MainDispatcher
import app.cayresim.core.boundary.ComputeDispatcher
import app.cayresim.core.boundary.FrameBoundary
import app.cayresim.core.boundary.ManualCameraBoundary
import app.cayresim.core.boundary.MediaBoundary
import app.cayresim.core.camera.CameraXCameraAdapter
import app.cayresim.core.data.MediaStoreMediaAdapter
import app.cayresim.core.data.FileSelfTestJournalAdapter
import app.cayresim.core.boundary.SelfTestJournalBoundary
import app.cayresim.core.boundary.NightPathBoundary
import app.cayresim.core.data.FileNightPathAdapter
import app.cayresim.core.data.series.RoomSeriesAdapter
import app.cayresim.core.processing.GlProcessingAdapter
import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.ProcessingBoundary
import app.cayresim.core.pure.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Singleton

/** Einzige Stelle mit Verdrahtung (R10) und mit Dispatchers.* (R16). */
@Module
@InstallIn(SingletonComponent::class)
abstract class BoundaryModule {
    @Binds @Singleton abstract fun camera(a: CameraXCameraAdapter): CameraBoundary
    @Binds @Singleton abstract fun frames(a: CameraXCameraAdapter): FrameBoundary
    @Binds @Singleton abstract fun manual(a: CameraXCameraAdapter): ManualCameraBoundary
    @Binds @Singleton abstract fun media(a: MediaStoreMediaAdapter): MediaBoundary
    @Binds @Singleton abstract fun series(a: RoomSeriesAdapter): SeriesBoundary
    @Binds @Singleton abstract fun processing(a: GlProcessingAdapter): ProcessingBoundary
    @Binds @Singleton abstract fun selfTestJournal(a: FileSelfTestJournalAdapter): SelfTestJournalBoundary
    @Binds @Singleton abstract fun nightPath(a: FileNightPathAdapter): NightPathBoundary
    @Binds @Singleton abstract fun microphone(a: AudioRecordMicrophoneAdapter): MicrophoneBoundary
    @Binds @Singleton abstract fun audioFiles(a: MediaStoreAudioFileAdapter): AudioFileBoundary
    @Binds @Singleton abstract fun seriesArchive(a: MediaStoreSeriesAdapter): SeriesArchiveBoundary
    @Binds @Singleton abstract fun debugOptions(a: InMemoryDebugOptionsAdapter): DebugOptionsBoundary
    @Binds @Singleton abstract fun motion(a: MotionSensorAdapter): MotionSensorBoundary
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides @CameraDispatcher fun cameraDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
    @Provides @ComputeDispatcher fun computeDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @Singleton @GpuDispatcher
    fun gpuDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "cayresim-gpu") }.asCoroutineDispatcher()
    @Provides fun clock(): Clock = Clock { System.currentTimeMillis() }
}
