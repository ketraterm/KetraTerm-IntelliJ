/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.intellij.ui

import com.intellij.codeWithMe.ClientId
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.intellij.services.IntellijClipboardClient
import io.github.ketraterm.intellij.services.IntellijClipboardSession
import io.github.ketraterm.intellij.support.MockConnector
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingClipboardReader
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.seconds

/** Real blocked workers and explicit admission establish responsiveness and ordering without sleeps. */
class IntellijTerminalClipboardHandlerTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testReadsUseWorkersAndCapturedClientWhileWritesUseTheIdeEdt() =
        Fixture().use { f ->
            val ambient = onEdt { ClientId.current }
            assertEquals("original", await(f.start { f.handler.readTextAsync() }))
            assertEquals("primary", await(f.start { f.handler.readPrimarySelectionTextAsync() }))
            await(f.start { f.handler.copyTextAsync("copied") })
            assertEquals(listOf("read", "primary", "write:copied"), f.native.events)
            assertEquals("copied", f.native.text)
            onEdt { assertEquals("Returning from clipboard work must restore the caller context", ambient, ClientId.current) }
        }

    fun testBlockedReadKeepsEdtResponsiveAndOrdersOtherPaneCopyAndPrimaryRead() =
        Fixture().use { f ->
            val gate = f.blockRead()
            val read = f.start { f.handler.readTextAsync() }
            gate.awaitEntered()
            val otherPane = f.handler()
            val copy = f.start { otherPane.copyTextAsync("next") }
            val primary = f.start { otherPane.readPrimarySelectionTextAsync() }
            onEdt {
                var clicked = false
                JButton().apply { addActionListener { clicked = true } }.doClick(0)
                assertTrue(clicked)
                assertEquals(listOf("read"), f.native.events)
                assertFalse(copy.isCompleted)
                assertFalse(primary.isCompleted)
            }
            gate.release.countDown()
            assertEquals("original", await(read))
            await(copy)
            assertEquals("primary", await(primary))
            assertEquals(listOf("read", "read-returned", "write:next", "primary"), f.native.events)
        }

    fun testCancellingBlockedReadRetainsItsSlotUntilNativeReturnAndSkipsCancelledCopy() =
        Fixture().use { f ->
            val gate = f.blockRead()
            val read = f.start { f.handler.readTextAsync() }
            gate.awaitEntered()
            onEdt { read.cancel() }
            val obsolete = f.start { f.handler.copyTextAsync("obsolete") }
            onEdt { obsolete.cancel() }
            val copy = f.start { f.handler.copyTextAsync("current") }
            onEdt {
                assertTrue(read.isCancelled)
                assertEquals(listOf("read"), f.native.events)
                assertFalse(copy.isCompleted)
            }
            gate.release.countDown()
            assertCancelled(read)
            assertCancelled(obsolete)
            await(copy)
            assertEquals(listOf("read", "read-returned", "write:current"), f.native.events)
        }

    fun testReplacingEitherOwnerRejectsLateReadAndQueuedWrite() {
        for (applicationOwner in listOf(false, true)) {
            Fixture().use { f ->
                val gate = f.blockRead()
                val read = f.start { f.handler.readTextAsync() }
                gate.awaitEntered()
                val copy = f.start { f.handler.copyTextAsync("must-not-write") }
                val owner = if (applicationOwner) f.application else f.projectOwner
                onEdt { owner.current.set(f.owner(owner.id).client) }
                gate.release.countDown()
                assertCancelled(read)
                assertCancelled(copy)
                assertEquals("original", f.native.text)
                assertEquals(listOf("read", "read-returned"), f.native.events)
            }
        }
    }

    fun testReplacingOwnerAfterNativeReturnRejectsDelivery() =
        Fixture().use { f ->
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
            try {
                val gate = f.blockRead()
                val read = scope.async { f.handler.readTextAsync() }
                scheduler.runCurrent()
                gate.awaitEntered()
                gate.release.countDown()
                f.drainWorker()
                assertFalse("Native return must still await its caller's dispatcher", read.isCompleted)
                f.application.current.set(f.owner(f.application.id).client)
                scheduler.runCurrent()
                assertCancelled(read)
                assertEquals(listOf("read", "read-returned"), f.native.events)
            } finally {
                scope.cancel()
            }
        }

    fun testBindingDisposalCancelsQueuedWriteWithoutCancellingOtherPanes() =
        Fixture().use { f ->
            val gate = f.blockRead()
            val read = f.start { f.handler.readTextAsync() }
            gate.awaitEntered()
            val binding = f.binding()
            val write = onEdt { binding.write("disposed-pane") }
            // This EDT barrier follows the posted write's admission to the occupied client gate.
            onEdt {
                assertFalse(write.isCompleted)
                Disposer.dispose(binding)
                assertTrue(write.isCancelled)
                assertTrue(f.application.client.isAlive)
                assertTrue(f.projectOwner.client.isAlive)
            }
            val otherPane = f.start { f.handler().copyTextAsync("other-pane") }
            gate.release.countDown()
            assertEquals("original", await(read))
            join(write)
            await(otherPane)
            assertEquals(listOf("read", "read-returned", "write:other-pane"), f.native.events)
        }

    fun testDisposingEitherOwnerRejectsQueuedAccessAndLateResults() {
        for (applicationOwner in listOf(false, true)) {
            Fixture().use { f ->
                val gate = f.blockRead()
                val read = f.start { f.handler.readTextAsync() }
                gate.awaitEntered()
                val copy = f.start { f.handler.copyTextAsync("must-not-write") }
                onEdt { Disposer.dispose(if (applicationOwner) f.application.client else f.projectOwner.client) }
                gate.release.countDown()
                assertCancelled(read)
                assertCancelled(copy)
                assertEquals(listOf("read", "read-returned"), f.native.events)
            }
        }
    }

    fun testNativeFailureReleasesTheGateForFollowingOperations() =
        Fixture().use { f ->
            f.native.onRead = { throw IOException("Clipboard unavailable") }
            try {
                await(f.start { f.handler.readTextAsync() })
                fail("Expected native clipboard failure")
            } catch (expected: IOException) {
                assertEquals("Clipboard unavailable", expected.message)
            }
            await(f.start { f.handler.copyTextAsync("recovered") })
            assertEquals(listOf("read", "write:recovered"), f.native.events)
        }

    fun testUserPasteStaysResponsiveAndDiscardsResultAfterTerminalDisposal() =
        Fixture().use { f ->
            val gate = f.blockRead()
            val connector = MockConnector()
            val session = TerminalSession.create(TerminalBuffers.create(20, 3), connector)
            val bound = CompletableFuture<Unit>()
            val terminal =
                onEdt {
                    SwingTerminal(
                        settingsProvider = { SwingSettings() },
                        hostServices = SwingHostServices.create { it.clipboardHandler = f.handler },
                    ).also {
                        it.bind(session) { failure ->
                            if (failure == null) bound.complete(Unit) else bound.completeExceptionally(failure)
                        }
                    }
                }
            try {
                bound.get(10, TimeUnit.SECONDS)
                session.start(20, 3)
                onEdt { assertTrue(terminal.pasteClipboardText()) }
                gate.awaitEntered()
                onEdt {
                    assertFalse("Repeated pastes must not queue behind a blocked read", terminal.pasteClipboardText())
                    terminal.dispose()
                }
                gate.release.countDown()
                f.drainWorker()
                onEdt { assertFalse(terminal.pasteClipboardText()) }
                assertTrue(connector.writtenBytes.isEmpty())
                assertEquals(listOf("read", "read-returned"), f.native.events)
            } finally {
                gate.release.countDown()
                onEdt { terminal.dispose() }
                session.close()
            }
        }

    fun testBlockedClientDoesNotDelayOrSelectAnotherClientsClipboard() =
        Fixture().use { first ->
            Fixture(ClientId("other-clipboard-client")).use { second ->
                second.native.text = "other-client-secret"
                val gate = first.blockRead()
                val read = first.start { first.handler.readTextAsync() }
                gate.awaitEntered()
                assertEquals("other-client-secret", await(second.start { second.handler.readTextAsync() }))
                assertFalse(read.isCompleted)
                gate.release.countDown()
                assertEquals("original", await(read))
                assertEquals(listOf("read", "read-returned"), first.native.events)
                assertEquals(listOf("read"), second.native.events)
            }
        }

    fun testEitherClientDisposalCancelsBindingWritesBeforeNativeReadReturns() {
        for (applicationOwner in listOf(false, true)) {
            Fixture().use { f ->
                val gate = f.blockRead()
                val read = f.start { f.handler.readTextAsync() }
                gate.awaitEntered()
                val binding = f.binding()
                val write = onEdt { binding.write("must-not-write") }
                onEdt {
                    assertFalse(write.isCompleted)
                    Disposer.dispose(if (applicationOwner) f.application.client else f.projectOwner.client)
                    assertTrue("Owner disposal must cancel queued binding work immediately", write.isCancelled)
                }
                gate.release.countDown()
                assertCancelled(read)
                join(write)
                assertEquals(listOf("read", "read-returned"), f.native.events)
            }
        }
    }

    private inner class Fixture(
        id: ClientId = ClientId("clipboard-worker-test"),
    ) : AutoCloseable {
        private val lifetime: Disposable = onEdt { Disposer.newDisposable(testRootDisposable, "async clipboard") }
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val ioDispatcher = executor.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
        val application = owner(id)
        val projectOwner = owner(application.id)
        val native = RecordingClipboard(application.id)
        val handler = handler()
        private val gates = mutableListOf<Gate>()
        private val sessions = mutableListOf<TerminalSession>()

        fun owner(id: ClientId): Owner = Owner(id).also { onEdt { Disposer.register(lifetime, it.client) } }

        fun handler(): IntellijTerminalClipboardHandler =
            IntellijTerminalClipboardHandler(application.client, projectOwner.client, ioDispatcher, native)

        fun binding(): IntellijClipboardSession {
            val session = TerminalSession.create(TerminalBuffers.create(10, 3), MockConnector())
            sessions.add(session)
            return onEdt {
                IntellijClipboardSession(projectOwner.client, application.client, session, SwingClipboardReader(), handler)
                    .also { binding -> Disposer.register(lifetime) { Disposer.dispose(binding) } }
            }
        }

        fun blockRead(): Gate =
            Gate().also { gate ->
                gates.add(gate)
                native.onRead = {
                    gate.entered.countDown()
                    check(gate.release.await(10, TimeUnit.SECONDS)) { "Native read was not released" }
                    native.events.add("read-returned")
                    "original"
                }
            }

        fun <T> start(action: suspend () -> T): Deferred<T> = onEdt { scope.async(start = CoroutineStart.UNDISPATCHED) { action() } }

        fun drainWorker() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            onEdt {}
        }

        override fun close() {
            gates.forEach { it.release.countDown() }
            onEdt {
                scope.cancel()
                Disposer.dispose(lifetime)
            }
            drainWorker()
            sessions.forEach { it.close() }
            ioDispatcher.close()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private class Owner(
        val id: ClientId,
    ) {
        private val scope = CoroutineScope(SupervisorJob())
        val current = AtomicReference<IntellijClipboardClient>()
        val client =
            IntellijClipboardClient(scope, id, current::get).also {
                current.set(it)
                Disposer.register(it) { scope.cancel() }
            }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun awaitEntered() = check(entered.await(10, TimeUnit.SECONDS)) { "Native read was not entered" }
    }

    private class RecordingClipboard(
        private val owner: ClientId,
    ) : TerminalClipboardHandler {
        val events = CopyOnWriteArrayList<String>()

        @Volatile var text = "original"
        var onRead: () -> String? = { text }

        override fun copyText(text: String) {
            check(SwingUtilities.isEventDispatchThread()) { "IDE history/listeners belong on the EDT" }
            check(ClientId.current == owner)
            events.add("write:$text")
            this.text = text
        }

        override fun readText(): String? {
            check(!SwingUtilities.isEventDispatchThread())
            check(ClientId.current == owner)
            events.add("read")
            return onRead()
        }

        override fun readPrimarySelectionText(): String {
            check(!SwingUtilities.isEventDispatchThread())
            check(ClientId.current == owner)
            events.add("primary")
            return "primary"
        }
    }

    private fun <T> await(result: Deferred<T>): T = runBlocking { withTimeout(10.seconds) { result.await() } }

    private fun join(job: Job) = runBlocking { withTimeout(10.seconds) { job.join() } }

    private fun assertCancelled(result: Deferred<*>) {
        try {
            await(result)
            fail("Obsolete clipboard access must be cancelled")
        } catch (_: CancellationException) {
            assertTrue(result.isCancelled)
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }
}
