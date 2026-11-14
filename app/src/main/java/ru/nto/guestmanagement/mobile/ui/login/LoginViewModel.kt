package ru.nto.guestmanagement.mobile.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.nto.guestmanagement.mobile.R
import ru.nto.guestmanagement.mobile.data.ApiResult
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.di.AppContainer

sealed interface LoginState {
    object InitialChecking : LoginState
    object Idle : LoginState
    object Authorized : LoginState
    object Loading : LoginState
    data class Error(val messageResId: Int) : LoginState
}

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    private val api = RequestsApi(container.httpClient)

    var codeInput = MutableStateFlow("")
        private set

    private val _state = MutableStateFlow<LoginState>(LoginState.InitialChecking)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    init {
        checkExistingSession()
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            val savedCode = container.session.current()
            if (!savedCode.isNullOrEmpty()) {
                _state.value = LoginState.Authorized
            } else {
                _state.value = LoginState.Idle
            }
        }
    }

    fun onCodeChanged(newCode: String) {
        codeInput.value = newCode
        if (_state.value is LoginState.Error) {
            _state.value = LoginState.Idle
        }
    }

    fun isCodeValid(code: String): Boolean {
        return code.length == 4 && code.all {
            it in 'A'..'Z' ||
                    it in 'a'..'z' ||
                    it in '0'..'9'
        }
    }

    fun login(onSuccess: () -> Unit) {
        val code = codeInput.value
        if (!isCodeValid(code)) return

        if (_state.value is LoginState.Loading) return

        viewModelScope.launch {
            _state.value = LoginState.Loading
            when (val result = api.auth(code)) {
                is ApiResult.Success -> {
                    container.session.save(code)
                    _state.value = LoginState.Idle
                    onSuccess()
                }
                is ApiResult.Unauthorized -> {
                    _state.value = LoginState.Error(R.string.login_error_unknown_code)
                }
                is ApiResult.FieldErrors, is ApiResult.Failure -> {
                    _state.value = LoginState.Error(R.string.login_error_unknown_code)
                }
                is ApiResult.Network -> {
                    _state.value = LoginState.Error(R.string.login_error_network)
                }
                else -> {
                    _state.value = LoginState.Error(R.string.login_error_other)
                }
            }
        }
    }
}