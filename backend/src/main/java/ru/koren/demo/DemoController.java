package ru.koren.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.koren.cases.domain.CaseProgress;
import ru.koren.cases.domain.ItemState;
import ru.koren.inference.InferenceClient;
import ru.koren.inference.InferenceContract;
import static ru.koren.cases.domain.ItemState.*;
import static ru.koren.inference.InferenceContract.*;

/** Synthetic, read-only wiring example. Not a repository, authenticated case API, or ML demonstration. */
@Profile("demo")
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {
    private static final UUID CASE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID LIGHT = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID DOOR = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private final InferenceClient inference;

    public DemoController(InferenceClient inference) { this.inference = inference; }

    public record DemoItem(Item detail, ItemState state) {}
    public record DemoCase(UUID caseId, boolean synthetic, String originalText, String reportText,
                           List<DemoItem> items, CaseProgress progress) {}

    public static Request sampleRequest() {
        return new Request(CASE, 1, REPORT, "Лампу на третьем этаже заменили, освещение работает.", List.of(
                new Item(LIGHT, 1, 1, Category.LIGHTING, "Подъезд 1, этаж 3", "Не горит свет"),
                new Item(DOOR, 1, 0, Category.ENTRANCE_DOOR, "Подъезд 1, вход", "Дверь не закрывается")));
    }

    @GetMapping("/case")
    public DemoCase demoCase() {
        Request request = sampleRequest();
        List<DemoItem> items = request.items().stream().map(item -> new DemoItem(item,
                new ItemState(item.itemId(), AUTHOR, item.contentVersion(), item.workRound(),
                        item.itemId().equals(LIGHT) ? WorkStatus.REPORTED_DONE : WorkStatus.NOT_ASSIGNED,
                        Observation.NOT_RECEIVED, AdministrativeStatus.ACTIVE))).toList();
        return new DemoCase(CASE, true, "На третьем этаже темно, и входная дверь не закрывается.",
                request.reportText(), items, CaseProgress.from(items.stream().map(DemoItem::state).toList()));
    }

    @GetMapping("/compare")
    public InferenceContract.Response compare() { return inference.compare(sampleRequest()); }
}
