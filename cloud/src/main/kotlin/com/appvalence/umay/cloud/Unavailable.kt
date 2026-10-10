package com.appvalence.umay.cloud

import com.appvalence.umay.coremodel.BuildingMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * For a build with no remote map source at all (the institution build without a
 * server): every call fails as offline, without touching the network. The app
 * doesn't offer the shared pool in such a build; this only keeps the wiring whole.
 */
object UnavailableRemoteBuildingMapRepository : RemoteBuildingMapRepository {
    override suspend fun listPublicBuildings(): Result<List<PublicBuildingSummary>> = Result.remoteFailure(RemoteMapFailure.NoConnection)

    override suspend fun downloadBuildingMap(id: String): Result<BuildingMap> = Result.remoteFailure(RemoteMapFailure.NoConnection)

    override suspend fun publishBuildingMap(map: BuildingMap): Result<String> = Result.remoteFailure(RemoteMapFailure.NoConnection)

    override suspend fun unpublishBuildingMap(id: String): Result<Unit> = Result.remoteFailure(RemoteMapFailure.NoConnection)
}

/** Never online — and never asks Android, which would need the network-state permission. */
object NeverOnlineConnectivityMonitor : ConnectivityMonitor {
    override val isOnline: Flow<Boolean> = flowOf(false)
}
