This PR addresses long-standing Bluetooth/BLE instability issues in Meshtastic-Android. The changes are focused on stabilizing the connection stack by introducing a more robust state machine, correcting UI state representation, and improving reconnect and keep-alive logic.

### Changes

-   **Introduced an Explicit Transport State Machine:**
    -   Created a new `TransportState` enum (`IDLE`, `CONNECTING`, `READY`, etc.) to track the granular state of the BLE connection.
    -   Integrated this state machine into `BluetoothInterface.kt`, which now emits these states throughout the connection lifecycle.
    -   `RadioInterfaceService.kt` now observes this new state.

-   **Refactored ConnectionState and UI Logic:**
    -   Modified `ConnectionState.isConnected()` to only return `true` when the state is `CONNECTED`, fixing the bug where the UI would incorrectly show a connected state.
    -   Updated `ConnectionsScreen.kt` to use the new `TransportState` to provide more accurate and descriptive connection status messages to the user.

-   **Implemented and Activated BLE Keep-Alive:**
    -   Added a periodic keep-alive job to `RadioInterfaceService.kt` that triggers when the connection is `READY`.
    -   The `keepAlive()` method in `BluetoothInterface.kt` now performs a characteristic read to verify the connection's liveness. If the read fails, it triggers the reconnect logic.

-   **Improved Reconnect Backoff Policy:**
    -   Replaced the previous exponential backoff with a jittered, capped exponential backoff in `BluetoothInterface.kt`. This prevents long, unresponsive reconnection attempts.
    -   Added a `Mutex` to the reconnect logic to prevent concurrent reconnection attempts.

-   **Refined Teardown Logic:**
    -   The `close()` method in `BluetoothInterface.kt` now unsubscribes from GATT notifications before closing the connection, making the teardown process more reliable.