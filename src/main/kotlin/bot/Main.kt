package bot

import bot.discord.StockBot
import bot.model.StockConfig
import bot.model.StockList
import bot.quote.YahooQuoteProvider
import bot.schedule.MarketScheduler
import com.charleskorn.kaml.Yaml
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("bot.Main")

fun main() {
    val discordToken = env("DISCORD_TOKEN")
    val channelId = env("DISCORD_CHANNEL_ID")
    val stocksPath = env("STOCKS_FILE")
    val stocks = loadStocks(stocksPath)

    logger.info("종목 {}개 로드: {}", stocks.size, stocks.joinToString { it.name })

    val quoteProvider = YahooQuoteProvider()
    val bot = StockBot(stocks, quoteProvider)

    val jda = JDABuilder.createLight(discordToken, emptyList<GatewayIntent>())
        .addEventListeners(bot)
        .build()
        .awaitReady()

    // /주식 명령을 봇이 들어가 있는 모든 서버에 등록한다. (길드 명령은 즉시 반영됨)
    jda.guilds.forEach { guild ->
        guild.upsertCommand(COMMAND_NAME, "등록된 종목의 현재 시세를 조회합니다").queue()
    }
    logger.info("봇 준비 완료. /{} 명령 대기 중 ({}개 서버)", COMMAND_NAME, jda.guilds.size)

    // 장 마감 시각마다 해당 장의 시세를 채널로 자동 발송한다.
    val scheduler = MarketScheduler(jda, channelId, stocks, quoteProvider)
    scheduler.start()

    // 프로세스 종료 시 자원 정리. (JDA 스레드가 살아있어 main 이 반환돼도 봇은 계속 동작)
    Runtime.getRuntime().addShutdownHook(Thread {
        scheduler.stop()
        quoteProvider.close()
        jda.shutdown()
    })
}

private const val COMMAND_NAME = "주식"

private fun loadStocks(stocksPath: String): List<StockConfig> {
    val file = File(stocksPath)
    require(file.exists()) { "종목 설정 파일을 찾을 수 없습니다 : ${file.absolutePath} "}
    val parsed = Yaml.default.decodeFromString(StockList.serializer(), file.readText())
    require(parsed.stocks.isNotEmpty()) {"종목이 없습니다. 추가해 주세요 $stocksPath"}
    return parsed.stocks
}

private fun env(key: String) =
    System.getenv(key) ?: error("환경변수 $key 가 설정이 안돼있습니다.")