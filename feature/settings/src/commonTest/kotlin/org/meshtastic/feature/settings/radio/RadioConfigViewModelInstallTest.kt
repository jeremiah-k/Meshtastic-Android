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
package org.meshtastic.feature.settings.radio

import androidx.lifecycle.viewModelScope
import dev.mokkery.MockMode
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.meshtastic.core.common.util.CommonUri
import org.meshtastic.core.domain.usecase.settings.AdminActionsUseCase
import org.meshtastic.core.domain.usecase.settings.ExportProfileUseCase
import org.meshtastic.core.domain.usecase.settings.ImportProfileUseCase
import org.meshtastic.core.domain.usecase.settings.ImportSecurityConfigUseCase
import org.meshtastic.core.domain.usecase.settings.InstallProfileUseCase
import org.meshtastic.core.domain.usecase.settings.ProcessRadioResponseUseCase
import org.meshtastic.core.domain.usecase.settings.ProfileInstallOutcome
import org.meshtastic.core.domain.usecase.settings.ProfileInstallProgress
import org.meshtastic.core.domain.usecase.settings.RadioConfigUseCase
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.MyNodeInfo
import org.meshtastic.core.model.Node
import org.meshtastic.core.repository.AnalyticsPrefs
import org.meshtastic.core.repository.FileService
import org.meshtastic.core.repository.HomoglyphPrefs
import org.meshtastic.core.repository.LocationRepository
import org.meshtastic.core.repository.LocationService
import org.meshtastic.core.repository.MapConsentPrefs
import org.meshtastic.core.repository.MqttManager
import org.meshtastic.core.repository.NodeRestartTracker
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.SecurityKeyBackupStore
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.testing.FakeLockdownCoordinator
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.DeviceProfile
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.LocalModuleConfig
import org.meshtastic.proto.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Staged device-profile install coverage: progress state, concurrency guard, cancellation, and the import-error
 * contract. Scaffolding mirrors the main VM test file but with the install use-case fakes wired.
 */
class RadioConfigViewModelInstallTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val radioConfigRepository: RadioConfigRepository = mock(MockMode.autofill)
    private val packetRepository: PacketRepository = mock(MockMode.autofill)
    private val serviceRepository: ServiceRepository = mock(MockMode.autofill)
    private val nodeRepository = FakeNodeRepository()
    private val locationRepository: LocationRepository = mock(MockMode.autofill)
    private val mapConsentPrefs: MapConsentPrefs = mock(MockMode.autofill)
    private val analyticsPrefs: AnalyticsPrefs = mock(MockMode.autofill)
    private val analytics: PlatformAnalytics = mock(MockMode.autofill)
    private val homoglyphEncodingPrefs: HomoglyphPrefs = mock(MockMode.autofill)

    private val importProfileUseCase: ImportProfileUseCase = mock(MockMode.autofill)
    private val exportProfileUseCase: ExportProfileUseCase = mock(MockMode.autofill)
    private val importSecurityConfigUseCase: ImportSecurityConfigUseCase = mock(MockMode.autofill)
    private val installProfileUseCase: InstallProfileUseCase = mock(MockMode.autofill)
    private val radioConfigUseCase: RadioConfigUseCase = mock(MockMode.autofill)
    private val adminActionsUseCase: AdminActionsUseCase = mock(MockMode.autofill)
    private val processRadioResponseUseCase: ProcessRadioResponseUseCase = mock(MockMode.autofill)
    private val locationService: LocationService = mock(MockMode.autofill)
    private val fileService: FileService = mock(MockMode.autofill)
    private val mqttManager: MqttManager = mock(MockMode.autofill)
    private val uiPrefs: UiPrefs = mock(MockMode.autofill)
    private val securityKeyBackupStore: SecurityKeyBackupStore = mock(MockMode.autofill)
    private val snackbarManager: SnackbarManager = mock(MockMode.autofill)
    private val trackerScope = CoroutineScope(SupervisorJob() + testDispatcher)
    private val nodeRestartTracker = NodeRestartTracker(trackerScope)

    /**
     * A `viewModelScope` is not a child of `runTest`, so work still in flight when a test ends would resume on
     * `Dispatchers.Main` after [Dispatchers.resetMain] and fail an unrelated later test. Every ViewModel is tracked
     * here so [tearDown] can cancel it.
     */
    private val createdViewModels = mutableListOf<RadioConfigViewModel>()

    private lateinit var serviceConnectionState: MutableStateFlow<ConnectionState>
    private lateinit var viewModel: RadioConfigViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { radioConfigRepository.deviceProfileFlow } returns MutableStateFlow(DeviceProfile())
        every { radioConfigRepository.localConfigFlow } returns MutableStateFlow(LocalConfig())
        every { radioConfigRepository.channelSetFlow } returns MutableStateFlow(ChannelSet())
        every { radioConfigRepository.moduleConfigFlow } returns MutableStateFlow(LocalModuleConfig())
        every { radioConfigRepository.deviceUIConfigFlow } returns MutableStateFlow(null)
        every { radioConfigRepository.fileManifestFlow } returns MutableStateFlow(emptyList())
        every { radioConfigRepository.loraRegionPresetMapFlow } returns MutableStateFlow(null)

        every { analyticsPrefs.analyticsAllowed } returns MutableStateFlow(false)
        every { homoglyphEncodingPrefs.homoglyphEncodingEnabled } returns MutableStateFlow(false)

        every { serviceRepository.meshPacketFlow } returns MutableSharedFlow()
        serviceConnectionState = MutableStateFlow(ConnectionState.Connected)
        every { serviceRepository.connectionState } returns serviceConnectionState

        every { mqttManager.mqttConnectionState } returns
            MutableStateFlow(org.meshtastic.core.model.MqttConnectionState.Inactive)
        every { mqttManager.proxyActive } returns MutableStateFlow(false)

        every { uiPrefs.showQuickChat } returns MutableStateFlow(false)

        viewModel = createViewModel()
    }

    @AfterTest
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        trackerScope.cancel()
        Dispatchers.resetMain()
    }

    private fun createViewModel(destNum: Int? = null) = RadioConfigViewModel(
        destNum = destNum,
        radioConfigRepository = radioConfigRepository,
        packetRepository = packetRepository,
        serviceRepository = serviceRepository,
        nodeRepository = nodeRepository,
        locationRepository = locationRepository,
        mapConsentPrefs = mapConsentPrefs,
        analyticsPrefs = analyticsPrefs,
        homoglyphEncodingPrefs = homoglyphEncodingPrefs,
        importProfileUseCase = importProfileUseCase,
        exportProfileUseCase = exportProfileUseCase,
        importSecurityConfigUseCase = importSecurityConfigUseCase,
        securityKeyBackupStore = securityKeyBackupStore,
        snackbarManager = snackbarManager,
        nodeRestartTracker = nodeRestartTracker,
        installProfileUseCase = installProfileUseCase,
        radioConfigUseCase = radioConfigUseCase,
        adminActionsUseCase = adminActionsUseCase,
        processRadioResponseUseCase = processRadioResponseUseCase,
        locationService = locationService,
        fileService = fileService,
        mqttManager = mqttManager,
        lockdownCoordinator = FakeLockdownCoordinator(),
        analytics = analytics,
    )
        .also { createdViewModels += it }

    @Test
    fun `cancelProfileInstall cancels the active restore and returns to idle`() = runTest {
        val node = Node(num = 123, user = User(id = "!123"))
        nodeRepository.setNodes(listOf(node))
        viewModel = createViewModel()
        val installStarted = CompletableDeferred<Unit>()
        var resultCallbackInvoked = false
        everySuspend { installProfileUseCase(any(), any(), any(), any(), any()) } calls
            {
                installStarted.complete(Unit)
                awaitCancellation()
            }

        viewModel.installProfile(DeviceProfile()) { resultCallbackInvoked = true }
        installStarted.await()
        viewModel.cancelProfileInstall()
        advanceUntilIdle()

        assertEquals(ProfileInstallState.Idle, viewModel.profileInstallState.value)
        assertFalse(resultCallbackInvoked, "cancellation is not an installation result")
        verifySuspend(exactly(1)) { installProfileUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `importProfile reports decoder failure exactly once`() = runTest {
        everySuspend { fileService.read(any(), any()) } calls
            { args ->
                val block = args.arg<suspend (okio.BufferedSource) -> Unit>(1)
                block(okio.Buffer().writeUtf8("not a profile"))
                true
            }
        val results = mutableListOf<DeviceProfile>()

        viewModel.importProfile(CommonUri.parse("content://test/bad.cfg"), results::add)
        advanceUntilIdle()

        assertTrue(results.isEmpty())
    }

    @Test
    fun `importProfile reports unreadable file instead of silently dropping result`() = runTest {
        everySuspend { fileService.read(any(), any()) } returns false
        var imported: DeviceProfile? = null

        viewModel.importProfile(CommonUri.parse("content://test/missing.cfg")) { imported = it }
        advanceUntilIdle()

        assertNull(imported)
    }

    @Test
    fun `installProfile binds owner context at invocation time`() = runTest {
        val originalNode = Node(num = 123, user = User(id = "!123", long_name = "Original"))
        val replacementNode = Node(num = 123, user = User(id = "!123", long_name = "Replacement"))
        nodeRepository.setNodes(listOf(originalNode))
        viewModel = createViewModel()
        runCurrent()
        val profile = DeviceProfile(long_name = "Updated")
        everySuspend { installProfileUseCase(any(), any(), any(), any(), any()) } returns
            ProfileInstallOutcome.Completed
        viewModel.installProfile(profile)
        nodeRepository.setNodes(listOf(replacementNode))
        advanceUntilIdle()

        verifySuspend { installProfileUseCase(123, profile, originalNode.user, true, any()) }
    }

    @Test
    fun `installProfile exposes preparing and staged progress until completion`() = runTest {
        val node = Node(num = 123, user = User(id = "!123"))
        nodeRepository.setNodes(listOf(node))
        viewModel = createViewModel()
        val allowProgress = CompletableDeferred<Unit>()
        val releaseInstall = CompletableDeferred<Unit>()
        everySuspend { installProfileUseCase(any(), any(), any(), any(), any()) } calls
            { args ->
                allowProgress.await()
                args.arg<(ProfileInstallProgress) -> Unit>(4)(ProfileInstallProgress(2, 4))
                releaseInstall.await()
                ProfileInstallOutcome.Completed
            }

        viewModel.installProfile(DeviceProfile())
        assertEquals(ProfileInstallState.Preparing, viewModel.profileInstallState.value)
        allowProgress.complete(Unit)
        runCurrent()
        assertEquals(ProfileInstallState.Installing(2, 4), viewModel.profileInstallState.value)

        releaseInstall.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfileInstallState.Idle, viewModel.profileInstallState.value)
    }

    @Test
    fun `installProfile rejects a second install while the first is active`() = runTest {
        val node = Node(num = 123, user = User(id = "!123"))
        nodeRepository.setNodes(listOf(node))
        viewModel = createViewModel()
        val releaseInstall = CompletableDeferred<Unit>()
        everySuspend { installProfileUseCase(any(), any(), any(), any(), any()) } calls
            {
                releaseInstall.await()
                ProfileInstallOutcome.Completed
            }

        viewModel.installProfile(DeviceProfile())
        everySuspend { installProfileUseCase(any(), any(), any(), any(), any()) } calls
            {
                releaseInstall.await()
                ProfileInstallOutcome.Completed
            }
        var secondResult: Result<Unit>? = null
        viewModel.installProfile(DeviceProfile()) { secondResult = it }

        assertFalse(assertNotNull(secondResult).isSuccess)
        verifySuspend(exactly(1)) { installProfileUseCase(any(), any(), any(), any(), any()) }
        releaseInstall.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `installProfile reports missing destination`() = runTest {
        viewModel = createViewModel()
        var result: Result<Unit>? = null

        viewModel.installProfile(DeviceProfile()) { result = it }

        assertFalse(assertNotNull(result).isSuccess)
        verifySuspend(exactly(0)) { installProfileUseCase(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `installProfile reports staged restore failure`() = runTest {
        val node = Node(num = 123, user = User(id = "!123"))
        val profile = DeviceProfile()
        nodeRepository.setMyNodeInfo(myNodeInfo(myNodeNum = 123))
        nodeRepository.setNodes(listOf(node))
        serviceConnectionState.value = ConnectionState.Connected
        viewModel = createViewModel()
        runCurrent()
        everySuspend { installProfileUseCase(123, profile, node.user, true, any()) } throws
            IllegalStateException("restore interrupted")
        var result: Result<Unit>? = null

        viewModel.installProfile(profile) { result = it }
        advanceUntilIdle()

        verifySuspend { installProfileUseCase(123, profile, node.user, true, any()) }
        assertFalse(assertNotNull(result).isSuccess)
    }
}

/** Minimal [MyNodeInfo] so the view model resolves the local node number and the local destination. */
private fun myNodeInfo(myNodeNum: Int) = MyNodeInfo(
    myNodeNum = myNodeNum,
    hasGPS = false,
    model = null,
    firmwareVersion = null,
    couldUpdate = false,
    shouldUpdate = false,
    currentPacketId = 0,
    messageTimeoutMsec = 0,
    minAppVersion = 0,
    maxChannels = 8,
    hasWifi = false,
    channelUtilization = 0f,
    airUtilTx = 0f,
    deviceId = null,
)
