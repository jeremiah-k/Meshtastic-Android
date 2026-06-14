/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.app.di

import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.meshtastic.core.model.NetworkDeviceHardware
import org.meshtastic.core.model.NetworkFirmwareReleases
import org.meshtastic.core.network.service.ApiService

@Module
class FDroidNetworkModule {

    /**
     * F-Droid builds intentionally avoid network calls to the Meshtastic API.
     *
     * Returning empty results (a valid [NetworkFirmwareReleases] with default empty lists) avoids exception creation
     * and stack-trace filling on every refresh. Empty API responses do NOT trigger bundled-JSON fallback —
     * [FirmwareReleaseRepositoryImpl]'s `singleFlightRefresh()` inserts returned lists directly (no-op for empty), and
     * bundled JSON seeding is driven by `ensureSeeded()` only when the local DB/cache is empty, not as a consequence of
     * empty network results.
     */
    @Single
    fun provideApiService(): ApiService = object : ApiService {
        override suspend fun getDeviceHardware(): List<NetworkDeviceHardware> = emptyList()

        override suspend fun getFirmwareReleases(): NetworkFirmwareReleases = NetworkFirmwareReleases()
    }
}
