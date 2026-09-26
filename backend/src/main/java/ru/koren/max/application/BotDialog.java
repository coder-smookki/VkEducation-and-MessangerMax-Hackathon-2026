package ru.koren.max.application;

import java.util.Map;
import java.util.UUID;
import ru.koren.cases.application.CaseService;
import ru.koren.identity.persistence.MaxUserProvisioner;
import ru.koren.max.persistence.DialogRepository;
import ru.koren.notifications.persistence.OutboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bot calls the same application commands as REST: no second set of rules, no second authority.
 * Replies go through the outbox, so no HTTP happens inside this transaction and a retried update
 * produces the same reply key instead of a second message.
 */
public class BotDialog implements UpdateHandler {

    static final String REPLY_EVENT = "BOT_REPLY";

    private static final String HELP = """
            КОРЕНЬ на связи. Что я умею:
            «мои обращения» - сколько у вас обращений;
            «/start» - это сообщение.
            Обращения, ответы по пунктам и история - в приложении.""";

    private final CaseService cases;
    private final MaxUserProvisioner users;
    private final DialogRepository dialogs;
    private final OutboxRepository outbox;
    private final JsonMapper json = JsonMapper.builder().build();

    public BotDialog(CaseService cases, MaxUserProvisioner users, DialogRepository dialogs,
                     OutboxRepository outbox) {
        this.cases = cases;
        this.users = users;
        this.dialogs = dialogs;
        this.outbox = outbox;
    }

    @Override
    public void handle(String eventKey, String payload) {
        JsonNode update = json.readTree(payload);
        long maxUserId = senderId(update);
        String text = text(update).trim();
        UUID actor = users.findOrCreate(maxUserId);
        dialogs.setIdle(actor);
        reply(eventKey, actor, answerFor(actor, text));
    }

    private String answerFor(UUID actor, String text) {
        String command = text.toLowerCase(java.util.Locale.ROOT);
        if (command.startsWith("/start")) return HELP;
        if (command.startsWith("мои обращения") || command.startsWith("/cases")) {
            int count = cases.listCases(actor, null, null, null, 50).items().size();
            if (count == 0) return "У вас пока нет обращений. Новое можно создать в приложении.";
            // Numbers and texts stay out of MAX: the card re-checks access on every read.
            return "У вас обращений: " + count + ". Откройте приложение, чтобы посмотреть их.";
        }
        return "Пока я понимаю «/start» и «мои обращения». Остальное - в приложении.";
    }

    private void reply(String eventKey, UUID recipient, String text) {
        outbox.enqueueReply(eventKey + ":reply", recipient, REPLY_EVENT,
            json.writeValueAsString(Map.of("text", text)));
    }

    /**
     * MAX's real update envelope is not confirmed against a live bot yet, so several shapes are
     * accepted and an unrecognised one is refused rather than guessed: the event then stays in the
     * inbox, visible, instead of being answered with nonsense. Confirm the paths on real traffic.
     */
    private static long senderId(JsonNode update) {
        long[] found = {0};
        if (pick(update, found, "message", "sender", "user_id")
            || pick(update, found, "message", "from", "id")
            || pick(update, found, "sender", "user_id")
            || pick(update, found, "from", "id")
            || pick(update, found, "user_id")) {
            return found[0];
        }
        throw new IllegalStateException("No sender in update");
    }

    private static boolean pick(JsonNode node, long[] out, String... path) {
        JsonNode current = node;
        for (String key : path) {
            if (current == null) return false;
            current = current.get(key);
        }
        if (current == null || !current.isNumber()) return false;
        out[0] = current.asLong();
        return true;
    }

    private static String text(JsonNode update) {
        for (String[] path : new String[][] {{"message", "body", "text"}, {"message", "text"}, {"text"}}) {
            JsonNode current = update;
            for (String key : path) {
                if (current == null) break;
                current = current.get(key);
            }
            if (current != null && current.isString()) return current.asString();
        }
        return "";
    }
}
