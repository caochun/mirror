package gov.objectlibrary.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** A bounded, typed condition tree. No property paths, executable text, regex, or implicit keyword matching. */
final class RuleExpression {
    static final Set<String> FIELDS = Set.of("ageYears", "serviceMonths", "organizationId", "rank", "positionCode",
            "organizationNature", "roleLevel", "positionDomain");
    private static final Set<String> NUMERIC = Set.of("ageYears", "serviceMonths");
    private static final Map<String, String> LABELS = Map.of("ageYears", "年龄", "serviceMonths", "入职月数", "organizationId", "当前单位",
            "rank", "标准职级", "positionCode", "标准岗位编码", "organizationNature", "单位性质映射", "roleLevel", "最高职务层级映射", "positionDomain", "岗位领域映射");
    private static final Map<String, String> OPERATORS = Map.of("EQ", "等于", "LT", "小于", "LTE", "不大于", "GT", "大于", "GTE", "不小于", "BETWEEN", "处于区间", "IN", "属于所选集合");

    static Set<String> validate(Map<String, Object> expression) {
        var dependencies = new TreeSet<String>();
        validate(expression, 0, new int[]{0}, dependencies);
        return Set.copyOf(dependencies);
    }

    private static void validate(Map<String, Object> node, int depth, int[] count, Set<String> dependencies) {
        if (node == null || depth > 5 || ++count[0] > 50) throw new IllegalArgumentException("规则最多5层、50个条件节点");
        if (node.containsKey("all") || node.containsKey("any")) {
            String key = node.containsKey("all") ? "all" : "any";
            if (!node.keySet().equals(Set.of(key)) || !(node.get(key) instanceof List<?> children) || children.isEmpty() || children.size() > 20) {
                throw new IllegalArgumentException("条件组只支持all或any及1—20个子条件");
            }
            for (Object child : children) validate(mapping(child), depth + 1, count, dependencies);
            return;
        }
        if (!node.keySet().equals(Set.of("field", "operator", "value")) || !(node.get("field") instanceof String field)
                || !FIELDS.contains(field) || !(node.get("operator") instanceof String operator)) {
            throw new IllegalArgumentException("规则字段或参数不在白名单中");
        }
        dependencies.add(field);
        Object value = node.get("value");
        if (NUMERIC.contains(field)) {
            if (!Set.of("EQ", "LT", "LTE", "GT", "GTE", "BETWEEN").contains(operator)) throw new IllegalArgumentException("日期数值仅支持比较或区间");
            if (operator.equals("BETWEEN")) {
                if (!(value instanceof List<?> values) || values.size() != 2) throw new IllegalArgumentException("区间必须有两个整数");
                int min = integer(values.getFirst());
                int max = integer(values.get(1));
                if (min > max) throw new IllegalArgumentException("区间顺序无效");
            } else integer(value);
        } else {
            if (operator.equals("EQ")) text(value);
            else if (operator.equals("IN") && value instanceof List<?> values && !values.isEmpty() && values.size() <= 100) values.forEach(RuleExpression::text);
            else throw new IllegalArgumentException("标准字段仅支持EQ或IN");
        }
    }

    static Result evaluate(Map<String, Object> expression, RuleFacts.Input input) {
        if (!input.eligible()) return new Result("SKIPPED", "人员已停用、非对象或当前单位未唯一有效", List.of());
        return evaluateNode(expression, input);
    }

    private static Result evaluateNode(Map<String, Object> node, RuleFacts.Input input) {
        if (node.containsKey("all") || node.containsKey("any")) {
            boolean all = node.containsKey("all");
            List<?> children = (List<?>) node.get(all ? "all" : "any");
            List<Result> results = children.stream().map(child -> evaluateNode(mapping(child), input)).toList();
            if (all && results.stream().anyMatch(result -> result.outcome().equals("NO_MATCH"))) return new Result("NO_MATCH", "至少一个条件确定不满足", List.of());
            if (!all && results.stream().anyMatch(result -> result.outcome().equals("MATCH"))) return new Result("MATCH", "至少一个条件确定满足", List.of());
            var missing = results.stream().flatMap(result -> result.missingFields().stream()).distinct().sorted().toList();
            if (!missing.isEmpty()) return new Result("UNKNOWN", "缺少可靠字段，当前条件组无法确定", missing);
            return new Result(all ? "MATCH" : "NO_MATCH", all ? "全部条件确定满足" : "所有条件均未命中", List.of());
        }
        String field = node.get("field").toString();
        Object actual = input.fields().get(field);
        if (actual == null) return new Result("UNKNOWN", LABELS.get(field) + "缺少可靠字段或映射，无法计算", List.of(field));
        String operator = node.get("operator").toString();
        Object expected = node.get("value");
        boolean matches;
        if (NUMERIC.contains(field)) {
            int number = ((Number) actual).intValue();
            matches = switch (operator) {
                case "EQ" -> number == integer(expected);
                case "LT" -> number < integer(expected);
                case "LTE" -> number <= integer(expected);
                case "GT" -> number > integer(expected);
                case "GTE" -> number >= integer(expected);
                case "BETWEEN" -> number >= integer(((List<?>) expected).getFirst()) && number <= integer(((List<?>) expected).get(1));
                default -> throw new IllegalArgumentException("Unsupported operator");
            };
        } else {
            boolean complete = !(actual instanceof RuleFacts.PartialValues partial) || partial.complete();
            Set<String> actualValues = actual instanceof RuleFacts.PartialValues partial ? partial.values()
                    : actual instanceof Set<?> values ? values.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet()) : Set.of(actual.toString());
            matches = operator.equals("EQ") ? actualValues.contains(expected.toString()) : ((List<?>) expected).stream().anyMatch(value -> actualValues.contains(value.toString()));
            if (!matches && !complete) return new Result("UNKNOWN", "部分当前任职缺少可靠的" + LABELS.get(field), List.of(field));
        }
        boolean reference = Set.of("organizationId", "organizationNature", "roleLevel", "positionDomain").contains(field);
        String reason = reference ? LABELS.get(field) + (matches ? "与所选范围匹配" : "与所选范围不匹配")
                : LABELS.get(field) + OPERATORS.get(operator) + expected + (matches ? "：满足" : "：不满足");
        return new Result(matches ? "MATCH" : "NO_MATCH", reason, List.of());
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0 || ((Number) value).longValue() > 2000) {
            throw new IllegalArgumentException("数值必须是0—2000的整数");
        }
        return ((Number) value).intValue();
    }
    private static void text(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128) throw new IllegalArgumentException("标准值必须为1—128字符");
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("条件节点必须是对象");
        return (Map<String, Object>) map;
    }
    record Result(String outcome, String reason, List<String> missingFields) {}
}
