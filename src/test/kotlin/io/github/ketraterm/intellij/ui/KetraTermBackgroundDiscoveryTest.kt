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

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermSettingsConfigurable
import io.github.ketraterm.workspace.TerminalProfile
import kotlinx.coroutines.asCoroutineDispatcher
import java.awt.Component
import java.awt.Container
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.*
import javax.swing.JComboBox
import javax.swing.SwingUtilities

/** Real blocked workers keep the EDT free; barriers drain results without sleeps or timing thresholds. */
class KetraTermBackgroundDiscoveryTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testShellMenuRemainsResponsiveAndPublishesOneDiscoveredList() =
        Worker().use { worker ->
            val opened = mutableListOf<TerminalProfile>()
            val group =
                onEdt {
                    KetraTermShellProfileGroup(testRootDisposable, { worker.block(listOf(PROFILE)) }, worker.dispatcher, opened::add)
                        .also {
                            assertLoading(it)
                        }
                }
            val release = worker.awaitStarted()
            onEdt {
                repeat(3) {
                    assertLoading(group)
                }
                assertEmpty(opened)
            }
            release.countDown()
            worker.drain()
            onEdt {
                val action = group.getChildren(null).single()
                assertEquals(PROFILE.displayName, action.templatePresentation.text)
                assertTrue(ActionUtil.performAction(action, event(action)).isPerformed)
                assertEquals(listOf(PROFILE), opened)
                assertSame(action, group.getChildren(null).single())
                Disposer.dispose(group)
                assertTrue(ActionUtil.performAction(action, event(action)).isPerformed)
                assertEquals(listOf(PROFILE), opened)
            }
            assertEquals(1, worker.calls)
        }

    fun testDisposedShellMenuDiscardsBlockedDiscovery() =
        Worker().use { worker ->
            val group =
                onEdt {
                    KetraTermShellProfileGroup(testRootDisposable, { worker.block(listOf(PROFILE)) }, worker.dispatcher) {
                        error("Disposed menu must not open a terminal")
                    }.also { it.getChildren(null) }
                }
            val release = worker.awaitStarted()
            onEdt { Disposer.dispose(group) }
            release.countDown()
            worker.drain()
            onEdt { assertEmpty(group.getChildren(null).toList()) }
        }

    fun testSettingsStayEditableWhileShellDiscoveryIsBlocked() =
        Worker().use { worker ->
            val configurable =
                onEdt {
                    KetraTermSettingsConfigurable(emptyList(), { worker.block(listOf(PROFILE)) }, worker.dispatcher)
                }
            // Construction must not start discovery, even before a page has a lifetime.
            assertEquals(0, worker.calls)
            val combo = onEdt { shellCombo(configurable.createComponent()) }
            try {
                val release = worker.awaitStarted()
                onEdt {
                    combo.editor.item = "My Shell --login"
                    assertEquals("My Shell --login", combo.editor.item)
                }
                release.countDown()
                worker.drain()
                onEdt {
                    assertEquals(1, combo.itemCount)
                    assertEquals("My Shell --login", combo.editor.item)
                    assertTrue(configurable.isModified)
                }
            } finally {
                onEdt { configurable.disposeUIResources() }
            }
        }

    fun testReopenedSettingsRejectResultsFromDisposedPage() =
        Worker().use { worker ->
            val configurable =
                onEdt {
                    KetraTermSettingsConfigurable(emptyList(), { worker.block(listOf(PROFILE)) }, worker.dispatcher)
                        .also { it.createComponent() }
                }
            val old = worker.awaitStarted()
            val combo =
                onEdt {
                    configurable.disposeUIResources()
                    shellCombo(configurable.createComponent()).also { it.editor.item = "new page shell" }
                }
            try {
                old.countDown()
                val current = worker.awaitStarted()
                onEdt {
                    assertEquals(0, combo.itemCount)
                    assertEquals("new page shell", combo.editor.item)
                }
                current.countDown()
                worker.drain()
                onEdt {
                    assertEquals(1, combo.itemCount)
                    assertEquals("new page shell", combo.editor.item)
                }
            } finally {
                onEdt { configurable.disposeUIResources() }
            }
        }

    fun testDirectoryActionRemainsResponsiveAndRefreshesAnUnchangedPath() =
        Worker().use { worker ->
            var exists = true
            var changes = 0
            val directory =
                onEdt {
                    TerminalWorkingDirectory({ worker.block(if (exists) DIRECTORY else null) }, worker.dispatcher, { changes++ }) { URI }
                        .also { assertFalse(it.isAvailable()) }
                }
            try {
                val first = worker.awaitStarted()
                onEdt { repeat(3) { assertFalse(directory.isAvailable()) } }
                first.countDown()
                worker.drain()
                onEdt {
                    assertTrue(directory.isAvailable())
                    assertEquals(1, changes)
                    exists = false
                    directory.refresh()
                    assertFalse(directory.isAvailable())
                }
                worker.awaitStarted().countDown()
                worker.drain()
                onEdt {
                    assertFalse(directory.isAvailable())
                    assertEquals(2, changes)
                }
            } finally {
                onEdt { directory.close() }
            }
        }

    fun testDirectoryChangeAndDisposalDiscardBlockedResults() =
        Worker().use { worker ->
            var uri = URI
            var changes = 0
            val directory =
                onEdt {
                    TerminalWorkingDirectory({ worker.block(DIRECTORY) }, worker.dispatcher, { changes++ }) { uri }
                        .also { assertFalse(it.isAvailable()) }
                }
            val first = worker.awaitStarted()
            onEdt { uri = DIRECTORY.resolve("next").toUri().toString() }
            first.countDown()
            worker.drain()
            onEdt {
                assertEquals(0, changes)
                assertFalse(directory.isAvailable())
            }
            val next = worker.awaitStarted()
            onEdt { directory.close() }
            next.countDown()
            worker.drain()
            onEdt {
                assertFalse(directory.isAvailable())
                assertEquals(0, changes)
            }
        }

    fun testNonlocalAndMalformedDirectoryUrisDoNotStartFilesystemWork() =
        onEdt {
            for (uri in listOf(null, "", "file://remote.example/repo", "https://example.com/", "not a uri")) {
                TerminalWorkingDirectory(resolve = { error("Nonlocal URI must not reach filesystem") }, readUri = { uri }).use {
                    assertFalse(it.isAvailable())
                    it.refresh()
                }
            }
        }

    fun testEmptyShellDiscoveryShowsADisabledStatus() =
        Worker().use { worker ->
            val group =
                onEdt {
                    KetraTermShellProfileGroup(testRootDisposable, { worker.block(emptyList()) }, worker.dispatcher) {
                        error("No shell is available")
                    }.also { assertLoading(it) }
                }
            worker.awaitStarted().countDown()
            worker.drain()
            onEdt {
                assertLoading(group)
                assertEquals(
                    KetraTermBundle.message("terminal.profiles.empty"),
                    group
                        .getChildren(null)
                        .single()
                        .templatePresentation.text,
                )
                Disposer.dispose(group)
            }
        }

    fun testFailedShellDiscoveryCanBeRetriedWithoutBlockingTheMenu() =
        Worker().use { worker ->
            val group =
                onEdt {
                    KetraTermShellProfileGroup(testRootDisposable, {
                        val result = worker.block(listOf(PROFILE))
                        if (worker.calls == 1) throw IOException("Test shell discovery failure")
                        result
                    }, worker.dispatcher) {}.also { assertLoading(it) }
                }
            worker.awaitStarted().countDown()
            worker.drain()
            onEdt {
                val retry = group.getChildren(null).single()
                assertEquals(KetraTermBundle.message("terminal.profiles.retry"), retry.templatePresentation.text)
                assertTrue(ActionUtil.performAction(retry, event(retry)).isPerformed)
                assertLoading(group)
            }
            val release = worker.awaitStarted()
            onEdt { assertLoading(group) }
            release.countDown()
            worker.drain()
            onEdt {
                assertEquals(
                    PROFILE.displayName,
                    group
                        .getChildren(null)
                        .single()
                        .templatePresentation.text,
                )
                Disposer.dispose(group)
            }
            assertEquals(2, worker.calls)
        }

    private fun assertLoading(group: KetraTermShellProfileGroup) {
        val action = group.getChildren(null).single()
        val event = event(action)
        assertTrue(ActionUtil.updateAction(action, event).isPerformed)
        assertFalse(event.presentation.isEnabled)
    }

    private fun event(action: AnAction): AnActionEvent =
        AnActionEvent.createEvent(
            DataContext.EMPTY_CONTEXT,
            action.templatePresentation.clone(),
            ActionPlaces.UNKNOWN,
            ActionUiKind.POPUP,
            null,
        )

    private fun shellCombo(root: Component): JComboBox<*> = descendants(root).filterIsInstance<JComboBox<*>>().single { it.isEditable }

    private fun descendants(root: Component): Sequence<Component> =
        sequence {
            yield(root)
            if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
        }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }

    private inner class Worker : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        private val entered = LinkedBlockingQueue<CountDownLatch>()
        private val gates = ConcurrentLinkedQueue<CountDownLatch>()
        var calls = 0
            private set

        fun <T> block(value: T): T {
            check(!SwingUtilities.isEventDispatchThread()) { "Discovery must run outside the EDT" }
            calls++
            val gate = CountDownLatch(1)
            gates.add(gate)
            entered.add(gate)
            check(gate.await(10, TimeUnit.SECONDS)) { "Worker was not released" }
            return value
        }

        fun awaitStarted(): CountDownLatch = requireNotNull(entered.poll(10, TimeUnit.SECONDS)) { "Worker did not start" }

        fun drain() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            onEdt {}
        }

        override fun close() {
            gates.forEach { it.countDown() }
            dispatcher.close()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
            onEdt {}
        }
    }

    private companion object {
        val PROFILE = TerminalProfile("test-shell", "Test Shell", listOf("test-shell"))
        val DIRECTORY: Path = Path.of(System.getProperty("user.home"))
        val URI: String = DIRECTORY.toUri().toString()
    }
}
