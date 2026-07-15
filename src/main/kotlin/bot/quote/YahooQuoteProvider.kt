package bot.quote

import bot.model.Market
import bot.model.MarketSnapshot
import bot.model.Quote
import bot.model.QuoteResult
import bot.model.StockConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Yahoo Finance 비공식 시세 API(v8 chart)로 실제 시세와 환율을 가져온다.
 *
 * - US 종목: 티커 그대로 (예: NVDA)
 * - KR 종목: 야후는 거래소 접미사가 필요하므로 .KS(코스피) → .KQ(코스닥) 순으로 시도한다.
 * - 환율(USD→KRW): KRW=X 심볼로 조회한다.
 *
 * 조회에 실패한 종목은 [QuoteResult.Failure] 로 담아 나머지 종목에는 영향을 주지 않는다.
 * 사용이 끝나면 [close] 로 HTTP 클라이언트를 정리한다. (use { } 권장)
 */
class YahooQuoteProvider : AutoCloseable {

    private val logger = LoggerFactory.getLogger(YahooQuoteProvider::class.java)

    private val client = HttpClient(CIO) {
        // 잘못된 심볼은 404 를 주므로 예외 대신 상태 코드로 판단한다.
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        defaultRequest {
            // User-Agent 가 없으면 야후가 401/429 로 막는 경우가 있어 붙여준다.
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
    }

    /** 모든 종목 시세와 환율을 병렬로 조회해 하나의 스냅샷으로 만든다. */
    suspend fun fetch(stocks: List<StockConfig>): MarketSnapshot = coroutineScope {
        val quotes = stocks.map { config -> async { quoteFor(config) } }
        val rate = async { fetchExchangeRate() }
        MarketSnapshot(quotes.awaitAll(), rate.await())
    }

    private suspend fun quoteFor(config: StockConfig): QuoteResult {
        val symbols = when (config.market) {
            Market.US -> listOf(config.ticker)
            Market.KR -> listOf("${config.ticker}.KS", "${config.ticker}.KQ")
        }

        val meta = symbols.firstNotNullOfOrNull { symbol ->
            runCatching { fetchMeta(symbol) }
                .onFailure { logger.warn("{} 조회 중 오류: {}", symbol, it.message) }
                .getOrNull()
        } ?: return QuoteResult.Failure(config, "시세 조회 실패")

        val price = meta.regularMarketPrice!!  // fetchMeta 가 null 이 아니면 값이 있음이 보장됨
        val prevClose = meta.chartPreviousClose ?: meta.previousClose
        val changePercent =
            if (prevClose != null && prevClose != 0.0) (price - prevClose) / prevClose * 100 else 0.0
        val tradingDate = meta.tradingDate() ?: LocalDate.now()

        return QuoteResult.Success(Quote(config, price, changePercent, tradingDate))
    }

    /** USD→KRW 환율. 조회 실패 시 기본값으로 폴백한다. */
    private suspend fun fetchExchangeRate(): Double {
        val rate = runCatching { fetchMeta("KRW=X")?.regularMarketPrice }
            .onFailure { logger.warn("환율 조회 실패: {}", it.message) }
            .getOrNull()
        if (rate == null) logger.warn("환율 조회 실패, 기본값 {} 사용", DEFAULT_EXCHANGE_RATE)
        return rate ?: DEFAULT_EXCHANGE_RATE
    }

    /** 심볼 하나를 조회한다. 응답이 없거나 현재가가 없으면 null. */
    private suspend fun fetchMeta(symbol: String): Meta? {
        val response = client.get(CHART_URL + symbol)
        if (!response.status.isSuccess()) return null
        val meta = response.body<ChartResponse>().chart.result?.firstOrNull()?.meta
        return meta?.takeIf { it.regularMarketPrice != null }
    }

    override fun close() = client.close()

    // --- Yahoo chart 응답 (필요한 필드만) ---

    @Serializable
    private data class ChartResponse(val chart: Chart)

    @Serializable
    private data class Chart(val result: List<ChartResult>? = null)

    @Serializable
    private data class ChartResult(val meta: Meta)

    @Serializable
    private data class Meta(
        val regularMarketPrice: Double? = null,
        val chartPreviousClose: Double? = null,
        val previousClose: Double? = null,
        val regularMarketTime: Long? = null,
        val exchangeTimezoneName: String? = null,
    ) {
        /** 거래소 현지 시각 기준 거래일. */
        fun tradingDate(): LocalDate? = regularMarketTime?.let { epochSec ->
            val zone = exchangeTimezoneName?.let(ZoneId::of) ?: ZoneId.of("UTC")
            Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate()
        }
    }

    private companion object {
        const val CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        const val DEFAULT_EXCHANGE_RATE = 1_390.0
    }
}