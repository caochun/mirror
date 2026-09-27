package gov.objectlibrary;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Checks definition coherence. It does not claim the business handlers are implemented. */
class BusinessContractDefinitionTest {
    private static final Set<String> SCALARS = Set.of("ID", "String", "Int", "Boolean", "DateTime", "Date", "JSON");

    @Test
    void participationExitAndLateReceiptsHaveLegalTransitionsWithoutErasingSuccess() throws IOException {
        var models = records(yaml("contracts/states.yaml"), "models");
        assertTransition(models, "MatterParticipation", "state", "ACTIVE", "ENDED", "EndMatterParticipation");
        assertTransition(models, "TagContribution", "state", "ACTIVE", "EXPIRED", "EndMatterParticipation");
        assertTransition(models, "PersonTagAssignment", "state", "ACTIVE", "EXPIRED", "EndMatterParticipation");
        assertTransition(models, "ReminderTaskVersion", "state", "DRAFT", "FROZEN", "SubmitReminderRevision");
        assertTransition(models, "ReminderTask", "state", "ALL_FAILED", "PARTIAL_FAILED", "RecordDeliveryReceipt");
        assertTransition(models, "ReminderTask", "state", "PARTIAL_FAILED", "ALL_SUCCESS", "RecordDeliveryReceipt");
        assertTransition(models, "RecipientRecord", "withdrawalState", "UNKNOWN", "WITHDRAWN", "RecordWithdrawalResult");
        assertTransition(models, "RecipientRecord", "withdrawalState", "FAILED", "WITHDRAWN", "RecordWithdrawalResult");

        for (var model : models) {
            for (var transition : records(model, "transitions")) {
                if (model.get("object").equals("PersonTagAssignment") && transition.get("from").equals("SUPPRESSED")) {
                    assertTrue(Set.of("AddPersonTag", "RestorePersonTag").contains(transition.get("action")),
                            "Only explicit human restoration may remove suppression");
                }
                if (model.get("object").equals("RecipientRecord") && model.get("field").equals("withdrawalState")) {
                    assertNotEquals("WITHDRAWN", transition.get("from"), "A late failure cannot undo withdrawal");
                }
                if (model.get("object").equals("ReminderTask") && transition.get("action").equals("RecordDeliveryReceipt")) {
                    assertFalse(Set.of("WITHDRAWING", "WITHDRAWN", "PARTIAL_WITHDRAWN", "WITHDRAW_FAILED")
                            .contains(transition.get("from")), "Delivery callbacks cannot reset a withdrawal workflow");
                }
            }
        }
    }

    private static void assertTransition(List<Map<String, Object>> models, String object, String field,
                                         String from, String to, String action) {
        var model = models.stream().filter(m -> object.equals(m.get("object")) && field.equals(m.get("field")))
                .findFirst().orElseThrow();
        assertTrue(records(model, "transitions").contains(Map.of("from", from, "to", to, "action", action)),
                object + "." + field + ": " + action);
    }

    @Test
    void criticalStateTransitionsMatchTheDocumentedBusinessSemantics() throws IOException {
        var models = records(yaml("contracts/states.yaml"), "models");
        var tag = models.stream().filter(m -> m.get("object").equals("PersonTagAssignment")).findFirst().orElseThrow();
        var tagTransitions = records(tag, "transitions");
        assertTrue(tagTransitions.contains(Map.of("from", "SUPPRESSED", "to", "ACTIVE", "action", "AddPersonTag")));
        assertFalse(tagTransitions.stream().anyMatch(t -> t.get("from").equals("SUPPRESSED")
                && t.get("action").equals("ApplyRuleEvaluation")), "A rule cannot remove human suppression");

        var delivery = models.stream().filter(m -> m.get("object").equals("RecipientRecord")
                && m.get("field").equals("deliveryState")).findFirst().orElseThrow();
        var transitions = records(delivery, "transitions");
        assertTrue(transitions.contains(Map.of("from", "UNKNOWN", "to", "SUBMITTED", "action", "RetryFailedRecipients")));
        assertTrue(transitions.contains(Map.of("from", "FAILED", "to", "DELIVERED", "action", "RecordDeliveryReceipt")));
        assertFalse(transitions.stream().anyMatch(t -> t.get("from").equals("DELIVERED")
                && t.get("to").equals("FAILED")), "Late failures cannot erase a confirmed delivery");

        var audit = records(yaml("contracts/actions.yaml"), "actions").stream()
                .filter(a -> a.get("name").equals("ReadOperationAudit")).findFirst().orElseThrow();
        assertEquals("AUDIT_READ", audit.get("permission"));
        assertTrue(strings(audit.get("rules")).containsAll(List.of("AUTH", "PRIVACY")));
    }

    @Test
    void everyContractHasMatchingTypedSignatureAndKnownObjectsRulesAndInputs() throws IOException {
        var pack = new DomainPackLoader().load(BusinessPackVerificationTest.packPath());
        var manifest = yaml("pack.yaml");
        var documents = strings(manifest.get("x-business-contracts"));
        assertEquals(Set.of("actions", "rules", "states", "metrics", "scenarios", "tag-catalog", "coverage"),
                documents.stream().map(s -> s.replace("contracts/", "").replace(".yaml", "")).collect(Collectors.toSet()));
        for (String file : documents) assertEquals("definition-only", yaml(file).get("status"), file);
        Set<String> objects = pack.ontology().objectTypes().keySet();
        var rules = records(yaml("contracts/rules.yaml"), "rules");
        Set<String> ruleIds = uniqueNames(rules, "id");
        for (var rule : rules) for (String object : strings(rule.get("objects"))) assertTrue(objects.contains(object), object);

        StringBuilder source = new StringBuilder();
        for (String schema : pack.manifest().schemaFiles()) {
            source.append(Files.readString(BusinessPackVerificationTest.packPath().resolve(schema))).append('\n');
        }
        source.append(Files.readString(BusinessPackVerificationTest.packPath().resolve((String) manifest.get("x-action-signatures"))));
        var compiled = new SchemaCompiler().compile(new OdlParser().parse(source.toString()));
        Map<String, ActionTypeDefinition> signatures = compiled.schema().actionTypes().stream()
                .collect(Collectors.toMap(ActionTypeDefinition::name, a -> a));
        var actions = records(yaml("contracts/actions.yaml"), "actions");
        Set<String> names = uniqueNames(actions, "name");
        assertEquals(names, signatures.keySet());
        assertTrue(names.containsAll(Set.of("SynchronizePersonFacts", "RemovePersonTag", "ApplyRuleEvaluation",
                "EndMatterStage", "SubmitReminderReview", "DecideReminderReview", "PublishReminderRevision",
                "RecordFirstRead", "EvaluateOverdue", "ConfirmAssistantAction")));
        for (var action : actions) {
            String name = (String) action.get("name");
            assertEquals("definition-only", action.get("status"), name);
            for (String field : List.of("source", "actor", "permission", "idempotency", "event", "failure")) {
                assertFalse(((String) action.get(field)).isBlank(), name + ": " + field);
            }
            assertFalse(strings(action.get("preconditions")).isEmpty(), name);
            assertFalse(strings(action.get("effects")).isEmpty(), name);
            for (String object : strings(action.get("objects"))) assertTrue(objects.contains(object), name + ": " + object);
            for (String rule : strings(action.get("rules"))) assertTrue(ruleIds.contains(rule), name + ": " + rule);
            var parameters = signatures.get(name).parameters();
            var inputs = mapping(action.get("inputs"));
            assertEquals(inputs.keySet(), parameters.stream().map(p -> p.name()).collect(Collectors.toSet()), name);
            for (var parameter : parameters) {
                String declared = (String) inputs.get(parameter.name());
                assertEquals(!declared.endsWith("?"), parameter.required(), name + ": " + parameter.name());
                String base = declared.replace("?", "").replace("[]", "");
                assertTrue(SCALARS.contains(base) || objects.contains(base), name + ": " + declared);
                String expected = declared.replace("?", "");
                if (expected.endsWith("[]")) expected = "[" + expected.substring(0, expected.length() - 2) + "]";
                assertEquals(expected, parameter.type());
            }
        }
    }

    @Test
    void statesMetricsAndScenariosReferenceActualDefinitions() throws IOException {
        var pack = new DomainPackLoader().load(BusinessPackVerificationTest.packPath());
        var objects = pack.ontology().schema().objectTypes().stream().collect(Collectors.toMap(o -> o.name(), o -> o));
        Set<String> actions = uniqueNames(records(yaml("contracts/actions.yaml"), "actions"), "name");
        Set<String> stateFields = new HashSet<>();
        for (var model : records(yaml("contracts/states.yaml"), "models")) {
            String object = (String) model.get("object");
            String field = (String) model.get("field");
            assertNotNull(objects.get(object), object);
            assertTrue(objects.get(object).properties().stream().anyMatch(p -> p.name().equals(field)), object + "." + field);
            assertTrue(stateFields.add(object + "." + field), "Duplicate state model");
            var values = strings(model.get("values"));
            assertEquals(values.size(), new HashSet<>(values).size());
            for (var transition : records(model, "transitions")) {
                assertTrue(values.contains(transition.get("from")));
                assertTrue(values.contains(transition.get("to")));
                assertTrue(actions.contains(transition.get("action")), transition.toString());
            }
        }
        for (var key : records(yaml("contracts/rules.yaml"), "uniqueKeys")) {
            var object = objects.get(key.get("object"));
            assertNotNull(object);
            for (String field : strings(key.get("fields"))) assertTrue(object.properties().stream().anyMatch(p -> p.name().equals(field)));
        }
        var metrics = records(yaml("contracts/metrics.yaml"), "metrics");
        uniqueNames(metrics, "code");
        for (var metric : metrics) {
            assertTrue(objects.containsKey(metric.get("object")));
            if (metric.containsKey("sourceObjects")) {
                for (String object : strings(metric.get("sourceObjects"))) {
                    assertTrue(objects.containsKey(object), object);
                }
            }
            for (String field : List.of("grain", "definition", "numerator", "denominator", "organizationBasis"))
                assertFalse(((String) metric.get(field)).isBlank());
        }
        var scenarios = records(yaml("contracts/scenarios.yaml"), "scenarios");
        uniqueNames(scenarios, "id");
        for (var scenario : scenarios) {
            for (String action : strings(scenario.get("actions"))) assertTrue(actions.contains(action), action);
            assertFalse(strings(scenario.get("expect")).isEmpty());
        }
    }

    @Test
    void coversAllFunctionalRequirementIdsAndExplicitPilotExtensions() throws IOException {
        Set<String> expected = Set.of(
                "DXK_SJGL", "DXK_DXLB", "DXK_DXXQ", "DXK_YHTY",
                "BQZX_BQML", "BQZX_BGGX", "BQZX_ZDFB", "BQZX_DWXZ", "BQZX_ZWLY", "BQZX_RGCS", "BQZX_RGFB", "BQZX_YJQD", "BQZX_BQLS",
                "NRK_NRFW", "NRK_WHQX", "NRK_GLGZ", "NRK_RWRT",
                "TSRW_CFYZ", "TSRW_JSDX", "TSRW_FSFS", "TSRW_YDXS", "TSRW_AQCS", "TSRW_RZZT", "TSRW_TJSH", "TSRW_XDCH",
                "LTDJT_QDBJ", "LTDJT_SFSP", "LTDJT_HZZT", "LTDJT_YQWD", "LTDJT_BKY", "LTDJT_H5",
                "TJ_DXTJ", "TJ_BQTJ", "TJ_TSYD", "TJ_DJTJ",
                "AIZS_CXNL", "AIZS_ZXCZ", "AIZS_NLBY", "AIZS_QXKZ");
        var coverage = records(yaml("contracts/coverage.yaml"), "requirements");
        Set<String> ids = uniqueNames(coverage, "id");
        assertEquals(expected, ids.stream().filter(id -> id.startsWith("UR_F_ZWDXK_"))
                .map(id -> id.substring("UR_F_ZWDXK_".length())).collect(Collectors.toSet()));
        assertTrue(ids.containsAll(Set.of("PLAN_MATTER", "PLAN_RISK", "PLAN_GOVERNANCE")));
        var actions = uniqueNames(records(yaml("contracts/actions.yaml"), "actions"), "name");
        var rules = uniqueNames(records(yaml("contracts/rules.yaml"), "rules"), "id");
        for (var requirement : coverage) {
            assertFalse(strings(requirement.get("actions")).isEmpty(), requirement.toString());
            for (String action : strings(requirement.get("actions"))) assertTrue(actions.contains(action), action);
            for (String rule : strings(requirement.get("rules"))) assertTrue(rules.contains(rule), rule);
            assertEquals("business-definition", requirement.get("coverage"));
        }
        Set<String> coveredActions = coverage.stream().flatMap(r -> strings(r.get("actions")).stream())
                .collect(Collectors.toSet());
        assertEquals(actions, coveredActions, "Every business action needs a source requirement mapping");
        for (var action : records(yaml("contracts/actions.yaml"), "actions")) {
            for (String source : ((String) action.get("source")).split(" ")) {
                String id = expected.contains(source) ? "UR_F_ZWDXK_" + source : source;
                assertTrue(ids.contains(id), action.get("name") + ": unknown requirement " + source);
                var requirement = coverage.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
                assertTrue(strings(requirement.get("actions")).contains(action.get("name")),
                        action.get("name") + ": source must link back to the action in " + id);
            }
        }
        assertFalse(records(yaml("contracts/rules.yaml"), "conflicts").isEmpty());
    }

    @Test
    void baselineCatalogHasValidDepthAndKeepsManualOnlyAndStageSemantics() throws IOException {
        var nodes = records(yaml("contracts/tag-catalog.yaml"), "nodes");
        uniqueNames(nodes, "code");
        var byCode = nodes.stream().collect(Collectors.toMap(n -> (String) n.get("code"), n -> n));
        for (var node : nodes) {
            var seen = new HashSet<String>();
            Map<String, Object> cursor = node;
            int depth = 0;
            while (cursor != null) {
                assertTrue(seen.add((String) cursor.get("code")), "Catalog cycle");
                assertTrue(++depth <= 3, "Catalog exceeds three levels");
                String parent = (String) cursor.get("parent");
                if (parent.isEmpty()) break;
                cursor = byCode.get(parent);
                assertNotNull(cursor, "Missing parent " + parent);
                assertEquals(cursor.get("dimension"), node.get("dimension"));
            }
        }
        assertEquals("MANUAL_ONLY", byCode.get("NEW_PROMOTION").get("mode"));
        assertEquals("MANUAL_ONLY", byCode.get("RETIREMENT").get("mode"));
        assertEquals("STAGE", byCode.get("SPECIAL").get("scope"));
    }

    private Map<String, Object> yaml(String path) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return mapping(new Yaml(new SafeConstructor(options)).load(
                Files.readString(BusinessPackVerificationTest.packPath().resolve(path))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> records(Map<String, Object> root, String key) {
        assertInstanceOf(List.class, root.get(key), key);
        return ((List<?>) root.get(key)).stream().map(BusinessContractDefinitionTest::mapping).toList();
    }

    private static List<String> strings(Object value) {
        assertInstanceOf(List.class, value);
        return ((List<?>) value).stream().map(v -> {
            assertInstanceOf(String.class, v);
            return (String) v;
        }).toList();
    }

    private static Set<String> uniqueNames(List<Map<String, Object>> records, String field) {
        Set<String> values = new HashSet<>();
        for (var record : records) assertTrue(values.add((String) record.get(field)), "Duplicate " + record.get(field));
        return values;
    }
}
