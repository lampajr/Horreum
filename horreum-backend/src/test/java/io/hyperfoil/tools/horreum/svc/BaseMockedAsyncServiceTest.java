package io.hyperfoil.tools.horreum.svc;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;
import jakarta.transaction.Status;

import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.BeforeEach;

import com.fasterxml.jackson.databind.JsonNode;

import io.hyperfoil.tools.horreum.api.data.Dataset;
import io.hyperfoil.tools.horreum.api.data.Test;
import io.hyperfoil.tools.horreum.entity.data.DatasetDAO;
import io.hyperfoil.tools.horreum.entity.data.RunDAO;
import io.hyperfoil.tools.horreum.mapper.DatasetMapper;
import io.hyperfoil.tools.horreum.server.CloseMe;
import io.hyperfoil.tools.horreum.test.InMemoryAMQTestProfile;
import io.restassured.response.Response;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;

/**
 * Tests extending this abstract test requires {@link InMemoryAMQTestProfile} profile to be set
 */
public abstract class BaseMockedAsyncServiceTest extends BaseServiceTest {

    private static final List<String> queues = List.of("dataset-event", "run-recalc", "schema-sync", "run-upload");

    @Inject
    @Any
    InMemoryConnector connector;

    @BeforeEach
    public void clearQueues() {
        for (String channel : queues) {
            InMemorySink<?> outgoingChannel = connector.sink("%s-out".formatted(channel));
            outgoingChannel.clear();
        }
    }

    /**
     * Wait for the expected messages on a specific channel and then
     * propagates the same to the corresponding incoming channel.
     * This is a trick to make async processing executed in the same
     * transaction of the test running it.
     * Furthermore, it waits for the propagated messages to be completed
     * before returning.
     * @param channel channel name to inspect and propagate
     * @param expectedMessages number of expected messages to wait for
     */
    protected void checkAndPropagate(String channel, int expectedMessages) {
        InMemorySource<Message<Dataset.EventNew>> incomingChannel = connector.source("%s-in".formatted(channel));
        InMemorySink<Dataset.EventNew> outgoingChannel = connector.sink("%s-out".formatted(channel));

        await().until(() -> outgoingChannel.received().size() == expectedMessages);
        CountDownLatch latch = new CountDownLatch(expectedMessages);
        outgoingChannel.received().forEach(m -> {
            Message<Dataset.EventNew> msgWithCallback = m.withAck(() -> {
                latch.countDown();
                return CompletableFuture.completedFuture(null);
            });
            incomingChannel.send(msgWithCallback);
        });

        try {
            latch.await();
            // ensure the messages are not kept in the queue
            outgoingChannel.clear();
        } catch (InterruptedException e) {
            fail("Cannot wait for latch", e);
        }
    }

    protected <T> T withExampleDataset(Test test, JsonNode data, int nDatasets, Function<Dataset, T> testLogic) {
        try {
            RunDAO run = new RunDAO();
            tm.begin();
            try (CloseMe ignored = roleManager.withRoles(Arrays.asList(UPLOADER_ROLES))) {
                run.data = data;
                run.testid = test.id;
                run.start = run.stop = Instant.now();
                run.owner = UPLOADER_ROLES[0];
                log.debugf("Creating new Run via API: %s", run.toString());

                Response response = jsonRequest()
                        .auth()
                        .oauth2(getUploaderToken())
                        .body(run)
                        .post("/api/run/test");
                run.id = response.body().as(Integer.class);
                log.debugf("Run ID: %d, for test ID: %d", run.id, run.testid);
            } finally {
                if (tm.getTransaction().getStatus() == Status.STATUS_ACTIVE) {
                    tm.commit();
                } else {
                    tm.rollback();
                    fail();
                }
            }
            // dataset recal is async, let's check events are sent and processed
            checkAndPropagate("dataset-event", nDatasets);
            int datasetId = ((DatasetDAO) DatasetDAO.find("run.id = ?1", run.id).firstResult()).id;

            // only to cover the summary call in API
            jsonRequest().get("/api/dataset/" + datasetId + "/summary").then().statusCode(200);
            T value = testLogic.apply(DatasetMapper.from(
                    DatasetDAO.<DatasetDAO> findById(datasetId)));
            tm.begin();
            Throwable error = null;
            try (CloseMe ignored = roleManager.withRoles(SYSTEM_ROLES)) {
                DatasetDAO oldDs = DatasetDAO.findById(datasetId);
                if (oldDs != null) {
                    oldDs.delete();
                }
                DatasetDAO.delete("run.id", run.id);
                RunDAO.findById(run.id).delete();
            } catch (Throwable t) {
                error = t;
            } finally {
                if (tm.getTransaction().getStatus() == Status.STATUS_ACTIVE) {
                    tm.commit();
                } else {
                    tm.rollback();
                    fail(error);
                }
            }
            return value;
        } catch (Exception e) {
            fail(e);
            return null;
        }
    }
}
