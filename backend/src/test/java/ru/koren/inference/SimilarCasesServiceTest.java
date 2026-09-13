package ru.koren.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static ru.koren.inference.InferenceContract.*;
import static ru.koren.inference.IntakeContract.*;

@SpringBootTest(properties = {
        "spring.datasource.url=${DB_TEST_URL}", "spring.datasource.username=${DB_TEST_USER:koren}",
        "spring.datasource.password=${DB_TEST_PASSWORD:koren_local_only}"
})
@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class SimilarCasesServiceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    UUID resident, neighbor, dispatcher, executor, house, foreignHouse, query, own, other, foreign, draft;
    final List<UUID> cases = new ArrayList<>();
    CapturingInference inference;
    SimilarCasesService service;

    @BeforeEach void setup() {
        resident = UUID.randomUUID(); neighbor = UUID.randomUUID(); dispatcher = UUID.randomUUID();
        executor = UUID.randomUUID(); house = UUID.randomUUID(); foreignHouse = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (UUID user : List.of(resident, neighbor, dispatcher, executor)) jdbc.update("INSERT INTO app_user(id) VALUES (?)", user);
            for (UUID h : List.of(house, foreignHouse)) jdbc.update("INSERT INTO house(id,address) VALUES (?, 'Synthetic test house')", h);
            for (UUID h : List.of(house, foreignHouse)) {
                jdbc.update("INSERT INTO membership VALUES (?,?,'RESIDENT'), (?,?,'RESIDENT'), (?,?,'DISPATCHER'), (?,?,'EXECUTOR')",
                        resident,h,neighbor,h,dispatcher,h,executor,h);
            }
            query = addCase(resident, house, true); own = addCase(resident, house, true);
            other = addCase(neighbor, house, true); foreign = addCase(resident, foreignHouse, true);
            draft = addCase(resident, house, false);
        });
        inference = new CapturingInference();
        service = new SimilarCasesService(jdbc, transactions, inference);
    }

    UUID addCase(UUID author, UUID h, boolean confirmed) {
        UUID id = UUID.randomUUID(), item = UUID.randomUUID(); cases.add(id);
        jdbc.update("INSERT INTO service_case(id,house_id,author_id,submission_status) VALUES (?,?,?,?)",
                id,h,author,confirmed ? "CONFIRMED" : "DRAFT");
        jdbc.update("INSERT INTO case_revision(case_id,revision,source_text) VALUES (?,1,'Synthetic lighting issue')", id);
        jdbc.update("INSERT INTO case_item(id,case_id) VALUES (?,?)", item,id);
        jdbc.update("INSERT INTO item_revision(item_id,content_version,category,location,symptom,confirmed_at) VALUES (?,1,'LIGHTING','Floor 3','Dark',now())", item);
        return id;
    }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (UUID id : cases) {
                jdbc.update("DELETE FROM item_revision WHERE item_id IN (SELECT id FROM case_item WHERE case_id=?)", id);
                jdbc.update("DELETE FROM case_item WHERE case_id=?", id);
                jdbc.update("DELETE FROM case_revision WHERE case_id=?", id);
                jdbc.update("DELETE FROM service_case WHERE id=?", id);
            }
            for (UUID h : List.of(house,foreignHouse)) {
                jdbc.update("DELETE FROM membership WHERE house_id=?",h);
                jdbc.update("DELETE FROM house WHERE id=?",h);
            }
            for (UUID user : List.of(resident,neighbor,dispatcher,executor)) jdbc.update("DELETE FROM app_user WHERE id=?",user);
        });
    }

    @Test void residentOnlySuppliesOwnConfirmedCasesFromTheSameHouse() {
        assertEquals(AnalysisStatus.ANALYZED, service.find(resident, query, 5).status());
        assertEquals(List.of(own), inference.request.candidates().stream().map(ConfirmedCaseContext::caseId).toList());
        assertThrows(AccessDeniedException.class, () -> service.find(resident, other, 5));
        assertThrows(AccessDeniedException.class, () -> service.find(resident, draft, 5));
        assertThrows(AccessDeniedException.class, () -> service.find(UUID.randomUUID(), query, 5));
        assertThrows(AccessDeniedException.class, () -> service.find(executor, query, 5));
    }

    @Test void dispatcherCanSupplyConfirmedHouseCasesButNotDraftsOrOtherHouses() {
        service.find(dispatcher, query, 5);
        assertEquals(java.util.Set.of(own,other), new java.util.HashSet<>(inference.request.candidates().stream().map(ConfirmedCaseContext::caseId).toList()));
    }

    @Test void candidateUpdateDuringInferenceDiscardsEvenAnEmptyFinding() {
        inference.during = () -> jdbc.update("UPDATE service_case SET version=version+1 WHERE id=?", own);
        assertEquals(AnalysisStatus.NOT_ANALYZED, service.find(resident, query, 5).status());
    }

    @Test void queryUpdateDuringInferenceDiscardsTheFinding() {
        inference.during = () -> jdbc.update("UPDATE case_item SET work_round=work_round+1 WHERE case_id=?", query);
        assertEquals(Mode.MANUAL, service.find(resident, query, 5).mode());
    }

    @Test void roleRevocationCannotExposeFormerlyAccessibleCandidates() {
        // Dispatcher still has access to query as its author after demotion, but loses access to neighbors.
        jdbc.update("UPDATE service_case SET author_id=? WHERE id=?", dispatcher,query);
        inference.during = () -> jdbc.update("UPDATE membership SET role='RESIDENT' WHERE user_id=? AND house_id=?", dispatcher,house);
        assertEquals(Mode.MANUAL, service.find(dispatcher, query, 5).mode());
    }

    @Test void queryAccessRevocationAfterHttpIsRejected() {
        inference.during = () -> jdbc.update("UPDATE membership SET role='RESIDENT' WHERE user_id=? AND house_id=?", dispatcher,house);
        assertThrows(AccessDeniedException.class, () -> service.find(dispatcher, query, 5));
    }

    @Test void utf8RequestStaysWithinTheRealBodyLimit() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (int c=0;c<12;c++) {
                UUID id=addCase(resident,house,true);
                jdbc.update("UPDATE case_revision SET source_text=? WHERE case_id=?", "я".repeat(5000),id);
                for (int i=0;i<19;i++) {
                    UUID item=UUID.randomUUID();
                    jdbc.update("INSERT INTO case_item(id,case_id) VALUES (?,?)",item,id);
                    jdbc.update("INSERT INTO item_revision(item_id,content_version,category,location,symptom,confirmed_at) VALUES (?,1,'LIGHTING',?,?,now())",item,"я".repeat(300),"я".repeat(1000));
                }
            }
        });
        service.find(resident,query,5);
        assertTrue(inference.request.candidates().size()<13);
        assertTrue(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsBytes(inference.request).length<=512*1024);
    }

    static class CapturingInference extends InferenceClient {
        SimilarRequest request;
        Runnable during = () -> {};
        CapturingInference() { super("http://127.0.0.1:1"); }
        @Override SimilarResponse similar(SimilarRequest value) {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            request=value; during.run();
            return new SimilarResponse(value.query().caseId(),value.query().caseVersion(),value.query().sourceRevision(),
                    "test-fixture",Mode.RULES,AnalysisStatus.ANALYZED,Reason.ANALYZED,"CHROMA",List.of());
        }
    }
}
