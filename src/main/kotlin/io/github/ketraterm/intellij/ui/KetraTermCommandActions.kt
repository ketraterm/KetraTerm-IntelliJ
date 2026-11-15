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

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** History actions use the shared viewport's presentation coordinates. */
internal class KetraTermScrollOldestAction : KetraTermPaneLifecycleAction() {
    override fun isEnabled(pane: KetraTermTerminalPane): Boolean = pane.terminal.viewportState().historySize > 0

    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) = pane.terminal.scrollToScrollbackOffset(Int.MAX_VALUE)
}

internal class KetraTermScrollLiveAction : KetraTermPaneLifecycleAction() {
    override fun isEnabled(pane: KetraTermTerminalPane): Boolean = !pane.terminal.viewportState().isAtLiveViewport

    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) = pane.terminal.scrollToLiveViewport()
}

internal class KetraTermPreviousCommandAction : KetraTermPaneLifecycleAction() {
    override fun isEnabled(pane: KetraTermTerminalPane): Boolean = pane.canNavigateCommands()

    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) = pane.terminal.scrollToPreviousCommand()
}

internal class KetraTermNextCommandAction : KetraTermPaneLifecycleAction() {
    override fun isEnabled(pane: KetraTermTerminalPane): Boolean = pane.canNavigateCommands()

    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) = pane.terminal.scrollToNextCommand()
}

/** Popup actions keep their captured record; keyboard actions target the latest command. */
internal abstract class KetraTermCommandAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled = pane != null && isEnabled(pane, record(event, pane))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val pane = terminalPane(event) ?: return
        val record = record(event, pane)
        if (isEnabled(pane, record)) perform(pane, record)
    }

    private fun record(
        event: AnActionEvent,
        pane: KetraTermTerminalPane,
    ): Int =
        event.getData(KetraTermTerminalData.COMMAND_RECORD) ?: pane.tab.session.shellIntegrationState
            .latestCommandRecordId()

    protected open fun isEnabled(
        pane: KetraTermTerminalPane,
        record: Int,
    ): Boolean = pane.canUseCommandOutput(record)

    protected abstract fun perform(
        pane: KetraTermTerminalPane,
        record: Int,
    )
}

internal class KetraTermCopyCommandAction : KetraTermCommandAction() {
    override fun isEnabled(
        pane: KetraTermTerminalPane,
        record: Int,
    ): Boolean =
        pane.canNavigateCommands() &&
            pane.tab.session.shellIntegrationState
                .commandText(record) != null

    override fun perform(
        pane: KetraTermTerminalPane,
        record: Int,
    ) {
        pane.terminal.copyCommandTextToClipboard(record)
    }
}

internal class KetraTermSelectCommandOutputAction : KetraTermCommandAction() {
    override fun perform(
        pane: KetraTermTerminalPane,
        record: Int,
    ) {
        pane.terminal.selectCommandOutput(record)
    }
}

internal class KetraTermCopyCommandOutputAction : KetraTermCommandAction() {
    override fun perform(
        pane: KetraTermTerminalPane,
        record: Int,
    ) {
        pane.terminal.copyCommandOutputToClipboard(record)
    }
}

internal class KetraTermExportCommandOutputAction : KetraTermCommandAction() {
    override fun perform(
        pane: KetraTermTerminalPane,
        record: Int,
    ) {
        pane.exportCommandOutput(record)
    }
}
