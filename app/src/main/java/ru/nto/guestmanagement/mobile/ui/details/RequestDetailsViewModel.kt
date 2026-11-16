package ru.nto.guestmanagement.mobile.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestDetails
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate

data class DetailsUiState(
    val details: RequestDetails? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val cancelError: String? = null,
    val isCancelling: Boolean = false,
    val showCancelDialog: Boolean = false
)

class RequestDetailsViewModel(
    private val requestId: Long,
    private val container: AppContainer,
    private val onSessionExpired: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    private val refreshGate = RefreshGate()
    private val requestsApi = RequestsApi(container.httpClient)

    init {
        loadDetails()
    }

    fun loadDetails() {
        refreshGate.launch(viewModelScope) {
            if (_uiState.value.details == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            } else {
                _uiState.update { it.copy(error = null) }
            }

            val code = container.session.current()

            if (code == null) {
                onSessionExpired()
                return@launch
            }

            val result = requestsApi.get(code, requestId)

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(details = result.value, isLoading = false, error = null) }
                }
                is ApiResult.Unauthorized -> {
                    onSessionExpired()
                    _uiState.update { it.copy(isLoading = false) }
                }
                is ApiResult.NotFound -> {
                    _uiState.update { it.copy(isLoading = false, error = "Заявка не найдена") }
                }
                is ApiResult.Network, is ApiResult.Failure -> {
                    val errorMsg = if (_uiState.value.details != null) "Нет связи" else "Ошибка загрузки"
                    _uiState.update { it.copy(isLoading = false, error = errorMsg) }
                }
                else -> {
                    _uiState.update { it.copy(isLoading = false, error = "Ошибка загрузки") }
                }
            }
        }
    }

    fun showCancelDialog() {
        _uiState.update { it.copy(showCancelDialog = true, cancelError = null) }
    }

    fun dismissCancelDialog() {
        _uiState.update { it.copy(showCancelDialog = false) }
    }

    fun cancelRequest() {
        if (_uiState.value.isCancelling) return

        viewModelScope.launch {
            _uiState.update { it.copy(isCancelling = true, cancelError = null, showCancelDialog = false) }

            val code = container.session.current()

            if (code == null) {
                onSessionExpired()
                return@launch
            }

            val result = requestsApi.cancel(code, requestId)

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(details = result.value, isCancelling = false, cancelError = null) }
                }
                is ApiResult.Conflict -> {
                    _uiState.update { it.copy(isCancelling = false, cancelError = "Заявка уже рассмотрена") }
                    loadDetails()
                }
                is ApiResult.Network, is ApiResult.Failure -> {
                    _uiState.update { it.copy(isCancelling = false, cancelError = "Нет связи") }
                }
                is ApiResult.Unauthorized -> {
                    onSessionExpired()
                    _uiState.update { it.copy(isCancelling = false) }
                }
                else -> {
                    _uiState.update { it.copy(isCancelling = false, cancelError = "Ошибка отмены") }
                }
            }
        }
    }
}