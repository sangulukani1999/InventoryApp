package com.example.zed

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CashReconciliationRepository {

    private val _summary =
        MutableStateFlow(
            CashReconciliationSummary()
        )

    val summary: StateFlow<CashReconciliationSummary> =
        _summary.asStateFlow()

    fun setSummary(
        summary: CashReconciliationSummary
    ) {
        _summary.value = summary
    }

    fun setActualDrawerCash(
        actualDrawerCash: Double
    ) {
        require(actualDrawerCash >= 0.0) {
            "Actual drawer cash cannot be negative."
        }

        val current = _summary.value

        _summary.value = current.copy(
            actualDrawerCash = actualDrawerCash,
            cashVariance =
                actualDrawerCash - current.expectedCash
        )
    }

    fun clearActualDrawerCash() {
        _summary.value = _summary.value.copy(
            actualDrawerCash = null,
            cashVariance = null
        )
    }

    fun clear() {
        _summary.value =
            CashReconciliationSummary()
    }
}