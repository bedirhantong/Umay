package com.appvalence.umay.di

import android.content.Context
import com.appvalence.umay.cloudmap.PublishedBuildings
import com.appvalence.umay.cloudmap.PublishedBuildingsStore
import com.appvalence.umay.common.AndroidDeviceCapabilities
import com.appvalence.umay.common.AndroidMotionSensors
import com.appvalence.umay.common.Clock
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.common.MotionSensors
import com.appvalence.umay.common.speechLocale
import com.appvalence.umay.diagnostics.CrashReportStore
import com.appvalence.umay.diagnostics.CrashReports
import com.appvalence.umay.feedback.HapticFeedbackController
import com.appvalence.umay.feedback.HapticPlayer
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.fieldlog.FieldLogFiles
import com.appvalence.umay.fieldlog.FieldLogStore
import com.appvalence.umay.files.BuildingFileReader
import com.appvalence.umay.files.ContentResolverBuildingFileReader
import com.appvalence.umay.navigation.NavigationSessionManager
import com.appvalence.umay.navigation.NavigationSessions
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.settings.SharedPreferencesUserSettings
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.storage.BuildingMapStorage
import com.appvalence.umay.storage.BuildingMapStore
import com.appvalence.umay.survey.ResourceSurveyAnnouncer
import com.appvalence.umay.survey.SurveyAnnouncer
import com.appvalence.umay.survey.ar.ArCoreSurveyCamera
import com.appvalence.umay.survey.ar.ArSurveyCamera
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    // A new one-at-a-time slice per injection: each user gets its own serial lane.
    @Provides
    @SerialDispatcher
    fun serialDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    fun clock(): Clock = Clock(System::currentTimeMillis)

    /**
     * The voice for everything spoken outside a navigation session (Ayarlar, the
     * survey, onboarding, permission explanations): one engine binding for the whole
     * process, rather than a new one — and a new voice load — per screen. A
     * [com.appvalence.umay.navigation.NavigationSession] keeps its own, since it's
     * shut down with the session.
     */
    @Provides
    @Singleton
    fun speaker(@ApplicationContext context: Context, settings: UserSettings): Speaker =
        TtsFeedbackController(context, speechLocale(context), settings.speechRate)

    @Provides
    fun hapticPlayer(@ApplicationContext context: Context): HapticPlayer = HapticFeedbackController(context)

    @Provides
    fun beaconScanner(@ApplicationContext context: Context): BeaconScanner = BleBeaconScanner(context)

    // The remote map source and connectivity come from the distribution flavor's own
    // DistributionModule (app/src/standard, app/src/institution).
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun userSettings(impl: SharedPreferencesUserSettings): UserSettings

    @Binds
    abstract fun buildingMapStore(impl: BuildingMapStorage): BuildingMapStore

    @Binds
    abstract fun fieldLogFiles(impl: FieldLogStore): FieldLogFiles

    @Binds
    abstract fun crashReports(impl: CrashReportStore): CrashReports

    @Binds
    abstract fun publishedBuildings(impl: PublishedBuildingsStore): PublishedBuildings

    @Binds
    abstract fun deviceCapabilities(impl: AndroidDeviceCapabilities): DeviceCapabilities

    @Binds
    abstract fun motionSensors(impl: AndroidMotionSensors): MotionSensors

    @Binds
    abstract fun buildingFileReader(impl: ContentResolverBuildingFileReader): BuildingFileReader

    @Binds
    abstract fun navigationSessions(impl: NavigationSessionManager): NavigationSessions

    @Binds
    abstract fun surveyAnnouncer(impl: ResourceSurveyAnnouncer): SurveyAnnouncer

    @Binds
    abstract fun arSurveyCamera(impl: ArCoreSurveyCamera): ArSurveyCamera
}
