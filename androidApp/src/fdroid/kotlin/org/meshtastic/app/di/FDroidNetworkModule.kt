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
     * Provides an [ApiService] implementation for F-Droid builds that returns empty results instead of making network calls.
     *
     * Returning empty results avoids creating exceptions and stack traces on every refresh.
     *
     * @return An [ApiService] that returns an empty device-hardware list and a default [NetworkFirmwareReleases] instance.
     */
    @Single
    fun provideApiService(): ApiService = object : ApiService {
        /**
 * Provides no device hardware from the network for F-Droid builds.
 *
 * @return An empty list of device hardware.
 */
override suspend fun getDeviceHardware(): List<NetworkDeviceHardware> = emptyList()

        /**
 * Provides default firmware release data without making network calls.
 *
 * This stub implementation for F-Droid builds returns a default [NetworkFirmwareReleases] instance
 * instead of performing any network requests.
 *
 * @return A default [NetworkFirmwareReleases] instance.
 */
override suspend fun getFirmwareReleases(): NetworkFirmwareReleases = NetworkFirmwareReleases()
    }
}
