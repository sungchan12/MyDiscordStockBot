package bot.discord

import bot.model.StockConfig
import bot.quote.YahooQuoteProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.LoggerFactory

/**
 * `/주식` 슬래시 커맨드를 처리하는 리스너.
 *
 * 명령이 들어오면 등록된 종목의 시세를 조회해 같은 채널에 답한다.
 * 조회에 3초 이상 걸릴 수 있으므로 먼저 [deferReply][SlashCommandInteractionEvent.deferReply]
 * 로 응답을 지연시키고, 코루틴에서 조회를 끝낸 뒤 결과로 원본 응답을 수정한다.
 */
class StockBot(
    private val stocks: List<StockConfig>,
    private val quoteProvider: YahooQuoteProvider,
) : ListenerAdapter() {

    private val logger = LoggerFactory.getLogger(StockBot::class.java)

    // 시세 조회(네트워크)는 JDA 이벤트 스레드를 막지 않도록 별도 스코프에서 돌린다.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != COMMAND) return

        event.deferReply().queue()
        logger.info("/{} 요청: {} (#{})", COMMAND, event.user.name, event.channel.name)

        scope.launch {
            val hook = event.hook
            runCatching {
                val snapshot = quoteProvider.fetch(stocks)
                // 먼저 시세 표(코드블록)를 보내고,
                hook.editOriginal(formatSnapshot(snapshot)).queue()
                // 상승/하락 종목의 특수 메시지를 각각 코드블록으로 묶어 이어 보낸다. (빈 그룹은 건너뜀)
                val (upMessages, downMessages) = specialMessageBlock(snapshot)
                listOf(upMessages, downMessages)
                    .filter { it.isNotEmpty() }
                    .forEach { hook.sendMessage("```\n" + it.joinToString("\n") + "\n```").queue() }
            }.onFailure { e ->
                logger.warn("시세 조회 실패", e)
                hook.editOriginal("⚠️ 시세 조회 중 오류가 발생했습니다: ${e.message}").queue()
            }
        }
    }

    private companion object {
        const val COMMAND = "주식"
    }
}