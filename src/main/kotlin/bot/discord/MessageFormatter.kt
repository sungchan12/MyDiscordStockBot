package bot.discord

import bot.model.Market
import bot.model.MarketSnapshot
import bot.model.QuoteResult
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * MarketSnapshot 을 Discord 로 보낼 문자열로 변환한다.
 *
 * 열 정렬을 유지하려면 고정폭 글꼴이 필요하므로 전체를 코드블록(```)으로 감싼다.
 */
fun formatSnapshot(snapshot: MarketSnapshot): String {
    val sections = listOfNotNull(
        formatSection(snapshot, Market.KR, "🇰🇷 국내"),
        formatSection(snapshot, Market.US, "🇺🇸 해외  (환율 ${decimal(snapshot.exchangeRate)})"),
    )
    return "```\n" + sections.joinToString("\n\n") + "\n```"
}


private fun formatSection(snapshot: MarketSnapshot, market: Market, country: String): String? {
    val results = snapshot.forMarket(market)
    if (results.isEmpty()) return null
    val (rows, aligns) = when (market) {
        Market.KR -> results.map { koreaRow(it) } to listOf(Align.LEFT, Align.RIGHT, Align.LEFT)
        Market.US -> results.map { usRow(it, snapshot.exchangeRate) } to
                listOf(Align.LEFT, Align.RIGHT, Align.RIGHT, Align.LEFT)
    }
    return country + "\n" + renderTable(rows, aligns)
}

private fun koreaRow(result: QuoteResult): List<String> {
    when (result) {
        is QuoteResult.Success -> {
            val q = result.quote
            return listOf(q.config.name, "${wonFormatting(q.price)}원", change(q.changePercent))
        }
        is QuoteResult.Failure -> {
            return listOf(result.config.name, "조회실패 : ${result.reason}")
        }
    }
}

private fun usRow(result: QuoteResult, currentWon: Double): List<String> {
    when (result) {
        is QuoteResult.Success -> {
            val q = result.quote
            return listOf(
                q.config.ticker,
                usdFormatting(q.price),
                "W${wonFormatting(q.price * currentWon)}",
                change(q.changePercent)
                )
        }
        is QuoteResult.Failure -> {
            return listOf(result.config.ticker, "조회실패 : ${result.reason}")
        }
    }
}

private fun wonFormatting(price: Double): String {
    return "%,d".format(price.roundToLong())
}

private fun usdFormatting(amount: Double): String = "$%,.2f".format(amount)

private fun change(changePercent: Double): String {
    val triangle = if (changePercent >= 0) "▲" else "▼"
    return "%s %.2f%%".format(triangle, abs(changePercent))
}

private fun decimal(value: Double): String = "%,.2f".format(value)

private enum class Align { LEFT, RIGHT }

private fun renderTable(rows: List<List<String>>, aligns: List<Align>, gap: String = "   "): String {
    val widths = IntArray(aligns.size) { col -> rows.maxOf { displayWidth(it[col]) } }
    return rows.joinToString("\n") { row ->
        row.mapIndexed { col, cell ->
            val pad = " ".repeat((widths[col] - displayWidth(cell)).coerceAtLeast(0))
            if (aligns[col] == Align.RIGHT) pad + cell else cell + pad
        }.joinToString(gap).trimEnd()
    }
}

/** 고정폭 글꼴 기준 표시 폭. 한글 등 전각 문자는 2칸으로 센다. */
private fun displayWidth(text: String): Int = text.sumOf { ch ->
    val code = ch.code
    val wide = code in 0x1100..0x115F ||   // 한글 자모
            code in 0x2E80..0xA4CF ||          // CJK 부수 ~ 확장
            code in 0xAC00..0xD7A3 ||          // 한글 완성형
            code in 0xF900..0xFAFF ||          // CJK 호환 한자
            code in 0xFF00..0xFF60             // 전각 기호
    val charWidth: Int = if (wide) 2 else 1
    charWidth
}

/**
 * 스냅샷을 Discord 로 보낼 메시지 목록으로 만든다.
 *
 * 첫 번째는 항상 시세 표이고, 그 뒤에 상승/하락 특수 메시지 블록이
 * 내용이 있을 때만 붙는다. 보내는 방법(슬래시 커맨드 응답 / 채널 직접 발송)은
 * 호출하는 쪽이 정한다.
 */
fun buildMessages(snapshot: MarketSnapshot): List<String> {
    val blocks = specialMessageBlock(snapshot)
        .filter { it.isNotEmpty() }
        .map { "```\n" + it.joinToString("\n") + "\n```" }
    return listOf(formatSnapshot(snapshot)) + blocks
}

/**
 * 상승(+)한 종목들의 message 를 하나의 코드블록으로 묶는다.
 * 조회 표 다음에 별도 메시지로 보낼 용도이며, 해당 종목이 없으면 null.
 */
fun specialMessageBlock(snapshot: MarketSnapshot): List<List<String>> {
    val upMessage = mutableListOf<String>()
    val downMessage = mutableListOf<String>()
    for (r in snapshot.results) {
        if (r !is QuoteResult.Success) continue
        val percent = r.quote.changePercent
        when {
            percent > 0 -> r.config.upMessage?.let { upMessage.add(it) }
            percent < 0 -> r.config.downMessage?.let { downMessage.add(it) }
        }
    }
    return listOf(upMessage, downMessage)
}
