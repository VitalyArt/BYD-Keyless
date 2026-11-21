package com.vitalyart.bydkeyless.quick

import com.vitalyart.bydkeyless.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickCommandExecutorTest {
    private val profile = VehicleProfile(
        vin = "TESTVIN",
        macAddress = "00:00:00:00:00:00",
        digitalKey = "key",
        keyNumber = 1,
        keyValidTo = null,
        capabilities = setOf("1005", "1006", "1020"),
    )
    private val token = WatchToken(1, "user", "imei", "enc", "sign", 1, "en", "TESTVIN", "owner", null)

    @Test fun routesOnlyFixedQuickActions() {
        assertEquals(VehicleCommand.LOCK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_LOCK))
        assertEquals(VehicleCommand.UNLOCK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_UNLOCK))
        assertEquals(VehicleCommand.OPEN_TRUNK, QuickCommandContract.commandForAction(QuickCommandContract.ACTION_OPEN_TRUNK))
        assertNull(QuickCommandContract.commandForAction("arbitrary"))
    }

    @Test fun choosesContextualDoorActionOnlyForKnownState() {
        assertEquals(VehicleCommand.UNLOCK, QuickCommandContract.contextualDoorCommand(DoorState.LOCKED))
        assertEquals(VehicleCommand.LOCK, QuickCommandContract.contextualDoorCommand(DoorState.UNLOCKED))
        assertNull(QuickCommandContract.contextualDoorCommand(DoorState.UNKNOWN))
    }

    @Test fun connectsBeforeExecuting() = runTest {
        val controller = FakeController(connects = true)
        val executor = executor(controller)

        assertEquals(CommandResult.Success, executor.execute(VehicleCommand.UNLOCK))
        assertEquals(1, controller.connectCount)
        assertTrue(controller.prioritizedDiscovery)
        assertEquals(listOf(VehicleCommand.UNLOCK), controller.executed)
        assertEquals(QuickCommandPhase.SUCCESS, executor.state.value.phase)
    }

    @Test fun timesOutWithoutSendingCommand() = runTest {
        val controller = FakeController(connects = false)
        val executor = executor(controller, timeout = 100)

        assertEquals(CommandResult.Rejected(CommandError.CONNECTION_TIMEOUT), executor.execute(VehicleCommand.LOCK))
        assertEquals(100L, testScheduler.currentTime)
        assertTrue(controller.executed.isEmpty())
    }

    @Test fun firstTapWaitsThroughReconnectAndIgnoresRepeatedTap() = runTest {
        val controller = FakeController(connects = false)
        controller.connectionState.value = BleConnectionState.SCANNING
        val executor = executor(controller)
        val firstTap = async { executor.execute(VehicleCommand.UNLOCK) }
        runCurrent()
        assertTrue(controller.prioritizedDiscovery)
        assertEquals(QuickCommandPhase.CONNECTING, executor.state.value.phase)
        assertEquals(CommandResult.Rejected(CommandError.COMMAND_BUSY), executor.execute(VehicleCommand.UNLOCK))

        controller.connectionState.value = BleConnectionState.AUTHENTICATING
        runCurrent()
        controller.connectionState.value = BleConnectionState.DISCONNECTED
        advanceTimeBy(300)
        assertTrue(controller.executed.isEmpty())

        controller.connectionState.value = BleConnectionState.READY
        assertEquals(CommandResult.Success, firstTap.await())
        assertEquals(1, controller.connectCount)
        assertEquals(listOf(VehicleCommand.UNLOCK), controller.executed)
    }

    @Test fun firstTapWaitsForInProgressAuthentication() = runTest {
        val controller = FakeController(connects = false)
        controller.connectionState.value = BleConnectionState.AUTHENTICATING
        val executor = executor(controller)
        val firstTap = async { executor.execute(VehicleCommand.UNLOCK) }
        runCurrent()
        assertTrue(controller.executed.isEmpty())
        assertEquals(BleConnectionState.AUTHENTICATING, controller.connectionState.value)
        controller.connectionState.value = BleConnectionState.READY
        assertEquals(CommandResult.Success, firstTap.await())
        assertEquals(listOf(VehicleCommand.UNLOCK), controller.executed)
    }

    @Test fun terminalConnectionErrorIsReportedWithoutSendingCommand() = runTest {
        val controller = FakeController(connects = false)
        val executor = executor(controller)
        val firstTap = async { executor.execute(VehicleCommand.UNLOCK) }
        runCurrent()
        controller.lastError = CommandError.BLUETOOTH_OFF
        controller.connectionState.value = BleConnectionState.ERROR
        assertEquals(CommandResult.Rejected(CommandError.BLUETOOTH_OFF), firstTap.await())
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(controller.executed.isEmpty())
    }

    @Test fun rejectsParallelCommand() = runTest {
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val controller = FakeController(connects = true, executeBlock = {
            entered.complete(Unit)
            release.await()
        })
        val executor = executor(controller)

        val first = async { executor.execute(VehicleCommand.UNLOCK) }
        entered.await()
        assertEquals(CommandResult.Rejected(CommandError.COMMAND_BUSY), executor.execute(VehicleCommand.LOCK))
        release.complete(Unit)
        assertEquals(CommandResult.Success, first.await())
    }

    @Test fun unknownCommandOutcomeIsNotResent() = runTest {
        val failure = CommandResult.Failure(CommandError.VEHICLE_TIMEOUT)
        val controller = FakeController(connects = true, commandResult = failure)
        val executor = executor(controller)

        assertEquals(failure, executor.execute(VehicleCommand.UNLOCK))
        assertEquals(listOf(VehicleCommand.UNLOCK), controller.executed)
        assertEquals(QuickCommandPhase.ERROR, executor.state.value.phase)
    }

    @Test fun rejectsMissingCapabilityBeforeConnecting() = runTest {
        val controller = FakeController(connects = true)
        val limited = profile.copy(capabilities = setOf("1005"))
        val executor = QuickCommandExecutor({ token to limited }, controller, { null }, 100)

        assertEquals(CommandResult.Rejected(CommandError.CAPABILITY_NOT_CONFIRMED), executor.execute(VehicleCommand.OPEN_TRUNK))
        assertEquals(0, controller.connectCount)
    }

    private fun executor(controller: FakeController, timeout: Long = 1_000) =
        QuickCommandExecutor({ token to profile }, controller, { null }, timeout)

    private class FakeController(
        private val connects: Boolean,
        private val commandResult: CommandResult = CommandResult.Success,
        private val executeBlock: suspend () -> Unit = {},
    ) : BleVehicleController {
        override val connectionState = MutableStateFlow(BleConnectionState.IDLE)
        override val telemetry = MutableStateFlow(VehicleTelemetry())
        override var lastError: CommandError? = null
        var connectCount = 0
        var prioritizedDiscovery = false
        val executed = mutableListOf<VehicleCommand>()

        override suspend fun connect(profile: VehicleProfile, prioritizeDiscovery: Boolean) {
            connectCount++
            prioritizedDiscovery = prioritizeDiscovery
            if (connects) connectionState.value = BleConnectionState.READY
            else if (connectionState.value == BleConnectionState.IDLE) connectionState.value = BleConnectionState.CONNECTING
        }

        override suspend fun disconnect() { connectionState.value = BleConnectionState.IDLE }

        override suspend fun execute(command: VehicleCommand, experimentalEnabled: Boolean): CommandResult {
            executed += command
            executeBlock()
            return commandResult
        }
    }
}
