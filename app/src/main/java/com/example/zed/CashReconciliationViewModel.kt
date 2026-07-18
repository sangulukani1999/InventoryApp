package com.example.zed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CashReconciliationViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val cashService =
        GoogleSheetsCashService(
            application
        )

    private val _uiState =
        MutableStateFlow(
            CashReconciliationUiState()
        )

    val uiState: StateFlow<CashReconciliationUiState> =
        _uiState.asStateFlow()

    fun loadReconciliation(
        periodStartMillis: Long,
        periodEndMillis: Long
    ) {
        viewModelScope.launch {

            _uiState.value =
                _uiState.value.copy(
                    loading = true,
                    saving = false,
                    errorMessage = null,
                    saveSuccessful = false
                )

            try {
                val account =
                    GoogleSignIn
                        .getLastSignedInAccount(
                            getApplication()
                        )
                        ?: throw IllegalStateException(
                            "Please sign in with Google first."
                        )

                val summary =
                    cashService
                        .calculateCashReconciliation(
                            account =
                                account,

                            periodStartMillis =
                                periodStartMillis,

                            periodEndMillis =
                                periodEndMillis
                        )

                CashReconciliationRepository
                    .setSummary(
                        summary
                    )

                _uiState.value =
                    CashReconciliationUiState(
                        loading = false,
                        summary = summary
                    )

            } catch (exception: Exception) {

                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        errorMessage =
                            exception.message
                                ?: "Unable to calculate expected cash."
                    )
            }
        }
    }

    fun setActualDrawerCash(
        actualDrawerCash: Double
    ) {
        CashReconciliationRepository
            .setActualDrawerCash(
                actualDrawerCash
            )

        _uiState.value =
            _uiState.value.copy(
                summary =
                    CashReconciliationRepository
                        .summary
                        .value,

                errorMessage = null,
                saveSuccessful = false
            )
    }

    fun clearActualDrawerCash() {
        CashReconciliationRepository
            .clearActualDrawerCash()

        _uiState.value =
            _uiState.value.copy(
                summary =
                    CashReconciliationRepository
                        .summary
                        .value
            )
    }

    fun saveReconciliation() {
        val summary =
            CashReconciliationRepository
                .summary
                .value

        if (
            summary.actualDrawerCash == null
        ) {
            _uiState.value =
                _uiState.value.copy(
                    errorMessage =
                        "Enter the actual drawer cash first."
                )

            return
        }

        viewModelScope.launch {

            _uiState.value =
                _uiState.value.copy(
                    saving = true,
                    errorMessage = null,
                    saveSuccessful = false
                )

            try {
                val account =
                    GoogleSignIn
                        .getLastSignedInAccount(
                            getApplication()
                        )
                        ?: throw IllegalStateException(
                            "Please sign in with Google first."
                        )

                cashService.saveCashReconciliation(
                    account = account,
                    summary = summary,
                    reconciledBy =
                        account.email.orEmpty()
                )

                _uiState.value =
                    _uiState.value.copy(
                        saving = false,
                        saveSuccessful = true,
                        summary = summary
                    )

            } catch (exception: Exception) {

                _uiState.value =
                    _uiState.value.copy(
                        saving = false,
                        errorMessage =
                            exception.message
                                ?: "Unable to save reconciliation."
                    )
            }
        }
    }

    fun clearError() {
        _uiState.value =
            _uiState.value.copy(
                errorMessage = null
            )
    }

    fun consumeSaveSuccessful() {
        _uiState.value =
            _uiState.value.copy(
                saveSuccessful = false
            )
    }
}