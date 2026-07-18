package com.example.zed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class StockHomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val sheetsService =
        GoogleSheetsStockTakeService(application)

    private val loading = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<StockHomeUiState> =
        combine(
            StockTakeRepository.products,
            StockTakeRepository.session,
            loading,
            errorMessage
        ) { products, session, isLoading, error ->

            val countedProducts = products.filter { it.isCounted }
            val remainingProducts = products.filter { !it.isCounted }

            val overallPercentage = if (products.isNotEmpty()) {
                (
                        countedProducts.size.toDouble() /
                                products.size.toDouble() *
                                100.0
                        )
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

            val locationProducts = if (
                session?.currentLocationId.isNullOrBlank()
            ) {
                emptyList()
            } else {
                StockTakeRepository.productsAtLocation(
                    session?.currentLocationId.orEmpty()
                )
            }

            val locationCounted = locationProducts.count {
                it.isCounted
            }

            val locationPercentage =
                if (locationProducts.isNotEmpty()) {
                    (
                            locationCounted.toDouble() /
                                    locationProducts.size.toDouble() *
                                    100.0
                            )
                        .toInt()
                        .coerceIn(0, 100)
                } else {
                    0
                }

            val mismatches = countedProducts.filter {
                it.variance != 0.0
            }

            StockHomeUiState(
                loading = isLoading,
                errorMessage = error,

                status = session?.status
                    ?: StockTakeStatus.NOT_STARTED,

                type = session?.type
                    ?: StockTakeType.WEEKLY,

                totalProducts = products.size,
                countedProducts = countedProducts.size,
                remainingProducts = remainingProducts.size,
                progressPercentage = overallPercentage,

                currentLocationId =
                    session?.currentLocationId.orEmpty(),

                currentAisle =
                    session?.currentAisle.orEmpty(),

                currentRack =
                    session?.currentRack.orEmpty(),

                currentShelf =
                    session?.currentShelf.orEmpty(),

                locationTotal = locationProducts.size,
                locationCounted = locationCounted,
                locationProgressPercentage = locationPercentage,

                stockVarianceUnits =
                    countedProducts.sumOf { it.variance },

                mismatchedProducts = mismatches.size
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StockHomeUiState()
        )

    fun loadWeeklyStockTake(
        periodStartMillis: Long,
        periodEndMillis: Long
    ) {
        loadStockTake(
            type = StockTakeType.WEEKLY,
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis
        )
    }

    fun loadMonthlyStockTake(
        periodStartMillis: Long,
        periodEndMillis: Long
    ) {
        loadStockTake(
            type = StockTakeType.MONTHLY,
            periodStartMillis = periodStartMillis,
            periodEndMillis = periodEndMillis
        )
    }

    private fun loadStockTake(
        type: StockTakeType,
        periodStartMillis: Long,
        periodEndMillis: Long
    ) {
        viewModelScope.launch {
            loading.value = true
            errorMessage.value = null

            try {
                val account = GoogleSignIn.getLastSignedInAccount(
                    getApplication()
                ) ?: throw IllegalStateException(
                    "Please sign in with Google first."
                )

                val currentSession =
                    StockTakeRepository.session.value

                val session = if (
                    currentSession != null &&
                    currentSession.type == type &&
                    currentSession.periodStartMillis == periodStartMillis &&
                    currentSession.periodEndMillis == periodEndMillis
                ) {
                    currentSession.copy(
                        status = StockTakeStatus.LOADING,
                        errorMessage = null
                    )
                } else {
                    StockTakeSession(
                        sessionId = UUID.randomUUID().toString(),
                        periodStartMillis = periodStartMillis,
                        periodEndMillis = periodEndMillis,
                        type = type,
                        status = StockTakeStatus.LOADING,
                        createdBy = account.email.orEmpty()
                    )
                }

                StockTakeRepository.setSession(session)

                val products = when (type) {
                    StockTakeType.WEEKLY -> {
                        sheetsService.loadWeeklyStockTake(
                            account = account,
                            periodStartMillis = periodStartMillis,
                            periodEndMillis = periodEndMillis
                        )
                    }

                    StockTakeType.MONTHLY,
                    StockTakeType.RECOVERY -> {
                        sheetsService.loadMonthlyStockTake(
                            account = account,
                            periodStartMillis = periodStartMillis,
                            periodEndMillis = periodEndMillis
                        )
                    }
                }

                StockTakeRepository.setProducts(products)

                val firstIncompleteProduct =
                    products.firstOrNull { !it.isCounted }
                        ?: products.firstOrNull()

                val firstLocation =
                    firstIncompleteProduct?.primaryLocation

                StockTakeRepository.setSession(
                    session.copy(
                        status = if (
                            products.isNotEmpty() &&
                            products.all { it.isCounted }
                        ) {
                            StockTakeStatus.COMPLETED
                        } else {
                            StockTakeStatus.IN_PROGRESS
                        },

                        currentLocationId =
                            firstLocation?.locationId.orEmpty(),

                        currentAisle =
                            firstLocation?.aisle.orEmpty(),

                        currentRack =
                            firstLocation?.rack.orEmpty(),

                        currentShelf =
                            firstLocation?.shelf.orEmpty(),

                        errorMessage = null
                    )
                )
            } catch (exception: Exception) {
                val message = exception.message
                    ?: "Unable to load stock take."

                errorMessage.value = message

                val current = StockTakeRepository.session.value

                if (current != null) {
                    StockTakeRepository.setSession(
                        current.copy(
                            status = StockTakeStatus.FAILED,
                            errorMessage = message
                        )
                    )
                }
            } finally {
                loading.value = false
            }
        }
    }

    fun retry() {
        val session = StockTakeRepository.session.value ?: return

        loadStockTake(
            type = session.type,
            periodStartMillis = session.periodStartMillis,
            periodEndMillis = session.periodEndMillis
        )
    }
}