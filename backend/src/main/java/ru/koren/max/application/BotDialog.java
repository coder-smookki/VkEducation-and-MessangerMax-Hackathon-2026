package ru.koren.max.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import ru.koren.cases.application.CaseException;
import ru.koren.cases.application.CaseService;
import ru.koren.cases.application.CaseView;
import ru.koren.cases.application.DraftItemInput;
import ru.koren.cases.persistence.CaseRepository;
import ru.koren.identity.persistence.MaxUserProvisioner;
import ru.koren.max.persistence.DialogRepository;
import ru.koren.notifications.persistence.OutboxRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bot calls the same application commands as REST: no second set of rules, no second authority.
 * Dialog state lives in bot_dialog, never in the client, and an observation question stores the round
 * it asked about, so an answer that arrives after a new round is refused instead of misapplied.
 * Replies go through the outbox, so no HTTP happens inside this transaction and a redelivered update
 * produces the same reply key rather than a second message.
 */
public class BotDialog implements UpdateHandler {

    static final String REPLY_EVENT = "BOT_REPLY";
    private static final int PENDING_LOOKAHEAD = 20;

    private static final String HELP = """
            КОРЕНЬ на связи. Что я умею:
            «новое обращение» - создать обращение;
            «наблюдение» - ответить, помогли ли работы;
            «мои обращения» - сколько у вас обращений;
            «/start» - это сообщение.
            История и подробности - в приложении.""";

    private static final String ITEM_FORMAT = """
            Опишите один пункт одним сообщением в виде: категория; место; что не так.
            Категории: свет, дверь, уборка, другое.
            Например: свет; 3 этаж; не горит лампа.
            Когда закончите - напишите «готово», чтобы подтвердить список.""";

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
        Map.entry("свет", "LIGHTING"), Map.entry("освещение", "LIGHTING"),
        Map.entry("дверь", "ENTRANCE_DOOR"), Map.entry("подъезд", "ENTRANCE_DOOR"),
        Map.entry("уборка", "CLEANING"), Map.entry("чистота", "CLEANING"),
        Map.entry("другое", "OTHER"), Map.entry("прочее", "OTHER"));

    private static final Map<String, String> OBSERVATIONS = Map.ofEntries(
        Map.entry("восстановлено", "RESTORED"), Map.entry("починили", "RESTORED"),
        Map.entry("помогло", "RESTORED"),
        Map.entry("не помогло", "PERSISTS"), Map.entry("осталось", "PERSISTS"),
        Map.entry("не починили", "PERSISTS"),
        Map.entry("не могу проверить", "CANNOT_CHECK"), Map.entry("не знаю", "CANNOT_CHECK"));

    private final CaseService cases;
    private final CaseRepository repo;
    private final MaxUserProvisioner users;
    private final DialogRepository dialogs;
    private final OutboxRepository outbox;
    private final JsonMapper json = JsonMapper.builder().build();

    public BotDialog(CaseService cases, CaseRepository repo, MaxUserProvisioner users,
                     DialogRepository dialogs, OutboxRepository outbox) {
        this.cases = cases;
        this.repo = repo;
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
        var dialog = dialogs.find(actor);
        String state = dialog.map(DialogRepository.DialogRow::state).orElse("IDLE");
        String answer;
        try {
            answer = route(eventKey, actor, state, dialog.orElse(null), text);
        } catch (CaseException refused) {
            // The rule that refused belongs to CaseService; its wording is internal, so it stays there.
            answer = "Не получилось выполнить это сейчас. Проверьте обращение в приложении.";
            dialogs.setIdle(actor);
        }
        reply(eventKey, actor, answer);
    }

    private String route(String eventKey, UUID actor, String state,
                         DialogRepository.DialogRow dialog, String text) {
        String command = text.toLowerCase(Locale.ROOT);
        if (command.startsWith("/start")) {
            dialogs.setIdle(actor);
            return HELP;
        }
        if (command.equals("отмена") || command.equals("/cancel")) {
            dialogs.setIdle(actor);
            return "Отменил. Начатый черновик остался, его видно в приложении.";
        }
        return switch (state) {
            case "AWAITING_CASE_TEXT" -> startCase(eventKey, actor, text);
            case "AWAITING_ITEMS" -> collectItems(eventKey, actor, dialog, text, command);
            case "AWAITING_OBSERVATION" -> recordObservation(eventKey, actor, dialog, command);
            default -> idle(actor, command);
        };
    }

    private String idle(UUID actor, String command) {
        if (command.startsWith("новое обращение") || command.startsWith("/new")) {
            dialogs.setAwaitingCaseText(actor);
            return "Опишите проблему своими словами одним сообщением.";
        }
        if (command.startsWith("наблюдение") || command.startsWith("/observe")) {
            return askAboutNextPending(actor, "Сейчас никаких работ, о которых стоит спросить, нет.");
        }
        if (command.startsWith("мои обращения") || command.startsWith("/cases")) {
            int count = cases.listCases(actor, null, null, null, 50).items().size();
            if (count == 0) return "У вас пока нет обращений. Напишите «новое обращение».";
            // Numbers and texts stay out of MAX: the card re-checks access on every read.
            return "У вас обращений: " + count + ". Подробности - в приложении.";
        }
        return "Пока я понимаю «новое обращение», «наблюдение», «мои обращения» и «/start».";
    }

    private String startCase(String eventKey, UUID actor, String text) {
        var houses = repo.activeHouses(actor);
        if (houses.isEmpty()) {
            dialogs.setIdle(actor);
            return "У вас нет доступа ни к одному дому. Попросите код приглашения у диспетчера.";
        }
        if (houses.size() > 1) {
            // Picking a house in chat needs its own dialog; the app already asks this properly.
            dialogs.setIdle(actor);
            return "У вас несколько домов - выберите нужный в приложении и создайте обращение там.";
        }
        CaseView created = cases.create(actor, houses.getFirst(), text, eventKey);
        dialogs.setAwaitingItems(actor, created.caseId());
        return "Записал. " + ITEM_FORMAT;
    }

    private String collectItems(String eventKey, UUID actor, DialogRepository.DialogRow dialog,
                                String text, String command) {
        CaseView view = cases.getCase(actor, dialog.caseId());
        if (!"DRAFT".equals(view.submissionStatus())) {
            dialogs.setIdle(actor);
            return "Этот список уже подтверждён. Дальше - в приложении.";
        }
        if (command.equals("готово") || command.equals("/done")) {
            if (view.items().isEmpty()) return "Пока ни одного пункта. " + ITEM_FORMAT;
            CaseView confirmed = cases.confirm(actor, view.caseId(), view.caseVersion(),
                view.sourceRevision(), eventKey);
            dialogs.setIdle(actor);
            return "Обращение подтверждено, пунктов: " + confirmed.progress().total()
                + ". Диспетчер увидит его в своей очереди.";
        }
        String[] parts = text.split("[;|]", -1);
        if (parts.length != 3) return "Не разобрал. " + ITEM_FORMAT;
        String category = CATEGORIES.get(parts[0].trim().toLowerCase(Locale.ROOT));
        if (category == null) return "Такой категории нет. " + ITEM_FORMAT;
        var items = new ArrayList<DraftItemInput>(view.items().size() + 1);
        for (var existing : view.items()) {
            // The command replaces the whole list, so every kept item must be sent back as it is.
            items.add(new DraftItemInput(existing.itemId(), existing.category(),
                existing.location(), existing.symptom()));
        }
        items.add(new DraftItemInput(null, category, parts[1].trim(), parts[2].trim()));
        CaseView patched = cases.patchItems(actor, view.caseId(), view.caseVersion(), items, eventKey);
        return "Добавил. Пунктов: " + patched.items().size()
            + ". Ещё один - или «готово», чтобы подтвердить.";
    }

    private String recordObservation(String eventKey, UUID actor, DialogRepository.DialogRow dialog,
                                     String command) {
        String value = OBSERVATIONS.get(command);
        if (value == null) {
            return "Ответьте, пожалуйста, одним из: «восстановлено», «не помогло», «не могу проверить».";
        }
        CaseView view = cases.getCase(actor, dialog.caseId());
        var item = view.items().stream()
            .filter(candidate -> candidate.itemId().equals(dialog.itemId()))
            .findFirst()
            .orElse(null);
        if (item == null) {
            dialogs.setIdle(actor);
            return "Этого пункта больше нет. Посмотрите обращение в приложении.";
        }
        // The question was about one round; if it moved on, the answer is about something else.
        if (item.contentVersion() != dialog.contentVersion() || item.workRound() != dialog.workRound()) {
            dialogs.setIdle(actor);
            return "По этому пункту появились новые сведения о работах. Посмотрите их в приложении"
                + " и ответьте заново.";
        }
        cases.observe(actor, item.itemId(), view.caseVersion(), item.itemVersion(),
            item.contentVersion(), item.workRound(), value, "AFTER_REPORT", eventKey);
        return "Записал ваш ответ. "
            + askAboutNextPending(actor, "Больше вопросов по работам нет. Спасибо.");
    }

    /** Walks pending items one at a time: no numbering, and the stored round is always the asked one. */
    private String askAboutNextPending(UUID actor, String whenNone) {
        var pending = repo.awaitingObservation(actor, PENDING_LOOKAHEAD);
        if (pending.isEmpty()) {
            dialogs.setIdle(actor);
            return whenNone;
        }
        var next = pending.getFirst();
        dialogs.setAwaitingObservation(actor, next.caseId(), next.itemId(),
            next.contentVersion(), next.workRound());
        return "По пункту «" + next.symptom() + "» (" + next.location()
            + ") сообщили о выполненных работах. Как сейчас: «восстановлено», «не помогло»"
            + " или «не могу проверить»?";
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
