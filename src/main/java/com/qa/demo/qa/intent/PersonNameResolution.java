package com.qa.demo.qa.intent;

import java.util.List;

/**
 * 人名解析结果：规范名（业务展示）、员工唯一标识、是否仍歧义。
 */
public record PersonNameResolution(
        String canonicalName,
        boolean ambiguous,
        List<String> candidates,
        Integer employeeId
) {
    public static PersonNameResolution resolved(String name) {
        return resolved(name, null);
    }

    public static PersonNameResolution resolved(String name, Integer employeeId) {
        return new PersonNameResolution(
                name == null ? "" : name,
                false,
                List.of(),
                employeeId != null && employeeId > 0 ? employeeId : null
        );
    }

    public static PersonNameResolution ambiguous(String partial, List<String> candidates) {
        return new PersonNameResolution(
                partial == null ? "" : partial,
                true,
                candidates == null ? List.of() : candidates,
                null
        );
    }

    public boolean needsClarification() {
        return ambiguous && !candidates.isEmpty();
    }
}
