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
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.services.KetraTermProjectTerminalService
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.KeyboardFocusManager

internal object KetraTermTerminalActionIds {
    const val CONTEXT_MENU_PLACE = "KetraTerm.TerminalContextMenu"
    const val COPY_SELECTION = "KetraTerm.Terminal.CopySelection"
    const val PASTE_CLIPBOARD = "KetraTerm.Terminal.PasteClipboard"
    const val OPEN_SEARCH = "KetraTerm.Terminal.OpenSearch"
    const val REQUEST_SUGGESTIONS = "KetraTerm.Terminal.RequestSuggestions"
    const val SELECT_ALL = "KetraTerm.Terminal.SelectAll"
    const val CLEAR_SCREEN = "KetraTerm.Terminal.ClearScreen"
    const val NEW_TAB = "KetraTerm.Terminal.NewTab"
    const val RENAME_TAB = "KetraTerm.Terminal.RenameTab"
    const val CLOSE_TAB = "KetraTerm.Terminal.CloseTab"
    const val OPEN_TERMINAL_HERE = "KetraTerm.Terminal.OpenTerminalHere"
    const val SCROLL_PAGE_UP = "KetraTerm.Terminal.ScrollPageUp"
    const val SCROLL_PAGE_DOWN = "KetraTerm.Terminal.ScrollPageDown"
    const val SCROLL_OLDEST = "KetraTerm.Terminal.ScrollOldest"
    const val SCROLL_LIVE = "KetraTerm.Terminal.ScrollLive"
    const val PREVIOUS_COMMAND = "KetraTerm.Terminal.PreviousCommand"
    const val NEXT_COMMAND = "KetraTerm.Terminal.NextCommand"
    const val COPY_COMMAND = "KetraTerm.Terminal.CopyCommand"
    const val SELECT_COMMAND_OUTPUT = "KetraTerm.Terminal.SelectCommandOutput"
    const val COPY_COMMAND_OUTPUT = "KetraTerm.Terminal.CopyCommandOutput"
    const val EXPORT_COMMAND_OUTPUT = "KetraTerm.Terminal.ExportCommandOutput"
}

/**
 * Base class for IntelliJ keymap actions that target the focused KetraTerm pane.
 *
 * Actions are registered in `plugin.xml` so users can inspect and customize
 * shortcuts through the IDE keymap. The focused pane still owns execution
 * semantics through [KetraTermTerminalPane.performTerminalAction].
 *
 * @param terminalAction host-owned terminal pane action to perform.
 */
abstract class KetraTermTerminalAction(
    internal val terminalAction: SwingTerminalHostAction,
) : DumbAwareAction() {
    /**
     * Performs this action against the focused KetraTerm pane.
     *
     * @param event IntelliJ action event.
     */
    override fun actionPerformed(event: AnActionEvent) {
        val pane = terminalPane(event) ?: return
        if (!pane.isTerminalActionEnabled(terminalAction, fromContextMenu = event.isTerminalContextMenu())) return
        pane.performTerminalAction(terminalAction)
    }

    /**
     * Enables this action only when a KetraTerm pane owns focus.
     *
     * @param event IntelliJ action event.
     */
    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled =
            pane != null &&
            pane.isTerminalActionEnabled(terminalAction, fromContextMenu = event.isTerminalContextMenu())
    }

    /**
     * Declares that pane lookup and presentation updates run on the EDT.
     *
     * @return IntelliJ EDT update thread.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Copies the focused terminal selection to the IDE clipboard.
 */
class KetraTermCopySelectionAction : KetraTermTerminalAction(SwingTerminalHostAction.COPY_SELECTION)

/**
 * Pastes IDE clipboard text into the focused terminal session.
 */
class KetraTermPasteClipboardAction : KetraTermTerminalAction(SwingTerminalHostAction.PASTE_CLIPBOARD)

/**
 * Opens the focused terminal pane's search overlay.
 */
class KetraTermOpenSearchAction : KetraTermTerminalAction(SwingTerminalHostAction.OPEN_SEARCH)

/**
 * Requests suggestions for the active command line in the focused terminal.
 */
class KetraTermRequestSuggestionsAction : KetraTermTerminalAction(SwingTerminalHostAction.REQUEST_SUGGESTIONS)

/**
 * Selects all retained text in the focused terminal.
 */
class KetraTermSelectAllAction : KetraTermTerminalAction(SwingTerminalHostAction.SELECT_ALL)

/**
 * Requests the foreground terminal program to clear/redraw the screen.
 */
class KetraTermClearScreenAction : KetraTermTerminalAction(SwingTerminalHostAction.CLEAR_SCREEN)

/**
 * Scrolls the focused terminal one visible page away from the live viewport.
 */
class KetraTermScrollPageUpAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_UP)

/**
 * Scrolls the focused terminal one visible page toward the live viewport.
 */
class KetraTermScrollPageDownAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_DOWN)

/**
 * Opens a new KetraTerm tab in the current tool window.
 */
internal class KetraTermNewTabAction : KetraTermPaneLifecycleAction() {
    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.openNewTab()
    }
}

/**
 * Edits the focused terminal tab's custom title, with an empty name restoring its automatic title.
 */
internal class KetraTermRenameTabAction : KetraTermPaneLifecycleAction() {
    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.renameTab()
    }
}

/**
 * Closes the focused KetraTerm tab.
 */
internal class KetraTermCloseTabAction : KetraTermPaneLifecycleAction() {
    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.closePane()
    }
}

/**
 * Opens a tab in the selected local directory, a selected file's parent, or the focused terminal's directory.
 */
internal class KetraTermOpenTerminalHereAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (usesTerminalPane(event, file)) {
            val pane = terminalPane(event) ?: return
            if (pane.canOpenTerminalHere()) pane.openTerminalHere()
            return
        }

        val project = event.project ?: return
        if (project.isDisposed || project.isDefault) return
        val directory = terminalContextDirectory(file) ?: return
        val workingDirectory = directory.fileSystem.getNioPath(directory) ?: return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("KetraTerm") ?: return
        KetraTermProjectTerminalService.getInstance(project).openDefaultTab(toolWindow, workingDirectory)
        toolWindow.activate(null)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.text =
            KetraTermBundle.message(
                if (event.isTerminalContextMenu()) {
                    "action.KetraTerm.Terminal.OpenTerminalHere.context.text"
                } else {
                    "action.KetraTerm.Terminal.OpenTerminalHere.text"
                },
            )
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (usesTerminalPane(event, file)) {
            val enabled =
                event.updateSession.compute(this, "terminalDirectory", ActionUpdateThread.EDT) {
                    terminalPane(event)?.canOpenTerminalHere() == true
                }
            event.presentation.isEnabled = enabled
            event.presentation.isVisible = event.isTerminalContextMenu() || enabled
            return
        }

        val project = event.project
        event.presentation.isEnabledAndVisible =
            project != null &&
            !project.isDisposed &&
            !project.isDefault &&
            terminalContextDirectory(file) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun usesTerminalPane(
        event: AnActionEvent,
        file: VirtualFile?,
    ): Boolean = event.isTerminalContextMenu() || (file == null && !event.isFromContextMenu)
}

/** Resolves a local launch directory without filesystem I/O or constructing a path during action updates. */
internal fun terminalContextDirectory(file: VirtualFile?): VirtualFile? {
    if (file == null || !file.isValid || !file.isInLocalFileSystem) return null
    val directory = if (file.isDirectory) file else file.parent ?: return null
    return directory.takeIf { it.isValid && it.isDirectory && it.isInLocalFileSystem }
}

internal abstract class KetraTermPaneLifecycleAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val pane = terminalPane(event) ?: return
        if (pane.isClosed || !isEnabled(pane)) return
        actionPerformed(pane, event)
    }

    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled = pane != null && isEnabled(pane)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    protected open fun isEnabled(pane: KetraTermTerminalPane): Boolean = true

    protected abstract fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    )
}

internal fun terminalPane(event: AnActionEvent): KetraTermTerminalPane? {
    event.getData(KetraTermTerminalData.PANE)?.let { return it.takeUnless { pane -> pane.isClosed } }
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
    event.project
        ?.getServiceIfCreated(KetraTermProjectTerminalService::class.java)
        ?.paneForComponent(focusOwner)
        ?.let { return it }
    for (project in ProjectManager.getInstance().openProjects) {
        project.getServiceIfCreated(KetraTermProjectTerminalService::class.java)?.paneForComponent(focusOwner)?.let { return it }
    }
    return null
}

private fun AnActionEvent.isTerminalContextMenu(): Boolean = place == KetraTermTerminalActionIds.CONTEXT_MENU_PLACE

/** Immutable popup data keeps command actions bound to the pane and record that opened the menu. */
internal object KetraTermTerminalData {
    val PANE: DataKey<KetraTermTerminalPane> = DataKey.create("ketraterm.terminalPane")
    val COMMAND_RECORD: DataKey<Int> = DataKey.create("ketraterm.commandRecord")
}
