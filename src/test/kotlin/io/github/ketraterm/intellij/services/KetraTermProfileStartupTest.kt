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
package io.github.ketraterm.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.workspace.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.*
import javax.swing.SwingUtilities

/** Profile resolution uses the existing startup worker, including cancellation before session creation. */
class KetraTermProfileStartupTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testDefaultProfileResolutionKeepsUiResponsiveAndCapturesLaunchSettings() =
        withFixture { fixture ->
            val expected =
                onEdt {
                    val settings = KetraTermIntellijSettings.getInstance()
                    settings.replaceState(settings.state.copy(shellPath = "first-shell"))
                    (project.guessProjectDir()?.path ?: project.basePath) to settings.state
                }
            val directory = Path.of(System.getProperty("user.home"))
            val content = onEdt { fixture.service.openDefaultTab(fixture.window, directory) }
            fixture.entered.get(10, TimeUnit.SECONDS)
            onEdt {
                assertEquals(1, fixture.manager.contentCount)
                assertSame(content, fixture.manager.selectedContent)
                val settings = KetraTermIntellijSettings.getInstance()
                settings.replaceState(settings.state.copy(shellPath = "later-shell"))
            }
            fixture.release.complete(Unit)
            fixture.drain()
            val arguments = fixture.arguments.get(10, TimeUnit.SECONDS)
            assertEquals(expected.first, arguments.first)
            assertEquals(expected.second, arguments.second)
            assertEquals(directory, arguments.third)
            assertEquals(PROFILE, fixture.prepared.single())
            onEdt {
                assertEquals("Failed: " + expected.second.defaultTabName, content.displayName)
                assertEquals(1, fixture.manager.contentCount)
            }
        }

    fun testClosingPendingTabDuringProfileDiscoveryPreventsSessionStartup() =
        withFixture { fixture ->
            val content = onEdt { fixture.service.openDefaultTab(fixture.window) }
            fixture.entered.get(10, TimeUnit.SECONDS)
            onEdt {
                assertTrue(fixture.manager.removeContent(content, true))
                assertFalse(fixture.service.hasOpenTabs())
            }
            fixture.release.complete(Unit)
            fixture.drain()
            assertEmpty(fixture.prepared)
            onEdt { assertEquals(0, fixture.manager.contentCount) }
        }

    fun testProjectDisposalDuringProfileDiscoveryPreventsSessionStartup() =
        withFixture { fixture ->
            onEdt { fixture.service.openDefaultTab(fixture.window) }
            fixture.entered.get(10, TimeUnit.SECONDS)
            onEdt {
                Disposer.dispose(fixture.service)
                assertFalse(fixture.service.hasOpenTabs())
            }
            fixture.release.complete(Unit)
            fixture.drain()
            assertEmpty(fixture.prepared)
        }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = onEdt { Fixture() }
        try {
            test(fixture)
        } finally {
            fixture.release.complete(Unit)
            onEdt { Disposer.dispose(fixture.lifetime) }
            fixture.drain()
            fixture.dispatcher.close()
            check(fixture.executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private inner class Fixture {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val lifetime: Disposable = Disposer.newDisposable(testRootDisposable, "profile discovery")
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val arguments = CompletableFuture<Triple<String?, KetraTermIntellijSettings.State, Path?>>()
        val prepared = LinkedBlockingQueue<TerminalProfile>()
        val manager: ContentManager =
            ContentFactory
                .getInstance()
                .createContentManager(true, project)
                .also { Disposer.register(lifetime, it) }
        val service: KetraTermProjectTerminalService
        val window: ToolWindow

        init {
            val settings = KetraTermIntellijSettings()
            settings.replaceState(settings.state.copy(addProjectJdkToPath = false, smartSuggestionsEnabled = false))
            ApplicationManager.getApplication().replaceService(KetraTermIntellijSettings::class.java, settings, lifetime)
            project.replaceService(KetraTermTerminalTabsStorage::class.java, KetraTermTerminalTabsStorage(), lifetime)
            service =
                KetraTermProjectTerminalService(
                    project,
                    workspaceFactory = { listener -> workspace(listener) },
                    defaultProfileFactory = { base, state, directory ->
                        check(!SwingUtilities.isEventDispatchThread())
                        arguments.complete(Triple(base, state, directory))
                        entered.complete(Unit)
                        release.get(10, TimeUnit.SECONDS)
                        PROFILE
                    },
                    prepareStartup = {
                        prepared.add(it)
                        error("PTY startup is disabled in this fixture")
                    },
                ).also { Disposer.register(lifetime, it) }
            window =
                Proxy.newProxyInstance(ToolWindow::class.java.classLoader, arrayOf(ToolWindow::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "getContentManager" -> manager
                        "getComponent" -> manager.component
                        "isActive", "isDisposed" -> false
                        "getTitle" -> "KetraTerm"
                        "setTitle" -> null
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.get(0)
                        "toString" -> "Profile discovery tool window"
                        else -> error("Unexpected tool window call: ${method.name}")
                    }
                } as ToolWindow
        }

        fun drain() {
            executor.submit {}.get(10, TimeUnit.SECONDS)
            onEdt {}
        }

        private fun workspace(listener: TerminalWorkspaceListener): TerminalWorkspace {
            val factoryType = Class.forName("io.github.ketraterm.workspace.TerminalWorkspaceSessionFactory")
            val factory =
                Proxy.newProxyInstance(factoryType.classLoader, arrayOf(factoryType)) { _, _, _ ->
                    error("No session may be created after cancellation or failed preparation")
                }
            return TerminalWorkspace::class.java
                .getDeclaredConstructor(
                    TerminalWorkspaceListener::class.java,
                    factoryType,
                    CoroutineDispatcher::class.java,
                ).newInstance(listener, factory, dispatcher) as TerminalWorkspace
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(Callable(action))
        runInEdtAndWait { task.run() }
        return task.get(10, TimeUnit.SECONDS)
    }

    private companion object {
        val PROFILE = TerminalProfile("test-shell", "Test Shell", listOf("test-shell"))
    }
}
