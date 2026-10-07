package app.cayresim.shell.di

import app.cayresim.core.boundary.CameraBoundary
import app.cayresim.core.boundary.CameraDispatcher
import app.cayresim.core.boundary.GpuDispatcher
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.MediaBoundary
import app.cayresim.core.camera.CameraXCameraAdapter
import app.cayresim.core.data.MediaStoreMediaAdapter
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
    @Binds @Singleton abstract fun media(a: MediaStoreMediaAdapter): MediaBoundary
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides @CameraDispatcher fun cameraDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @Singleton @GpuDispatcher
    fun gpuDispatcher(): CoroutineDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "cayresim-gpu") }.asCoroutineDispatcher()
    @Provides fun clock(): Clock = Clock { System.currentTimeMillis() }
}
