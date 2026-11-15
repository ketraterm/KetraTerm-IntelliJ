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
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.keymap.KeymapManager
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * IntelliJ-hosted input policy hooks for one terminal pane.
 *
 * Keyboard shortcuts are registered as plugin actions in `plugin.xml`. This
 * controller keeps mouse-only IDE policy and a pre-encoding guard so matching
 * key events are not sent to the shell if they reach the Swing terminal before
 * the IntelliJ action system handles them.
 */
internal class KetraTermTerminalShortcutController(
    private val pane: KetraTermTerminalPane,
) {
    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!KetraTermIntellijSettings.getInstance().pasteOnMiddleClick()) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes host input hooks installed for this pane.
     */
    fun dispose() {
        pane.terminal.removeMouseListener(mouseListener)
    }

    /**
     * Handles a key press before terminal input encoding.
     *
     * @param event Swing key event from the terminal component.
     * @return `true` when the key was a host shortcut and must not be sent to
     * the shell.
     */
    fun handleKeyPressed(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.VK_ESCAPE && pane.clipboardReadPrompt.dismiss()) return true
        val keyStroke = KeyStroke.getKeyStroke(event.keyCode, event.modifiersEx and RELEVANT_MODIFIERS)
        val keymap = KeymapManager.getInstance().activeKeymap
        for (id in keymap.getActionIds(keyStroke)) {
            // The IDE owns multi-stroke shortcuts; never execute one at its prefix.
            if (keymap.getShortcuts(id).filterIsInstance<KeyboardShortcut>().none {
                    it.firstKeyStroke == keyStroke && it.secondKeyStroke == null
                }
            ) {
                continue
            }
            val action = ActionManager.getInstance().getAction(id) ?: continue
            if (action is KetraTermTerminalAction) {
                val terminalAction = action.terminalAction
                if (terminalAction == SwingTerminalHostAction.OPEN_SEARCH &&
                    !KetraTermIntellijSettings.getInstance().overrideIdeShortcuts()
                ) {
                    continue
                }
                if (!pane.isTerminalActionEnabled(terminalAction)) continue
                return pane.performTerminalAction(terminalAction)
            }
            if (action !is KetraTermPaneLifecycleAction && action !is KetraTermCommandAction) continue
            val context = SimpleDataContext.builder().add(KetraTermTerminalData.PANE, pane).build()
            val actionEvent =
                AnActionEvent.createEvent(
                    context,
                    action.templatePresentation.clone(),
                    ActionPlaces.KEYBOARD_SHORTCUT,
                    ActionUiKind.NONE,
                    event,
                )
            if (ActionUtil.updateAction(action, actionEvent).isPerformed && actionEvent.presentation.isEnabled) {
                return ActionUtil.performAction(action, actionEvent).isPerformed
            }
        }
        return false
    }

    private companion object {
        private const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or
                InputEvent.CTRL_DOWN_MASK or
                InputEvent.META_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or
                InputEvent.ALT_GRAPH_DOWN_MASK
    }
}
