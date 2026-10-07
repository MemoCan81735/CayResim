package app.cayresim.core.boundary

import javax.inject.Qualifier

/** Feste, injizierte Dispatcher (R16). Belegt werden sie nur im DI-Modul von :app. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class CameraDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class GpuDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
