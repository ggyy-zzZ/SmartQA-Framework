package com.qa.demo.qa.intent;

import java.util.List;

/**
 * 人名解析结果：规范名、是否仍歧义、候选项（供追问澄清）。
 */
public record PersonNameResolution(
        String canonicalName,
        boolean ambiguous,
        List<String> candidates
) {
    public static PersonNameResolution resolved(String name) {
        return new PersonNameResolution(name == null ? "" : name, false, List.of());
    }

    public static PersonNameResolution ambiguous(String partial, List<String> candidates) {
        return new PersonNameResolution(partial == null ? "" : partial, true, candidates == null ? List.of() : candidates);
    }

    public boolean needsClarification() {
        return ambiguous && !candidates.isEmpty();
    }
}
