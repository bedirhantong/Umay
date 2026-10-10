package com.appvalence.umay.di

import android.content.Context
import com.appvalence.umay.BuildConfig
import com.appvalence.umay.cloud.AndroidConnectivityMonitor
import com.appvalence.umay.cloud.ConnectivityMonitor
import com.appvalence.umay.cloud.HttpBuildingMapRepository
import com.appvalence.umay.cloud.NeverOnlineConnectivityMonitor
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.cloud.UnavailableRemoteBuildingMapRepository
import com.appvalence.umay.common.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The institution build: nothing leaves the institution. Without a map server
 * (none given when it was built) there's no internet permission to begin with
 * and maps move between phones as files; with one, "Dosyalar" shows the maps
 * on that server, and only that host can be reached (see app/build.gradle.kts).
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    private val hasServer: Boolean get() = BuildConfig.INSTITUTION_SERVER_URL.isNotEmpty()

    @Provides
    fun distribution(): Distribution = Distribution(sharedMapPool = hasServer)

    @Provides
    @Singleton
    fun remoteBuildingMaps(): RemoteBuildingMapRepository = if (hasServer) {
        HttpBuildingMapRepository(
            baseUrl = BuildConfig.INSTITUTION_SERVER_URL,
            readToken = BuildConfig.INSTITUTION_READ_TOKEN.ifEmpty { null },
            // Only staff phones are built with it; visitors' phones browse and download.
            publishToken = BuildConfig.INSTITUTION_PUBLISH_TOKEN.ifEmpty { null },
        )
    } else {
        UnavailableRemoteBuildingMapRepository
    }

    @Provides
    @Singleton
    fun connectivityMonitor(@ApplicationContext context: Context): ConnectivityMonitor =
        // The institution's Wi-Fi may well have no way out to the internet; it only needs to reach the server.
        if (hasServer) AndroidConnectivityMonitor(context, requireValidated = false) else NeverOnlineConnectivityMonitor
}
