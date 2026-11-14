package ru.nto.guestmanagement.mobile.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.RequestSummary
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.refresh.RefreshGate

data class RequestListState(
    val selectedFilter: RequestStatus? = null,
    val requests: List<RequestSummary> = emptyList(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val isOffline: Boolean = false,
    val hasError: Boolean = false
)

sealed interface RequestListEvent {
    data object SessionExpired : RequestListEvent
    data object LoggedOut : RequestListEvent
}

class RequestListViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val api = RequestsApi(container.httpClient)
    private val refreshGate = RefreshGate()

    private val _state = MutableStateFlow(RequestListState())
    val state: StateFlow<RequestListState> = _state.asStateFlow()

    private val _events = Channel<RequestListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()


    fun onResume() {
        refresh()
    }

    fun refresh() {
        val status = _state.value.selectedFilter

        refreshGate.launch(viewModelScope) {
            _state.update {
                it.copy(
                    isLoading = true,
                    hasError = false
                )
            }

            loadRequests(status)
        }
    }

    fun selectFilter(status: RequestStatus?) {
        if (_state.value.selectedFilter == status) return

        refreshGate.cancel()

        _state.update {
            it.copy(
                selectedFilter = status,
                requests = emptyList(),
                isLoading = false,
                hasLoaded = false,
                isOffline = false,
                hasError = false
            )
        }

        refresh()
    }

    private suspend fun loadRequests(status: RequestStatus?) {
        try {
            val code = container.session.current()

            if (code.isNullOrEmpty()) {
                handleSessionExpired()
                return
            }

            when (val result = api.list(code, status)) {

                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            requests = result.value,
                            isLoading = false,
                            hasLoaded = true,
                            isOffline = false,
                            hasError = false
                        )
                    }
                }

                ApiResult.Unauthorized -> {
                    container.session.clear()
                    handleSessionExpired()
                }

                ApiResult.Network,
                ApiResult.NotFound,
                is ApiResult.Failure,
                is ApiResult.Conflict,
                is ApiResult.FieldErrors -> {
                    handleError()
                }
            }
        } catch (e: Exception) {
            handleError()
        }
    }

    private fun handleError() {
        _state.update {
            if (it.hasLoaded) {
                it.copy(
                    isLoading = false,
                    isOffline = true,
                    hasError = false
                )
            } else {
                it.copy(
                    isLoading = false,
                    isOffline = false,
                    hasError = true
                )
            }
        }
    }

    private suspend fun handleSessionExpired() {
        _state.update {
            it.copy(isLoading = false)
        }

        _events.send(RequestListEvent.SessionExpired)
    }

    fun logout() {
        viewModelScope.launch {
            container.session.clear()
            _events.send(RequestListEvent.LoggedOut)
        }
    }

    override fun onCleared() {
        refreshGate.cancel()
        super.onCleared()
    }
}