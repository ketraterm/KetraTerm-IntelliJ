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

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.services.intellijTerminalProfileRegistry
import io.github.ketraterm.intellij.settings.KetraTermIntellijProfileIcons
import io.github.ketraterm.workspace.TerminalProfile
import kotlinx.coroutines.*

/** Loads shell choices without filesystem access from action construction or updates. */
internal class KetraTermShellProfileGroup(
    parent: Disposable,
    private val discover: () -> List<TerminalProfile> = { intellijTerminalProfileRegistry().availableProfiles() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val open: (TerminalProfile) -> Unit,
) : DefaultActionGroup("", true),
    Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT + ModalityState.any().asContextElement())
    private val icons = KetraTermIntellijProfileIcons()
    private var actions: Array<AnAction>? = null
    private var loading = false
    private val loadingAction = status("terminal.profiles.loading")

    init {
        templatePresentation.description = KetraTermBundle.message("action.ketraterm.newTerminalProfile.description")
        templatePresentation.icon = com.intellij.icons.AllIcons.General.ButtonDropTriangle
        Disposer.register(parent, this)
    }

    override fun getChildren(event: AnActionEvent?): Array<AnAction> {
        if (!scope.isActive) return emptyArray()
        if (actions == null && !loading) load()
        return actions ?: arrayOf(loadingAction)
    }

    private fun load() {
        loading = true
        actions = null
        scope.launch {
            try {
                val profiles = withContext(ioDispatcher) { discover() }
                actions =
                    profiles
                        .map { profile ->
                            object : DumbAwareAction(
                                profile.displayName,
                                KetraTermBundle.message("action.ketraterm.openTerminalProfile.description", profile.displayName),
                                icons.icon(profile.kind),
                            ) {
                                override fun actionPerformed(event: AnActionEvent) {
                                    if (scope.isActive) open(profile)
                                }

                                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                            }
                        }.toTypedArray()
                if (profiles.isEmpty()) actions = arrayOf(status("terminal.profiles.empty"))
                ActivityTracker.getInstance().inc()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.getInstance(KetraTermShellProfileGroup::class.java).warn("Shell profile discovery failed", failure)
                actions =
                    arrayOf(
                        object : DumbAwareAction(KetraTermBundle.message("terminal.profiles.retry")) {
                            override fun actionPerformed(event: AnActionEvent) {
                                if (scope.isActive && !loading) load()
                            }

                            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                        },
                    )
            } finally {
                loading = false
            }
        }
    }

    private fun status(key: String): AnAction =
        object : DumbAwareAction(KetraTermBundle.message(key)) {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = false
            }

            override fun actionPerformed(event: AnActionEvent) = Unit

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun dispose() {
        scope.cancel()
        actions = emptyArray()
    }
}
