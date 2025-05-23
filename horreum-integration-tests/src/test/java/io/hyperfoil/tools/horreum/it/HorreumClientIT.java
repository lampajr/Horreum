package io.hyperfoil.tools.horreum.it;

import static io.hyperfoil.tools.horreum.api.services.UserService.KeyType.USER;
import static java.time.temporal.ChronoUnit.DAYS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;

import org.junit.jupiter.api.Assertions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.hyperfoil.tools.HorreumClient;
import io.hyperfoil.tools.horreum.api.SortDirection;
import io.hyperfoil.tools.horreum.api.alerting.ChangeDetection;
import io.hyperfoil.tools.horreum.api.alerting.Variable;
import io.hyperfoil.tools.horreum.api.data.Access;
import io.hyperfoil.tools.horreum.api.data.ExperimentComparison;
import io.hyperfoil.tools.horreum.api.data.ExperimentProfile;
import io.hyperfoil.tools.horreum.api.data.Extractor;
import io.hyperfoil.tools.horreum.api.data.Label;
import io.hyperfoil.tools.horreum.api.data.Run;
import io.hyperfoil.tools.horreum.api.data.Schema;
import io.hyperfoil.tools.horreum.api.data.Test;
import io.hyperfoil.tools.horreum.api.data.changeDetection.ChangeDetectionModelType;
import io.hyperfoil.tools.horreum.api.internal.services.AlertingService;
import io.hyperfoil.tools.horreum.api.services.DatasetService;
import io.hyperfoil.tools.horreum.api.services.ExperimentService;
import io.hyperfoil.tools.horreum.api.services.RunService;
import io.hyperfoil.tools.horreum.api.services.UserService;
import io.hyperfoil.tools.horreum.it.profile.InContainerProfile;
import io.hyperfoil.tools.horreum.svc.Roles;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.callback.QuarkusTestAfterAllCallback;
import io.quarkus.test.junit.callback.QuarkusTestAfterEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestBeforeClassCallback;
import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestBeforeTestExecutionCallback;
import io.quarkus.test.junit.callback.QuarkusTestContext;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;

@QuarkusIntegrationTest
@TestProfile(InContainerProfile.class)
public class HorreumClientIT implements QuarkusTestBeforeTestExecutionCallback, QuarkusTestBeforeClassCallback,
        QuarkusTestBeforeEachCallback, QuarkusTestAfterEachCallback, QuarkusTestAfterAllCallback {

    @org.junit.jupiter.api.Test
    public void testApiKeys() {
        String keyName = "Test key";
        String theKey = horreumClient.userService.newApiKey(new UserService.ApiKeyRequest(keyName, USER));
        List<String> existingRoles = horreumClient.userService.getRoles();

        try (HorreumClient apiClient = new HorreumClient.Builder()
                .horreumUrl("http://localhost:" + System.getProperty("quarkus.http.test-port"))
                .horreumApiKey(theKey)
                .build()) {

            List<String> roles = apiClient.userService.getRoles();
            assertFalse(roles.isEmpty());
            assertTrue(existingRoles.stream().filter(r -> r.startsWith("dev")).allMatch(roles::contains));
            assertTrue(roles.containsAll(List.of(Roles.ADMIN, Roles.MANAGER, Roles.TESTER, Roles.TESTER, Roles.VIEWER)));

            UserService.ApiKeyResponse apiKey = horreumClient.userService.apiKeys().get(0);
            assertEquals(keyName, apiKey.name);
            assertFalse(apiKey.isRevoked);
            assertFalse(apiKey.toExpiration < 0);
            assertEquals(Instant.now().truncatedTo(DAYS), apiKey.creation.truncatedTo(DAYS));
            assertEquals(Instant.now().truncatedTo(DAYS), apiKey.access.truncatedTo(DAYS));
            assertEquals(USER, apiKey.type);

            apiClient.userService.renameApiKey(apiKey.id, "Some new name"); // use key to modify key !!
            apiKey = horreumClient.userService.apiKeys().get(0);
            assertNotEquals(keyName, apiKey.name);

            horreumClient.userService.revokeApiKey(apiKey.id);
            assertThrows(NotAuthorizedException.class, apiClient.userService::getRoles);
        }
    }

    @org.junit.jupiter.api.Test
    public void testApiKeysPrivateUpload() throws JsonProcessingException {
        String theKey = horreumClient.userService.newApiKey(new UserService.ApiKeyRequest("private upload key", USER));

        horreumClient.testService.updateTestAccess(dummyTest.id, dummyTest.owner, Access.PRIVATE);
        try (HorreumClient apiClient = new HorreumClient.Builder()
                .horreumUrl("http://localhost:" + System.getProperty("quarkus.http.test-port"))
                .horreumApiKey(theKey)
                .build()) {

            Run run = new Run();
            run.start = Instant.now();
            run.stop = Instant.now();
            run.testid = -1; // should be ignored
            run.data = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));
            run.description = "Test description";
            List<Integer> ids = apiClient.runService.addRun(dummyTest.name, dummyTest.owner, Access.PRIVATE, run);
            assertEquals(1, ids.size());
            assertTrue(ids.get(0) > 0);
        } finally {
            horreumClient.testService.updateTestAccess(dummyTest.id, dummyTest.owner, Access.PUBLIC);
        }
    }

    @org.junit.jupiter.api.Test
    public void testAddRunFromData() throws JsonProcessingException {
        JsonNode payload = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));

        try {
            horreumClient.runService.addRunFromData("$.start", "$.stop", dummyTest.name, dummyTest.owner,
                    Access.PUBLIC,
                    null, "test", payload);
        } catch (BadRequestException badRequestException) {
            fail(badRequestException.getMessage()
                    + (badRequestException.getCause() != null ? " : " + badRequestException.getCause().getMessage() : ""));
        }
    }

    @org.junit.jupiter.api.Test
    public void testAddRunWithMetadataData() throws JsonProcessingException {
        JsonNode payload = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));
        JsonNode metadata = JsonNodeFactory.instance.objectNode().put("$schema", "urn:foobar").put("foo", "bar");

        try {
            horreumClient.runService.addRunFromData("$.start", "$.stop", dummyTest.name, dummyTest.owner, Access.PUBLIC,
                    null, "test", payload, metadata);
        } catch (BadRequestException badRequestException) {
            fail(badRequestException.getMessage()
                    + (badRequestException.getCause() != null ? " : " + badRequestException.getCause().getMessage() : ""));
        }
    }

    @org.junit.jupiter.api.Test
    public void testAddRun() throws JsonProcessingException {
        Run run = new Run();
        run.start = Instant.now();
        run.stop = Instant.now();
        run.testid = -1; // should be ignored
        run.data = new ObjectMapper().readTree(resourceToString("data/config-quickstart.jvm.json"));
        run.description = "Test description";
        horreumClient.runService.addRun(dummyTest.name, dummyTest.owner, Access.PUBLIC, run);
    }

    // Javascript execution gets often broken with new Quarkus releases, this should catch it
    @org.junit.jupiter.api.Test
    public void testJavascriptExecution() throws InterruptedException {
        Schema schema = new Schema();
        schema.id = -1;
        schema.uri = "urn:dummy:schema";
        schema.name = "Dummy";
        schema.owner = dummyTest.owner;
        schema.access = Access.PUBLIC;
        schema.id = horreumClient.schemaService.addSchema(schema);

        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        JsonNode data = JsonNodeFactory.instance.objectNode()
                .put("$schema", schema.uri)
                .put("value", "foobar");
        horreumClient.runService.addRunFromData(ts, ts, dummyTest.name, dummyTest.owner, Access.PUBLIC, schema.uri, null, data);

        int datasetId = -1;
        while (System.currentTimeMillis() < now + 10000) {
            DatasetService.DatasetList datasets = horreumClient.datasetService.listByTest(dummyTest.id, null, null, null, null,
                    null, null);
            if (datasets.datasets.isEmpty()) {
                //noinspection BusyWait
                Thread.sleep(50);
            } else {
                Assertions.assertEquals(1, datasets.datasets.size());
                datasetId = datasets.datasets.iterator().next().id;
            }
        }
        Assertions.assertNotEquals(-1, datasetId);

        Label label = new Label();
        label.name = "foo";
        label.schemaId = schema.id;
        label.function = "value => value";
        label.extractors = Collections.singletonList(new Extractor("value", "$.value", false));
        DatasetService.LabelPreview preview = horreumClient.datasetService.previewLabel(datasetId, label);
        Assertions.assertEquals("foobar", preview.value.textValue());
    }

    @org.junit.jupiter.api.Test
    public void testJavascriptExecutionWithArray() throws InterruptedException {
        Schema schema = new Schema();
        schema.id = -1;
        schema.uri = "urn:dummy:schema-array";
        schema.name = "DummyArray";
        schema.owner = dummyTest.owner;
        schema.access = Access.PUBLIC;
        schema.id = horreumClient.schemaService.addSchema(schema);

        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);

        ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("key", "value1"))
                .add(JsonNodeFactory.instance.objectNode().put("key", "value2"))
                .add(JsonNodeFactory.instance.objectNode().put("key", "value3"));
        ObjectNode data = JsonNodeFactory.instance.objectNode()
                .put("$schema", schema.uri);

        data.putIfAbsent("samplesArray", arrayNode);

        horreumClient.runService.addRunFromData(ts, ts, dummyTest.name, dummyTest.owner, Access.PUBLIC, schema.uri, null, data);

        int datasetId = -1;
        while (System.currentTimeMillis() < now + 10000) {
            DatasetService.DatasetList datasets = horreumClient.datasetService.listByTest(dummyTest.id, null, null, null, null,
                    null, null);
            if (datasets.datasets.isEmpty()) {
                //noinspection BusyWait
                Thread.sleep(50);
            } else {
                Assertions.assertEquals(1, datasets.datasets.size());
                datasetId = datasets.datasets.iterator().next().id;
            }
        }
        Assertions.assertNotEquals(-1, datasetId);

        Label label = new Label();
        label.name = "reduced-array";
        label.schemaId = schema.id;
        label.function = "value => { return value.reduce((a,b) => a + '' + b); }";
        label.extractors = Collections.singletonList(new Extractor("value", "$.samplesArray.key", true));
        DatasetService.LabelPreview preview = horreumClient.datasetService.previewLabel(datasetId, label);
        Assertions.assertEquals("value1value2value3", preview.value.textValue());
    }

    @org.junit.jupiter.api.Test
    public void runExperiment() {

        ObjectMapper mapper = new ObjectMapper();

        try {
            //1. Create new Schema
            Schema schema = new Schema();
            schema.uri = "urn:test-schema:0.1";
            schema.name = "test";
            schema.owner = dummyTest.owner;
            schema.access = Access.PUBLIC;
            schema.id = horreumClient.schemaService.addSchema(schema);

            //2. Define schema labels
            Label lblCpu = new Label();
            lblCpu.name = "cpu";
            Extractor cpuExtractor = new Extractor("cpu", "$.data.cpu", false);
            lblCpu.extractors = List.of(cpuExtractor);
            lblCpu.access = Access.PUBLIC;
            lblCpu.owner = dummyTest.owner;
            lblCpu.metrics = true;
            lblCpu.filtering = false;
            lblCpu.id = horreumClient.schemaService.addLabel(schema.id, lblCpu);

            Label lblThroughput = new Label();
            lblThroughput.name = "throughput";
            Extractor throughputExtractor = new Extractor("throughput", "$.data.throughput", false);
            lblThroughput.extractors = List.of(throughputExtractor);
            lblThroughput.access = Access.PUBLIC;
            lblThroughput.owner = dummyTest.owner;
            lblThroughput.metrics = true;
            lblThroughput.filtering = false;
            lblThroughput.id = horreumClient.schemaService.addLabel(schema.id, lblThroughput);

            Label lblJob = new Label();
            lblJob.name = "job";
            Extractor jobExtractor = new Extractor("job", "$.job", false);
            lblJob.extractors = List.of(jobExtractor);
            lblJob.access = Access.PUBLIC;
            lblJob.owner = dummyTest.owner;
            lblJob.metrics = false;
            lblJob.filtering = true;
            lblJob.id = horreumClient.schemaService.addLabel(schema.id, lblJob);

            Label lblBuildID = new Label();
            lblBuildID.name = "build-id";
            Extractor buildIDExtractor = new Extractor("build-id", "$.\"build-id\"", false);
            lblBuildID.extractors = List.of(buildIDExtractor);
            lblBuildID.access = Access.PUBLIC;
            lblBuildID.owner = dummyTest.owner;
            lblBuildID.metrics = false;
            lblBuildID.filtering = true;
            lblBuildID.id = horreumClient.schemaService.addLabel(schema.id, lblBuildID);

            //3. Config change detection variables
            Variable variable = new Variable();
            variable.testId = dummyTest.id;
            variable.name = "throughput";
            variable.order = 0;
            variable.labels = Collections.singletonList("throughput");
            ChangeDetection changeDetection = new ChangeDetection();
            changeDetection.model = ChangeDetectionModelType.names.RELATIVE_DIFFERENCE;

            changeDetection.config = (ObjectNode) mapper.readTree("{" +
                    "          \"window\": 1," +
                    "          \"filter\": \"mean\"," +
                    "          \"threshold\": 0.2," +
                    "          \"minPrevious\": 5" +
                    "        }");
            variable.changeDetection = new HashSet<>();
            variable.changeDetection.add(changeDetection);

            horreumClient.alertingService.updateVariables(dummyTest.id, Collections.singletonList(variable));

            //need this for defining experiment
            List<Variable> variableList = horreumClient.alertingService.variables(dummyTest.id);

            AlertingService.ChangeDetectionUpdate update = new AlertingService.ChangeDetectionUpdate();
            update.fingerprintLabels = Collections.emptyList();
            update.timelineLabels = Collections.emptyList();
            horreumClient.alertingService.updateChangeDetection(dummyTest.id, update);

            //4. Define experiments
            ExperimentProfile experimentProfile = new ExperimentProfile();
            experimentProfile.id = -1; //TODO: fix profile add/Update
            experimentProfile.name = "robust-experiment";
            experimentProfile.selectorLabels = mapper.readTree(" [ \"job\" ] ");
            experimentProfile.selectorFilter = "value => !!value";
            experimentProfile.baselineLabels = mapper.readTree(" [ \"build-id\" ] ");
            experimentProfile.baselineFilter = "value => value == 1";

            ExperimentComparison experimentComparison = new ExperimentComparison();
            experimentComparison.model = "relativeDifference";
            experimentComparison.variableId = variableList.get(0).id; //should only contain one variable
            experimentComparison.config = mapper.readValue("{" +
                    "          \"maxBaselineDatasets\": 0," +
                    "          \"threshold\": 0.1," +
                    "          \"greaterBetter\": true" +
                    "        }", ObjectNode.class);

            experimentProfile.comparisons = Collections.singletonList(experimentComparison);

            horreumClient.experimentService.addOrUpdateProfile(dummyTest.id, experimentProfile);

            //5. upload some data
            Consumer<JsonNode> uploadData = (payload) -> horreumClient.runService.addRunFromData("$.start", "$.stop",
                    dummyTest.name, dummyTest.owner, Access.PUBLIC, schema.uri, null, payload);

            uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds1.json")));
            uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds2.json")));
            uploadData.accept(mapper.readTree(resourceToString("data/experiment-ds3.json")));

            // let process finish async, labelValues calculation is getting processed async
            // TODO: is there a better way to wait for this?
            Thread.sleep(6000);

            //6. run experiments
            RunService.RunsSummary runsSummary = horreumClient.runService.listTestRuns(dummyTest.id, false, null, null, "name",
                    SortDirection.Ascending);

            Integer lastRunID = runsSummary.runs.stream().map(run -> run.id).max((Comparator.comparingInt(anInt -> anInt)))
                    .get();

            RunService.RunExtended extendedRun = horreumClient.runService.getRun(lastRunID);

            assertNotNull(extendedRun.datasets);

            Integer maxDataset = Arrays.stream(extendedRun.datasets).max(Comparator.comparingInt(anInt -> anInt)).get();

            List<ExperimentService.ExperimentResult> experimentResults = horreumClient.experimentService
                    .runExperiments(maxDataset);

            assertNotNull(experimentResults);
            assertFalse(experimentResults.isEmpty());

        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    protected static String resourceToString(String resourcePath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            return new BufferedReader(new InputStreamReader(inputStream))
                    .lines().collect(Collectors.joining(" "));
        } catch (IOException e) {
            fail("Failed to read `" + resourcePath + "`", e);
            return null;
        }
    }

    private static HorreumClient horreumClient;

    private static Test dummyTest;

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        if (horreumClient == null) {
            instantiateClient();
        }
        if (dummyTest != null) {
            horreumClient.testService.deleteTest(dummyTest.id);
            dummyTest = null;
        }
        Assertions.assertNull(dummyTest);
        Test test = new Test();
        test.name = "test";
        test.owner = "dev-team";
        test.description = "This is a dummy test";
        dummyTest = horreumClient.testService.addTest(test);
        Assertions.assertNotNull(dummyTest);
    }

    @Override
    public void afterEach(QuarkusTestMethodContext context) {
        if (horreumClient == null) {
            instantiateClient();
        }
        if (dummyTest != null) {
            horreumClient.testService.deleteTest(dummyTest.id);
        }
        dummyTest = null;
    }

    @Override
    public void afterAll(QuarkusTestContext context) {
        horreumClient.close();
    }

    private void instantiateClient() {
        if (horreumClient == null) {
            horreumClient = new HorreumClient.Builder()
                    .horreumUrl("http://localhost:" + System.getProperty("quarkus.http.test-port"))
                    .horreumUser("horreum.bootstrap")
                    .horreumPassword(ItResource.HORREUM_BOOTSTRAP_PASSWORD)
                    .build();

            Assertions.assertNotNull(horreumClient);
        }
    }

    @Override
    public void beforeClass(Class<?> testClass) {
    }

    @Override
    public void beforeTestExecution(QuarkusTestMethodContext context) {
        instantiateClient();
    }
}
