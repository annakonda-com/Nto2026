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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

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
) : ViewModel() {

    private val sessionExpired =
        Channel<Unit>(Channel.BUFFERED)

    val sessionExpiredEvents =
        sessionExpired.receiveAsFlow()

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    private val refreshGate = RefreshGate()
    private val requestsApi = RequestsApi(container.httpClient)

    fun loadDetails() {
        refreshGate.launch(viewModelScope) {
            _uiState.update {
                it.copy(isLoading = true)
            }
            if (_uiState.value.details == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            } else {
                _uiState.update { it.copy(error = null) }
            }

            val code = container.session.current()

            if (code == null) {
                sessionExpired.send(Unit)
                return@launch
            }

            val result = requestsApi.get(code, requestId)

            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            details = result.value,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                is ApiResult.Unauthorized -> {
                    container.session.clear()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = null,
                            error = null
                        )
                    }

                    sessionExpired.send(Unit)
                }
                is ApiResult.NotFound -> {
                    val errorMsg = if (_uiState.value.details != null) "Нет связи" else "Заявка не найдена"
                    _uiState.update { it.copy(isLoading = false, error = errorMsg) }
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
                sessionExpired.send(Unit)
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
                    container.session.clear()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            details = null,
                            error = null
                        )
                    }

                    sessionExpired.send(Unit)
                }
                else -> {
                    _uiState.update { it.copy(isCancelling = false, cancelError = "Ошибка отмены") }
                }
            }
        }
    }
}