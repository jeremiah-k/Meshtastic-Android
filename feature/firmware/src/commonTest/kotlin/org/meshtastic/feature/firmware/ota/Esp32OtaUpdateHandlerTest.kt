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
package org.meshtastic.feature.firmware.ota

import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.database.entity.FirmwareRelease
import org.meshtastic.core.di.CoroutineDispatchers
import org.meshtastic.core.model.DeviceHardware
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioController
import org.meshtastic.core.testing.FakeServiceRepository
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.feature.firmware.FirmwareArtifact
import org.meshtastic.feature.firmware.FirmwareFileHandler
import org.meshtastic.feature.firmware.FirmwareRetriever
import org.meshtastic.feature.firmware.FirmwareUpdateState
import org.meshtastic.proto.ClientNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [Esp32OtaUpdateHandler]'s OTA preflight gate — the bounded wait for firmware confirmation between
 * `triggerRebootOta` and tearing down the mesh transport.
 *
 * Each test drives `startUpdate` end-to-end just far enough to observe the preflight outcome:
 * - Confirmed → mesh disconnect (`setDeviceAddress("n")`) is invoked, then the handler proceeds into the BLE transport
 *   phase, which fails fast on the mocked BLE deps. We bound the call with `withTimeoutOrNull` so the post-preflight
 *   BLE-connect retry loop is cancelled before it costs real wall-clock time.
 * - Rejected / Timeout → mesh transport is preserved (no `setDeviceAddress`) and an `Error` state is emitted; the
 *   handler returns cleanly with no exception to chase.
 *
 * The firmware response is simulated by mutating [FakeServiceRepository.clientNotification] from a sibling coroutine
 * scheduled to fire after `triggerRebootOta`. Because baseline is captured BEFORE the trigger (see `performUpdate`), a
 * value written afterward is recognized as the response, not the baseline.
 */
class Esp32OtaUpdateHandlerTest {

    private val release = FirmwareRelease(id = "v1.0", title = "test")
    private val hardware =
        DeviceHardware(hwModelSlug = "HELTEC_V3", platformioTarget = "heltec-v3", architecture = "esp32-s3")
    private val firmwareUri = CommonUri.parse("file:///tmp/firmware.bin")

    private val dispatchers =
        CoroutineDispatchers(
            io = Dispatchers.Unconfined,
            main = Dispatchers.Unconfined,
            default = Dispatchers.Unconfined,
        )

    private fun makeHandler(
        serviceRepository: FakeServiceRepository,
        radioController: FakeRadioController,
        nodeRepository: FakeNodeRepository,
        fileHandler: FirmwareFileHandler,
    ): Esp32OtaUpdateHandler = Esp32OtaUpdateHandler(
        firmwareRetriever = FirmwareRetriever(fileHandler),
        firmwareFileHandler = fileHandler,
        radioController = radioController,
        nodeRepository = nodeRepository,
        serviceRepository = serviceRepository,
        bleScanner = mock(MockMode.autofill),
        bleConnectionFactory = mock(MockMode.autofill),
        dispatchers = dispatchers,
    )

    private fun newFileHandler(): FirmwareFileHandler = mock(MockMode.autofill) {
        everySuspend { readBytes(any()) } returns byteArrayOf(1, 2, 3, 4)
        everySuspend { importFromUri(any()) } returns
            FirmwareArtifact(uri = firmwareUri, fileName = "firmware.bin", isTemporary = false)
    }

    private fun newNodeRepository(): FakeNodeRepository = FakeNodeRepository().apply {
        setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 1, firmwareVersion = "1.0"))
    }

    /** Schedule [block] on a sibling Default dispatcher; cancelled at the end of the test. */
    private fun runSideEffect(block: suspend CoroutineScope.() -> Unit): Job =
        CoroutineScope(Dispatchers.Default).launch { block() }

    /**
     * Shared fixture builder for preflight tests. Schedules a sibling coroutine that emits [confirmationMessage] as a
     * firmware ClientNotification after [CONFIRMATION_DELAY_MS]; pass `confirmationMessage = null` (e.g. Timeout test)
     * to skip the emitter entirely.
     */
    private fun runPreflightTest(
        target: String = BLE_TARGET,
        confirmationMessage: String? = BLE_CONFIRMATION,
        configure: Esp32OtaUpdateHandler.() -> Unit = {},
        runUpdate: suspend PreflightFixture.() -> Unit = {
            handler.startUpdate(release, hardware, target, states::add, firmwareUri)
        },
        assertions: PreflightFixture.() -> Unit,
    ) = runBlocking {
        val serviceRepository = FakeServiceRepository()
        val radioController = FakeRadioController()
        val nodeRepository = newNodeRepository()
        val handler = makeHandler(serviceRepository, radioController, nodeRepository, newFileHandler()).apply(configure)
        val states = mutableListOf<FirmwareUpdateState>()
        val fixture = PreflightFixture(handler = handler, radioController = radioController, states = states)

        val emitter =
            confirmationMessage?.let { msg ->
                runSideEffect {
                    delay(CONFIRMATION_DELAY_MS)
                    serviceRepository.setClientNotification(ClientNotification(message = msg))
                }
            }

        try {
            fixture.runUpdate()
        } finally {
            emitter?.cancel()
        }

        fixture.assertions()
    }

    // ── Confirmed ──────────────────────────────────────────────────────────

    @Test
    fun `Confirmed preflight releases the mesh transport`() = runPreflightTest(
        target = BLE_TARGET,
        confirmationMessage = BLE_CONFIRMATION,
        runUpdate = {
            // Bound the call: we only need to observe the preflight side effect. The post-preflight BLE transport
            // phase would otherwise burn ~30 s of real wall-clock time on retry/delay loops with mocked BLE deps.
            withTimeoutOrNull(3000L) {
                handler.startUpdate(release, hardware, BLE_TARGET, states::add, firmwareUri)
            }
        },
    ) {
        assertEquals("n", radioController.lastSetDeviceAddress, "Confirmed preflight must disconnect mesh service")
    }

    @Test
    fun `Confirmed BLE preflight retries with fresh transport after connection failure`() {
        val events = mutableListOf<String>()
        val transports =
            ArrayDeque<FakeOtaTransport>().apply {
                add(
                    FakeOtaTransport(
                        name = "first",
                        events = events,
                        connectResult = Result.failure(OtaProtocolException.ConnectionFailed("OTA service missing")),
                    ),
                )
                add(FakeOtaTransport(name = "second", events = events))
            }
        runPreflightTest(
            target = BLE_TARGET,
            confirmationMessage = BLE_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                otaTransportRetryDelayMs = 0L
                bleTransportFactoryOverride = { transports.removeFirst() }
            },
        ) {
            assertEquals("n", radioController.lastSetDeviceAddress, "Confirmed preflight must disconnect mesh service")
            assertTrue(events.contains("close:first"), "Failed transport must be closed before retry")
            assertTrue(events.contains("connect:second"), "Retry must create and connect a fresh transport")
            assertTrue(events.indexOf("close:first") < events.indexOf("connect:second"))
            assertTrue(events.contains("close:second"), "Successful transport must be closed after transfer")
            assertTrue(states.any { it is FirmwareUpdateState.Success }, "Later fresh transport should complete update")
        }
    }

    @Test
    fun `Confirmed WiFi preflight waits for OTA service readiness before connecting`() {
        val events = mutableListOf<String>()
        runPreflightTest(
            target = WIFI_TARGET,
            confirmationMessage = WIFI_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                wifiOtaReadinessDelayMs = TEST_WIFI_READINESS_DELAY_MS
                delayFn = { delayMs -> events += "delay:$delayMs" }
                wifiTransportFactoryOverride = { FakeOtaTransport(name = "wifi", events = events) }
            },
        ) {
            assertTrue(
                events.contains("delay:$TEST_WIFI_READINESS_DELAY_MS"),
                "WiFi post-confirm readiness delay must run",
            )
            assertTrue(events.contains("connect:wifi"), "WiFi transport must connect after readiness delay")
            assertTrue(events.indexOf("delay:$TEST_WIFI_READINESS_DELAY_MS") < events.indexOf("connect:wifi"))
            assertTrue(
                states.any { it is FirmwareUpdateState.Success },
                "WiFi transport should connect after readiness delay",
            )
        }
    }

    @Test
    fun `Confirmed WiFi preflight uses UDP discovered address before connecting`() {
        val events = mutableListOf<String>()
        runPreflightTest(
            target = WIFI_TARGET,
            confirmationMessage = WIFI_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                wifiOtaReadinessDelayMs = TEST_WIFI_READINESS_DELAY_MS
                delayFn = { delayMs -> events += "delay:$delayMs" }
                wifiOtaDiscoveryOverride = {
                    events += "discover"
                    DISCOVERED_WIFI_TARGET
                }
                wifiTransportFactoryOverride = { target ->
                    events += "factory:$target"
                    FakeOtaTransport(name = target, events = events)
                }
            },
        ) {
            assertTrue(events.contains("discover"), "WiFi OTA should listen for the loader's DHCP address")
            assertTrue(
                events.contains("factory:$DISCOVERED_WIFI_TARGET"),
                "Discovered DHCP address must replace the pre-reboot address",
            )
            assertTrue(
                events.contains("connect:$DISCOVERED_WIFI_TARGET"),
                "WiFi transport must connect to the discovered address",
            )
            assertTrue(
                events.none { it == "factory:$WIFI_TARGET" || it == "connect:$WIFI_TARGET" },
                "Configured pre-reboot address should not be used after discovery succeeds",
            )
            assertTrue(
                events.none { it == "delay:$TEST_WIFI_READINESS_DELAY_MS" },
                "Discovery timeout window counts as the post-confirm readiness wait",
            )
            assertTrue(states.any { it is FirmwareUpdateState.Success })
        }
    }

    @Test
    fun `Confirmed WiFi preflight falls back to configured address when UDP discovery misses`() {
        val events = mutableListOf<String>()
        runPreflightTest(
            target = WIFI_TARGET,
            confirmationMessage = WIFI_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                wifiOtaReadinessDelayMs = TEST_WIFI_READINESS_DELAY_MS
                delayFn = { delayMs -> events += "delay:$delayMs" }
                wifiOtaDiscoveryOverride = {
                    events += "discover"
                    null
                }
                wifiTransportFactoryOverride = { target ->
                    events += "factory:$target"
                    FakeOtaTransport(name = target, events = events)
                }
            },
        ) {
            assertTrue(events.contains("discover"), "WiFi OTA should attempt UDP discovery before fixed-IP fallback")
            assertTrue(events.contains("factory:$WIFI_TARGET"), "Missing discovery must fall back to configured target")
            assertTrue(events.contains("connect:$WIFI_TARGET"), "Fallback transport must still connect")
            assertTrue(
                events.contains("delay:$TEST_WIFI_READINESS_DELAY_MS"),
                "Discovery miss must still apply the post-confirm readiness margin",
            )
            assertTrue(
                events.indexOf("delay:$TEST_WIFI_READINESS_DELAY_MS") < events.indexOf("connect:$WIFI_TARGET"),
                "Readiness delay must run before connecting",
            )
            assertTrue(states.any { it is FirmwareUpdateState.Success })
        }
    }

    @Test
    fun `Confirmed WiFi preflight retries with fresh transport after connection failure`() {
        val events = mutableListOf<String>()
        val transports =
            ArrayDeque<FakeOtaTransport>().apply {
                add(
                    FakeOtaTransport(
                        name = "wifi-first",
                        events = events,
                        connectResult = Result.failure(OtaProtocolException.ConnectionFailed("Connection refused")),
                    ),
                )
                add(FakeOtaTransport(name = "wifi-second", events = events))
            }
        runPreflightTest(
            target = WIFI_TARGET,
            confirmationMessage = WIFI_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                wifiOtaReadinessDelayMs = 0L
                otaTransportRetryDelayMs = 0L
                wifiTransportFactoryOverride = { transports.removeFirst() }
            },
        ) {
            assertEquals("n", radioController.lastSetDeviceAddress, "Confirmed preflight must disconnect mesh service")
            assertTrue(events.contains("close:wifi-first"), "Failed WiFi transport must be closed before retry")
            assertTrue(events.contains("connect:wifi-second"), "WiFi retry must create and connect a fresh transport")
            assertTrue(events.indexOf("close:wifi-first") < events.indexOf("connect:wifi-second"))
            assertTrue(events.contains("close:wifi-second"), "Successful WiFi transport must be closed after transfer")
            assertTrue(
                states.any { it is FirmwareUpdateState.Success },
                "Later fresh WiFi transport should complete update",
            )
        }
    }

    @Test
    fun `Confirmed WiFi preflight closes failed transports and surfaces connection error after retries`() {
        val events = mutableListOf<String>()
        var attempt = 0
        runPreflightTest(
            target = WIFI_TARGET,
            confirmationMessage = WIFI_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                wifiOtaReadinessDelayMs = 0L
                otaTransportRetryDelayMs = 0L
                wifiTransportFactoryOverride = {
                    attempt += 1
                    FakeOtaTransport(
                        name = "wifi-$attempt",
                        events = events,
                        connectResult = Result.failure(OtaProtocolException.ConnectionFailed("Connection refused")),
                    )
                }
            },
        ) {
            assertEquals("n", radioController.lastSetDeviceAddress, "Confirmed preflight must disconnect mesh service")
            assertEquals(WIFI_CONNECT_ATTEMPTS, attempt, "WiFi should use the full post-confirm retry budget")
            repeat(WIFI_CONNECT_ATTEMPTS) { index ->
                val name = "wifi-${index + 1}"
                assertTrue(events.contains("connect:$name"), "Attempt ${index + 1} must connect a fresh transport")
                assertTrue(events.contains("close:$name"), "Attempt ${index + 1} must close its failed transport")
                assertTrue(events.indexOf("connect:$name") < events.indexOf("close:$name"))
            }
            assertTrue(events.none { it.startsWith("start:") || it.startsWith("stream:") })
            assertIs<FirmwareUpdateState.Error>(states.lastOrNull())
            Unit
        }
    }

    @Test
    fun `Confirmed BLE preflight closes failed transports and surfaces connection error after retries`() {
        val events = mutableListOf<String>()
        var attempt = 0
        runPreflightTest(
            target = BLE_TARGET,
            confirmationMessage = BLE_CONFIRMATION,
            configure = {
                gattReleaseDelayMs = 0L
                otaTransportRetryDelayMs = 0L
                bleTransportFactoryOverride = {
                    attempt += 1
                    FakeOtaTransport(
                        name = "ble-$attempt",
                        events = events,
                        connectResult = Result.failure(OtaProtocolException.ConnectionFailed("GATT unreachable")),
                    )
                }
            },
        ) {
            assertEquals("n", radioController.lastSetDeviceAddress, "Confirmed preflight must disconnect mesh service")
            assertEquals(BLE_CONNECT_ATTEMPTS, attempt, "BLE should use the full post-confirm retry budget")
            repeat(BLE_CONNECT_ATTEMPTS) { index ->
                val name = "ble-${index + 1}"
                assertTrue(events.contains("connect:$name"), "Attempt ${index + 1} must connect a fresh transport")
                assertTrue(events.contains("close:$name"), "Attempt ${index + 1} must close its failed transport")
                assertTrue(events.indexOf("connect:$name") < events.indexOf("close:$name"))
            }
            assertTrue(events.none { it.startsWith("start:") || it.startsWith("stream:") })
            assertIs<FirmwareUpdateState.Error>(states.lastOrNull())
            Unit
        }
    }

    // ── Rejected ───────────────────────────────────────────────────────────

    @Test
    fun `Rejected preflight preserves mesh transport and surfaces Error`() = runPreflightTest(
        // OTA-keyed message that is NOT the canonical "Rebooting to <mode> OTA" success prefix → Rejected.
        confirmationMessage = "Cannot start OTA: OTA Loader partition not found.",
    ) {
        assertNull(radioController.lastSetDeviceAddress, "Rejected preflight must NOT disconnect mesh service")
        assertTrue(states.any { it is FirmwareUpdateState.Error }, "Rejected preflight must emit Error state")
    }

    // ── Timeout ────────────────────────────────────────────────────────────

    @Test
    fun `Timeout preflight preserves mesh transport and surfaces Error`() = runPreflightTest(
        // No emitter (confirmationMessage = null): the firmware never responds, so the overridden short preflight
        // timeout resolves quickly.
        confirmationMessage = null,
        configure = { otaPreflightTimeoutMs = 10L },
    ) {
        assertNull(radioController.lastSetDeviceAddress, "Timeout preflight must NOT disconnect mesh service")
        val terminal = states.lastOrNull()
        assertIs<FirmwareUpdateState.Error>(terminal, "Timeout preflight must emit Error state")
        // kotlin.test's assertIs returns its non-null typed argument on iOS, which would make this test
        // function return FirmwareUpdateState.Error instead of Unit. Explicit Unit terminates the block.
        Unit
    }

    private companion object {
        // Valid BLE MAC target so startUpdate routes through the BLE OTA preflight path.
        const val BLE_TARGET = "AA:BB:CC:DD:EE:FF"
        const val WIFI_TARGET = "192.168.1.33"
        const val DISCOVERED_WIFI_TARGET = "192.168.1.44"
        const val BLE_CONFIRMATION = "Rebooting to BLE OTA"
        const val WIFI_CONFIRMATION = "Rebooting to WiFi OTA"
        const val CONFIRMATION_DELAY_MS = 50L
        const val BLE_CONNECT_ATTEMPTS = 5
        const val WIFI_CONNECT_ATTEMPTS = 10
        const val TEST_WIFI_READINESS_DELAY_MS = 1234L
    }
}

private data class PreflightFixture(
    val handler: Esp32OtaUpdateHandler,
    val radioController: FakeRadioController,
    val states: MutableList<FirmwareUpdateState>,
)

private class FakeOtaTransport(
    private val name: String,
    private val events: MutableList<String>,
    private val connectResult: Result<Unit> = Result.success(Unit),
) : UnifiedOtaProtocol {
    override suspend fun connect(): Result<Unit> {
        events += "connect:$name"
        return connectResult
    }

    override suspend fun startOta(
        sizeBytes: Long,
        sha256Hash: String,
        onHandshakeStatus: suspend (OtaHandshakeStatus) -> Unit,
    ): Result<Unit> {
        events += "start:$name"
        return Result.success(Unit)
    }

    override suspend fun streamFirmware(
        data: ByteArray,
        chunkSize: Int,
        onProgress: suspend (Float) -> Unit,
    ): Result<Unit> {
        events += "stream:$name"
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun close() {
        events += "close:$name"
    }
}
