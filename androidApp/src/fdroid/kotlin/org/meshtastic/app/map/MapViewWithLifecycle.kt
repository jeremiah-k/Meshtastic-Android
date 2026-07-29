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
package org.meshtastic.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

private const val MIN_ZOOM_LEVEL = 1.5
private const val MAX_ZOOM_LEVEL = 20.0
private const val DEFAULT_ZOOM_LEVEL = 15.0

private class MapViewReference(var current: MapView? = null)

@Suppress("MagicNumber")
@Composable
fun rememberMapViewWithLifecycle(
    box: BoundingBox,
    tileSource: ITileSource = TileSourceFactory.DEFAULT_TILE_SOURCE,
): MapView {
    val zoom =
        if (box.requiredZoomLevel().isFinite()) {
            (box.requiredZoomLevel() - 0.5).coerceAtLeast(MIN_ZOOM_LEVEL)
        } else {
            DEFAULT_ZOOM_LEVEL
        }
    val center = GeoPoint(box.centerLatitude, box.centerLongitude)
    return rememberMapViewWithLifecycle(zoomLevel = zoom, mapCenter = center, tileSource = tileSource)
}

@Suppress("LongMethod")
@Composable
internal fun rememberMapViewWithLifecycle(
    zoomLevel: Double = MIN_ZOOM_LEVEL,
    mapCenter: GeoPoint = GeoPoint(0.0, 0.0),
    tileSource: ITileSource = TileSourceFactory.DEFAULT_TILE_SOURCE,
): MapView {
    var savedZoom by rememberSaveable { mutableDoubleStateOf(zoomLevel) }
    var savedCenter by
        rememberSaveable(
            stateSaver =
            Saver(
                save = { mapOf("latitude" to it.latitude, "longitude" to it.longitude) },
                restore = { GeoPoint(it["latitude"] ?: 0.0, it["longitude"] ?: .0) },
            ),
        ) {
            mutableStateOf(mapCenter)
        }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val mapViewReference = remember { MapViewReference() }
    val mapView =
        remember(context, lifecycle) {
            // Keyed remember creates the replacement before the old DisposableEffect is disposed. Read the live
            // viewport directly so an unsaved pan or zoom survives a context or lifecycle-owner replacement.
            val previousMapView = mapViewReference.current?.takeIf { it.width > 0 && it.height > 0 }
            val initialCenter = previousMapView?.projection?.currentCenter ?: savedCenter
            val initialZoom = previousMapView?.zoomLevelDouble ?: savedZoom
            MapView(context)
                .apply {
                    clipToOutline = true

                    setTileSource(tileSource)
                    isVerticalMapRepetitionEnabled = false // disables map repetition
                    setMultiTouchControls(true)
                    val bounds = overlayManager.tilesOverlay.bounds // bounds scrollable map
                    setScrollableAreaLimitLatitude(bounds.actualNorth, bounds.actualSouth, 0)
                    // scales the map tiles to the display density of the screen
                    isTilesScaledToDpi = true
                    // sets the minimum zoom level (the furthest out you can zoom)
                    minZoomLevel = MIN_ZOOM_LEVEL
                    maxZoomLevel = MAX_ZOOM_LEVEL
                    // Disables default +/- button for zooming
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)

                    controller.setZoom(initialZoom)
                    controller.setCenter(initialCenter)
                }
                .also { mapViewReference.current = it }
        }
    LaunchedEffect(mapView, tileSource) { mapView.setTileSource(tileSource) }
    DisposableEffect(mapView, lifecycle) {
        var isResumed = false

        fun saveViewport() {
            if (mapView.width > 0 && mapView.height > 0) {
                savedCenter = mapView.projection.currentCenter
                savedZoom = mapView.zoomLevelDouble
            }
        }

        fun resumeMap() {
            if (!isResumed) {
                mapView.onResume()
                isResumed = true
            }
        }

        fun pauseMap() {
            if (isResumed) {
                mapView.onPause()
                isResumed = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> pauseMap()
                Lifecycle.Event.ON_RESUME -> resumeMap()
                Lifecycle.Event.ON_STOP -> saveViewport()
                else -> {}
            }
        }

        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeMap()

        onDispose {
            saveViewport()
            lifecycle.removeObserver(observer)
            pauseMap()
            mapView.onDetach()
            if (mapViewReference.current === mapView) mapViewReference.current = null
        }
    }
    return mapView
}
