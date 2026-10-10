package com.appvalence.umay.di

import com.appvalence.umay.cloud.ConnectivityMonitor
import com.appvalence.umay.cloud.NeverOnlineConnectivityMonitor
import com.appvalence.umay.cloud.RemoteBuildingMapRepository
import com.appvalence.umay.cloud.UnavailableRemoteBuildingMapRepository
import com.appvalence.umay.common.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The institution build: nothing leaves the device. There's no internet
 * permission to begin with (see this flavor's AndroidManifest.xml); maps move
 * between phones as files.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    @Provides
    fun distribution(): Distribution = Distribution(sharedMapPool = false)

    @Provides
    fun remoteBuildingMaps(): RemoteBuildingMapRepository = UnavailableRemoteBuildingMapRepository

    @Provides
    fun connectivityMonitor(): ConnectivityMonitor = NeverOnlineConnectivityMonitor
}
