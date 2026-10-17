package com.tessera.otp.feature.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.BuildConfig
import com.tessera.otp.data.update.UpdateChecker
import com.tessera.otp.data.update.UpdateException
import com.tessera.otp.data.update.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 检查更新弹窗的三种结果。 */
sealed interface UpdateDialogState {
    data class UpToDate(val currentVersion: String) : UpdateDialogState
    data class Available(val info: UpdateInfo) : UpdateDialogState
    data class Failed(val message: String) : UpdateDialogState
}

data class UpdateUiState(
    val checking: Boolean = false,
    val dialog: UpdateDialogState? = null
)

/**
 * 检查更新（关于页）。
 *
 * 手动触发：只在用户点「检查更新」时访问 GitHub，不在启动/后台自动发请求，
 * 避免无谓的网络访问和 GitHub 未登录请求的限流消耗。
 */
class UpdateViewModel(
    private val checker: UpdateChecker = UpdateChecker(
        currentVersion = BuildConfig.VERSION_NAME,
        abis = Build.SUPPORTED_ABIS.toList()
    )
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun check() {
        if (_state.value.checking) return
        _state.value = UpdateUiState(checking = true)
        viewModelScope.launch {
            val dialog = try {
                val info = checker.check()
                if (info == null) UpdateDialogState.UpToDate(BuildConfig.VERSION_NAME)
                else UpdateDialogState.Available(info)
            } catch (c: CancellationException) {
                throw c
            } catch (e: UpdateException) {
                UpdateDialogState.Failed(e.message ?: "检查更新失败")
            } catch (t: Throwable) {
                UpdateDialogState.Failed(t.message ?: t::class.java.simpleName)
            }
            _state.value = UpdateUiState(checking = false, dialog = dialog)
        }
    }

    fun dismiss() {
        _state.value = _state.value.copy(dialog = null)
    }
}
