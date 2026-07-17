package bot.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

enum class Market {KR, US}

@Serializable
data class StockConfig(
    val ticker: String,
    val name: String,
    val market: Market,
    val upMessage: String? = null,
    val downMessage: String? = null
)

@Serializable
data class StockList(
    val stocks: List<StockConfig>
)

data class Quote(
    val config: StockConfig,
    val price: Double,
    val changePercent: Double,
    val tradingDate: LocalDate,
)

sealed interface QuoteResult {
    val config: StockConfig

    data class Success(val quote: Quote) : QuoteResult {
        override val config = quote.config
    }
    data class Failure(
        override val config: StockConfig,
        val reason: String,
    ) : QuoteResult
}

data class MarketSnapshot(
    val results: List<QuoteResult>,
    val exchangeRate: Double
) {
    fun forMarket(market: Market) = results.filter { it.config.market == market }

    /** 한 장의 종목만 남긴 스냅샷. 장 마감 알림처럼 해당 장만 다룰 때 쓴다. */
    fun onlyMarket(market: Market) = copy(results = forMarket(market))

    fun latestTradingDate(market: Market): LocalDate? =
        forMarket(market).filterIsInstance<QuoteResult.Success>()
            .maxOfOrNull { it.quote.tradingDate }
}