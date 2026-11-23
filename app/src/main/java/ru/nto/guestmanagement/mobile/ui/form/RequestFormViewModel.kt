package ru.nto.guestmanagement.mobile.ui.form

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
import ru.nto.guestmanagement.mobile.data.CreateRequestBody
import ru.nto.guestmanagement.mobile.data.RequestsApi
import ru.nto.guestmanagement.mobile.data.toApi
import ru.nto.guestmanagement.mobile.di.AppContainer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException

enum class FormValidationError {
    FULL_NAME,
    PHONE,
    ORGANIZATION,
    PURPOSE,
    DATE,
    TIME_FROM_FORMAT,
    TIME_FROM_PAST,
    TIME_TO_FORMAT,
    TIME_TO_ORDER
}

enum class FormCommonError {
    DUPLICATE,
    NETWORK,
    GENERAL
}

data class RequestFormState(
    val fullName: String = "",
    val phone: String = "",
    val organization: String = "",
    val purpose: String = "",
    val date: String = "",
    val timeFrom: String = "",
    val timeTo: String = "",
    val showErrors: Boolean = false,
    val isSubmitting: Boolean = false,
    val serverErrors: Map<String, String> = emptyMap(),
    val commonError: FormCommonError? = null
)

sealed interface RequestFormEvent {
    data object Created : RequestFormEvent
    data object SessionExpired : RequestFormEvent
}

private fun parseFormDate(value: String): LocalDate? {
    if (!Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}").matches(value)) {
        return null
    }

    return try {
        val day = value.substring(0, 2).toInt()
        val month = value.substring(3, 5).toInt()
        val year = value.substring(6, 10).toInt()

        LocalDate.of(year, month, day)
    } catch (e: DateTimeException) {
        null
    }
}

private fun parseFormTime(value: String): LocalTime? {
    if (!Regex("[0-9]{2}:[0-9]{2}").matches(value)) {
        return null
    }

    return try {
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(3, 5).toInt()

        LocalTime.of(hour, minute)
    } catch (e: DateTimeException) {
        null
    }
}

internal fun validateForm(
    state: RequestFormState,
    now: LocalDateTime
): Set<FormValidationError> {
    val errors = mutableSetOf<FormValidationError>()

    val fullName = state.fullName.trim()
    val phone = state.phone.trim()
    val organization = state.organization.trim()
    val purpose = state.purpose.trim()

    if (fullName.length !in 5..150) {
        errors.add(FormValidationError.FULL_NAME)
    }

    if (!Regex("\\+7[0-9]{10}").matches(phone)) {
        errors.add(FormValidationError.PHONE)
    }

    if (organization.length > 150) {
        errors.add(FormValidationError.ORGANIZATION)
    }

    if (purpose.length !in 5..200) {
        errors.add(FormValidationError.PURPOSE)
    }

    val date = parseFormDate(state.date)
    val timeFrom = parseFormTime(state.timeFrom)
    val timeTo = parseFormTime(state.timeTo)

    if (date == null) {
        errors.add(FormValidationError.DATE)
    }

    if (timeFrom == null) {
        errors.add(FormValidationError.TIME_FROM_FORMAT)
    } else if (date != null) {
        val validFrom = LocalDateTime.of(date, timeFrom)

        if (validFrom.isBefore(now)) {
            errors.add(FormValidationError.TIME_FROM_PAST)
        }
    }

    if (timeTo == null) {
        errors.add(FormValidationError.TIME_TO_FORMAT)
    } else if (timeFrom != null && !timeTo.isAfter(timeFrom)) {
        errors.add(FormValidationError.TIME_TO_ORDER)
    }

    return errors
}

class RequestFormViewModel(
    private val container: AppContainer
) : ViewModel() {

    private val api = RequestsApi(container.httpClient)

    private val _state = MutableStateFlow(RequestFormState())
    val state: StateFlow<RequestFormState> = _state.asStateFlow()

    private val _events = Channel<RequestFormEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun updateFullName(value: String) {
        updateField("fullName") {
            copy(fullName = value)
        }
    }

    fun updatePhone(value: String) {
        updateField("phone") {
            copy(phone = value)
        }
    }

    fun updateOrganization(value: String) {
        updateField("organization") {
            copy(organization = value)
        }
    }

    fun updatePurpose(value: String) {
        updateField("purpose") {
            copy(purpose = value)
        }
    }

    fun updateDate(value: String) {
        updateField(null) {
            copy(date = value)
        }
    }

    fun updateTimeFrom(value: String) {
        updateField("validFrom") {
            copy(timeFrom = value)
        }
    }

    fun updateTimeTo(value: String) {
        updateField("validTo") {
            copy(timeTo = value)
        }
    }

    private fun updateField(
        serverKey: String?,
        change: RequestFormState.() -> RequestFormState
    ) {
        _state.update { current ->
            current.change().copy(
                serverErrors = if (serverKey == null) {
                    current.serverErrors
                } else {
                    current.serverErrors - serverKey
                },
                commonError = null
            )
        }
    }

    private var created = false

    fun submit() {
        val current = _state.value

        if (current.isSubmitting || created) return

        _state.update {
            it.copy(
                showErrors = true,
                commonError = null
            )
        }

        val errors = validateForm(
            current,
            container.timeProvider.now()
        )

        if (errors.isNotEmpty()) return

        val date = parseFormDate(current.date) ?: return
        val timeFrom = parseFormTime(current.timeFrom) ?: return
        val timeTo = parseFormTime(current.timeTo) ?: return

        val body = CreateRequestBody(
            fullName = current.fullName.trim(),
            phone = current.phone.trim(),
            organization = current.organization.trim().ifEmpty { null },
            purpose = current.purpose.trim(),
            validFrom = LocalDateTime.of(date, timeFrom).toApi(),
            validTo = LocalDateTime.of(date, timeTo).toApi()
        )

        _state.update {
            it.copy(isSubmitting = true)
        }

        viewModelScope.launch {
            try {
                val code = container.session.current()

                if (code.isNullOrEmpty()) {
                    _state.update {
                        it.copy(isSubmitting = false)
                    }

                    _events.send(RequestFormEvent.SessionExpired)
                    return@launch
                }

                when (val result = api.create(code, body)) {
                    is ApiResult.Success -> {
                        created = true

                        _events.send(RequestFormEvent.Created)
                    }

                    ApiResult.Unauthorized -> {
                        container.session.clear()

                        _state.update {
                            it.copy(isSubmitting = false)
                        }

                        _events.send(RequestFormEvent.SessionExpired)
                    }

                    is ApiResult.FieldErrors -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                serverErrors = result.errors.filterKeys { key ->
                                    when (key) {
                                        "fullName" -> it.fullName == current.fullName
                                        "phone" -> it.phone == current.phone
                                        "organization" -> it.organization == current.organization
                                        "purpose" -> it.purpose == current.purpose
                                        "validFrom" -> it.timeFrom == current.timeFrom
                                        "validTo" -> it.timeTo == current.timeTo
                                        else -> true
                                    }
                                },
                                commonError = null
                            )
                        }
                    }

                    is ApiResult.Conflict -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                commonError = if (
                                    result.message == "Duplicate request"
                                ) {
                                    FormCommonError.DUPLICATE
                                } else {
                                    FormCommonError.GENERAL
                                }
                            )
                        }
                    }

                    ApiResult.Network -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                commonError = FormCommonError.NETWORK
                            )
                        }
                    }

                    is ApiResult.Failure -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                commonError = if (result.message?.startsWith("HTTP 5") == true)
                                    FormCommonError.NETWORK else FormCommonError.GENERAL
                            )
                        }
                    }

                    ApiResult.NotFound -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                commonError = FormCommonError.GENERAL
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        commonError = FormCommonError.GENERAL
                    )
                }
            }
        }
    }
}
