package bot.schedule

import bot.model.Market
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 장별 마감 시각과 발송 시각 계산. (시간대·주말 처리를 한곳에 모아 둔다)
 */

/** 마감 후 종가가 시세에 반영될 때까지 두는 여유. */
val REPORT_DELAY: Duration = Duration.ofMinutes(5)

private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

/** 거래소가 속한 시간대. 마감 시각·거래일 판단의 기준이 된다. */
fun zoneOf(market: Market): ZoneId = when (market) {
    Market.KR -> ZoneId.of("Asia/Seoul")
    Market.US -> ZoneId.of("America/New_York")
}

/** 거래소 현지 기준 정규장 마감 시각. */
fun closeTimeOf(market: Market): LocalTime = when (market) {
    Market.KR -> LocalTime.of(15, 30)
    Market.US -> LocalTime.of(16, 0)
}

/**
 * [now] 이후 가장 가까운 발송 시각(= 마감 + [REPORT_DELAY]).
 *
 * 주말은 다음 평일로 건너뛴다. 공휴일은 여기서 알 수 없으므로,
 * 발송 직전에 거래일을 확인해 거르는 쪽이 담당한다.
 */
fun nextRunAt(market: Market, now: ZonedDateTime): ZonedDateTime {
    val zone = zoneOf(market)
    val local = now.withZoneSameInstant(zone)
    val reportTime = closeTimeOf(market).plusMinutes(REPORT_DELAY.toMinutes())

    var date = local.toLocalDate()
    while (true) {
        val runAt = date.atTime(reportTime).atZone(zone)
        if (date.dayOfWeek !in WEEKEND && runAt.isAfter(local)) return runAt
        date = date.plusDays(1)
    }
}