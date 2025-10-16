# BLE Stabilization Work Log

This document tracks the investigation and implementation of fixes for the Meshtastic-Android BLE stack instability.

## Phase 1: Investigation and Planning

- [X] Read `CONTRIBUTING.md` to understand project standards.
- [ ] Explore the current `main` branch codebase to verify the analysis.
- [ ] Check out `v2.6.30` to understand the last known good state.
- [ ] Finalize the implementation plan based on the delta analysis.

## Phase 2: Implementation

*   **Step 1: Explicit Transport State Machine**
    *   [ ] Create `TransportStateMachine.kt`.
    *   [ ] Define states: `Idle`, `Scanning`, `Connecting`, `Discovering`, `Subscribing`, `Ready`, `Degraded`, `Recovering`, `Disconnecting`.
    *   [ ] Integrate state machine into `RadioInterfaceService` and `BluetoothInterface`.
*   **Step 2: Restore Correct Connection Semantics**
    *   [ ] Modify `ConnectionState.isConnected()` to return `this == CONNECTED`.
    *   [ ] Update UI in `ConnectionsScreen.kt` to react to the new, more granular state machine.
*   **Step 3: Liveness + Keep-Alive**
    *   [ ] Implement `BluetoothInterface.keepAlive()`.
    *   [ ] Schedule `keepAlive()` from `RadioInterfaceService` only when in `Ready` state.
    *   [ ] On failure, transition state machine to `Degraded`.
*   **Step 4: Teardown & Reconnect Policy**
    *   [ ] Refactor `BluetoothInterface.close()` for ordered teardown.
    *   [ ] Implement jittered, capped exponential backoff for retries.
*   **Step 5: UI/UX Enhancements**
    *   [ ] Add explicit "Disconnect" and "Retry" buttons.
    *   [ ] Display connection state accurately.
    *   [ ] Surface "Stale Connection" warnings.
*   **Step 6: Telemetry**
    *   [ ] Add structured logs for connection events, state transitions, and failures.
    *   [ ] Add counters for key metrics.

## Phase 3: Testing and Validation

- [ ] Write unit tests for the new state machine and connection logic.
- [ ] Run existing project tests to check for regressions.
- [ ] Perform pre-commit checks.
- [ ] Submit PR.