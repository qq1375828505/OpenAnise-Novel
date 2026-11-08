package com.ai.assistance.quro.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.ai.assistance.quro.agent.Level
import com.ai.assistance.quro.agent.ToolLoopDetector
import com.ai.assistance.quro.browser.BrowserActionInput
import com.ai.assistance.quro.browser.BrowserTabPool
import com.ai.assistance.quro.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.ai.assistance.quro.data.BPETokenizer
import com.ai.assistance.quro.data.ContextOffload
import com.ai.assistance.quro.data.ContextPolicy
import com.ai.assistance.quro.logging.AppLogger
import com.ai.assistance.quro.data.FileMentionIndex
import com.ai.assistance.quro.data.db.CompactMarkerEntity
import com.ai.assistance.quro.data.model.AgentContentPart
import com.ai.assistance.quro.data.model.AgentToolDefinition
import com.ai.assistance.quro.data.model.LLMMessage
import com.ai.assistance.quro.data.model.LLMModel
import com.ai.assistance.quro.data.model.LLMStreamChunk
import com.ai.assistance.quro.data.model.LLMUsage
import com.ai.assistance.quro.data.model.ModelGroup
import com.ai.assistance.quro.data.model.ThinkingLevel
import com.ai.assistance.quro.R
import com.ai.assistance.quro.data.repository.ChatRepository
import com.ai.assistance.quro.data.repository.MemoryRepository
import com.ai.assistance.quro.data.repository.ProviderRepository
import com.ai.assistance.quro.provider.ImageBudget
import com.ai.assistance.quro.provider.LLMProvider
import com.ai.assistance.quro.provider.ProviderFactory
import com.ai.assistance.quro.sandbox.ExecutionCoordinator
import com.ai.assistance.quro.terminal.MinisOpenUrlBroker
import com.ai.assistance.quro.terminal.MinisUrlMarker
import com.ai.assistance.quro.tools.AgentTools
import com.ai.assistance.quro.tools.FileEditTool
import com.ai.assistance.quro.tools.FileReadTool
import com.ai.assistance.quro.tools.FileWriteTool
import com.ai.assistance.quro.tools.MemoryTools
import com.ai.assistance.quro.tools.ReadImageTool
import com.ai.assistance.quro.tools.ToolExecutionResult
import com.ai.assistance.quro.offload.OffloadPermissionManager
import com.ai.assistance.quro.service.SessionActivityTracker
import com.ai.assistance.quro.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
