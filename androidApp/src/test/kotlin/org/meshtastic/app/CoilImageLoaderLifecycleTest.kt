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
package org.meshtastic.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CoilImageLoaderLifecycleTest {

    private lateinit var expectedImageLoader: ImageLoader

    @Before
    fun setUp() {
        stopKoin()
        val application = ApplicationProvider.getApplicationContext<Application>()
        expectedImageLoader = ImageLoader.Builder(application).build()
        startKoin {
            androidContext(application)
            modules(module { single<ImageLoader> { expectedImageLoader } })
        }
    }

    @After
    fun tearDown() {
        expectedImageLoader.shutdown()
        stopKoin()
    }

    @Test
    fun applicationProvidesConfiguredKoinImageLoader() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val factory: SingletonImageLoader.Factory = MeshUtilApplication()

        assertSame(expectedImageLoader, factory.newImageLoader(application))
    }
}
