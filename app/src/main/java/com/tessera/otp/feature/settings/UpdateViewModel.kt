package com.tessera.otp.feature.settings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.BuildConfig
import com.tessera.otp.data.update.ChangelogEntry
import com.tessera.otp.data.update.ChangelogRepository
import com.tessera.otp.data.update.UpdateChecker
import com.tessera.otp.data.update.UpdateException
import com.tessera.otp.data.update.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 检查更新弹窗的三种结果。 */
sealed interface UpdateDialogState {
    data class UpToDate(val currentVersion: String) : UpdateDialogState
    data class Available(val info: UpdateInfo) : UpdateDialogState
    data class Failed(val message: String) : UpdateDialogState
}

data class UpdateUiState(
    val checking: Boolean = false,
    val dialog: UpdateDialogState? = null,
    /** 冷启动自动检查发现的更新；只有存在新版时才非空。 */
    val autoUpdate: UpdateInfo? = null,
    /** 内置更新日志（新版本在前）。 */
    val history: List<ChangelogEntry> = emptyList()
)

/**
 * 检查更新（冷启动自动 + 关于页手动）。
 *
 * 自动检查失败或已是最新时完全静默，只有发现新版才弹窗；
 * 更新说明优先取内置更新日志，缺失时回退 GitHub Release 正文或仓库里的最新日志。
 */
class UpdateViewModel(
    context: Context,
    private val checker: UpdateChecker = UpdateChecker(
        currentVersion = BuildConfig.VERSION_NAME,
        abis = Build.SUPPORTED_ABIS.toList()
    ),
    private val changelog: ChangelogRepository = ChangelogRepository(context.applicationContext)
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var autoCheckStarted = false

    init {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { changelog.bundled() }
            _state.update { it.copy(history = entries) }
        }
    }

    fun check() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, dialog = null, autoUpdate = null) }
        viewModelScope.launch {
            val dialog = try {
                val info = checker.check()
                if (info == null) UpdateDialogState.UpToDate(BuildConfig.VERSION_NAME)
                else UpdateDialogState.Available(withChangelog(info))
            } catch (c: CancellationException) {
                throw c
            } catch (e: UpdateException) {
                UpdateDialogState.Failed(e.message ?: "检查更新失败")
            } catch (t: Throwable) {
                UpdateDialogState.Failed(t.message ?: t::class.java.simpleName)
            }
            _state.update { it.copy(checking = false, dialog = dialog) }
        }
    }

    /** 冷启动调用一次：延迟到首屏出码之后再请求，失败静默。 */
    fun autoCheck() {
        if (autoCheckStarted) return
        autoCheckStarted = true
        viewModelScope.launch {
            delay(AUTO_CHECK_DELAY_MS)
            val info = try {
                checker.check()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            } ?: return@launch
            val enriched = withChangelog(info)
            _state.update {
                // 用户已经在手动检查/查看结果时不再叠加自动弹窗
                if (it.checking || it.dialog != null) it else it.copy(autoUpdate = enriched)
            }
        }
    }

    fun dismiss() {
        _state.update { it.copy(dialog = null) }
    }

    fun dismissAuto() {
        _state.update { it.copy(autoUpdate = null) }
    }

    private suspend fun withChangelog(info: UpdateInfo): UpdateInfo {
        val notes = changelog.notesFor(info.latestVersion)
        return if (notes.isNullOrBlank()) info else info.copy(notes = notes)
    }

    private companion object {
        /** 让首屏查询/出码先完成；更新检查本身是纯网络操作。 */
        const val AUTO_CHECK_DELAY_MS = 1_500L
    }
}
