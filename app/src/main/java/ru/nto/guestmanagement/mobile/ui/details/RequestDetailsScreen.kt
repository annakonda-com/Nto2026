package ru.nto.guestmanagement.mobile.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ru.nto.guestmanagement.mobile.data.RequestStatus
import ru.nto.guestmanagement.mobile.data.formatDateTime
import ru.nto.guestmanagement.mobile.di.AppContainer
import ru.nto.guestmanagement.mobile.ui.common.StatusLabel
import ru.nto.guestmanagement.mobile.ui.TestIds
import ru.nto.guestmanagement.mobile.ui.common.containerViewModel
import ru.nto.guestmanagement.mobile.ui.refresh.ResumeEffect

@Composable
fun RequestDetailsScreen(
    container: AppContainer,
    requestId: Long,
    onBack: () -> Unit,
    onSessionExpired: () -> Unit,
) {
    val viewModel: RequestDetailsViewModel =
        containerViewModel {
            RequestDetailsViewModel(requestId, container)
        }

    val currentOnSessionExpired by
    rememberUpdatedState(onSessionExpired)

    LaunchedEffect(viewModel) {
        viewModel.sessionExpiredEvents.collect {
            currentOnSessionExpired()
        }
    }

    val state by viewModel.uiState.collectAsState()

    ResumeEffect {
        viewModel.loadDetails()
    }

    if (state.showCancelDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancelDialog() },
            title = { Text("Отмена заявки") },
            text = { Text("Вы уверены, что хотите отменить заявку?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.cancelRequest() },
                    modifier = Modifier.testTag(TestIds.DETAILS_CANCEL_CONFIRM)
                ) {
                    Text("Отменить заявку")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.dismissCancelDialog() },
                    modifier = Modifier.testTag(TestIds.DETAILS_CANCEL_DISMISS)
                ) {
                    Text("Назад")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(TestIds.DETAILS_BACK)) {
                Text("Назад")
            }
            Button(
                onClick = { viewModel.loadDetails() },
                modifier = Modifier.testTag(TestIds.DETAILS_REFRESH)
            ) {
                Text("Обновить")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (state.isLoading && state.details == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.error != null && state.details == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(TestIds.DETAILS_ERROR)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.loadDetails() },
                    modifier = Modifier.testTag(TestIds.DETAILS_RETRY)
                ) {
                    Text("Повторить")
                }
            }
        } else {
            state.details?.let { details ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (state.error != null) {
                        Text(
                            text = state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .testTag(TestIds.DETAILS_OFFLINE)
                        )
                    }

                    if (state.cancelError != null) {
                        Text(
                            text = state.cancelError!!,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .testTag(TestIds.DETAILS_CANCEL_ERROR)
                        )
                    }

                    DetailRow("ID", details.id.toString(), TestIds.DETAILS_ID)
                    DetailRow("ФИО", details.fullName, TestIds.DETAILS_NAME)
                    DetailRow("Телефон", details.phone, TestIds.DETAILS_PHONE)

                    if (!details.organization.isNullOrBlank()) {
                        DetailRow("Организация", details.organization!!, TestIds.DETAILS_ORGANIZATION)
                    }

                    DetailRow("Цель", details.purpose, TestIds.DETAILS_PURPOSE)
                    DetailRow("Начало", formatDateTime(details.validFrom), TestIds.DETAILS_VALID_FROM)
                    DetailRow("Окончание", formatDateTime(details.validTo), TestIds.DETAILS_VALID_TO)

                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Статус: ", style = MaterialTheme.typography.bodyLarge)
                        StatusLabel(
                            status = details.status,
                            tag = TestIds.DETAILS_STATUS
                        )
                    }

                    DetailRow("Создана", formatDateTime(details.createdAt), TestIds.DETAILS_CREATED_AT)
                    DetailRow("Обновлена", formatDateTime(details.updatedAt), TestIds.DETAILS_UPDATED_AT)

                    if (details.status == RequestStatus.REJECTED && !details.comment.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Комментарий охраны:", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = details.comment!!,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag(TestIds.DETAILS_COMMENT)
                        )
                    }
                }

                if (details.status == RequestStatus.PENDING) {
                    Button(
                        onClick = { viewModel.showCancelDialog() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .testTag(TestIds.DETAILS_CANCEL),
                        enabled = !state.isCancelling,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (state.isCancelling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onError
                            )
                        } else {
                            Text("Отменить заявку")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, testTag: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(testTag))
    }
}