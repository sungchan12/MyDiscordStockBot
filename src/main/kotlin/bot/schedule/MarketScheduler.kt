package bot.schedule

import bot.discord.buildMessages
import bot.model.Market
import bot.model.StockConfig
import bot.quote.YahooQuoteProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.JDA
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * 장 마감 시각에 맞춰 해당 장의 시세를 채널로 자동 발송한다.
 *
 * 장마다 마감 시각이 다르므로(KR 15:30 KST, US 16:00 ET) 장별로 루프를 하나씩 돌린다.
 * 발송 시각 계산은 [nextRunAt] 참고.
 *
 * 휴장일 처리:
 * - 주말은 [nextRunAt] 이 다음 평일로 건너뛴다.
 * - 공휴일은 별도 목록 없이, 조회한 시세의 거래일이 오늘이 아니면(= 오늘 거래가 없었으면)
 *   발송을 건너뛰는 방식으로 걸러낸다.
 */
class MarketScheduler(
    private val jda: JDA,
    private val channelId: String,
    private val stocks: List<StockConfig>,
    private val quoteProvider: YahooQuoteProvider,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val logger = LoggerFactory.getLogger(MarketScheduler::class.java)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 종목이 있는 장에 대해서만 발송 루프를 시작한다. */
    fun start() {
        val markets = Market.entries.filter { market -> stocks.any { it.market == market } }
        markets.forEach { market ->
            logger.info("{} 장 마감 알림 예약: 다음 발송 {}", market, nextRunAt(market, now()))
            scope.launch { runLoop(market) }
        }
    }

    fun stop() = scope.cancel()

    private suspend fun runLoop(market: Market) {
        while (true) {
            delay(Duration.between(now(), nextRunAt(market, now())).toMillis().milliseconds)
            runCatching { publish(market) }
                .onFailure { logger.warn("{} 장 마감 알림 발송 실패", market, it) }
        }
    }

    private suspend fun publish(market: Market) {
        val channel = jda.getTextChannelById(channelId)
        if (channel == null) {
            logger.warn("채널 {} 을(를) 찾을 수 없어 발송을 건너뜁니다", channelId)
            return
        }

        val snapshot = quoteProvider.fetch(stocks.filter { it.market == market }).onlyMarket(market)

        // 거래일이 거래소 현지 기준 오늘이 아니면 휴장일로 보고 보내지 않는다.
        // (전 종목 조회 실패로 거래일을 모르는 경우도 마찬가지)
        val tradingDate = snapshot.latestTradingDate(market)
        val today = now().withZoneSameInstant(zoneOf(market)).toLocalDate()
        if (tradingDate != today) {
            logger.info("{} 휴장일로 판단해 발송 생략 (최근 거래일: {})", market, tradingDate ?: "확인 불가")
            return
        }

        buildMessages(snapshot).forEach { channel.sendMessage(it).queue() }
        logger.info("{} 장 마감 알림 발송 완료 (#{})", market, channel.name)
    }

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}