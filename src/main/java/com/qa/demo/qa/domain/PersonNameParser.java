package com.qa.demo.qa.domain;

import java.util.regex.Pattern;

/**
 * 问句/槽位中人名的轻量解析（敬称剥离等），供意图 enrich 与实体抽取使用。
 */
public final class PersonNameParser {

    private static final Pattern HONORIFIC_SUFFIX =
            Pattern.compile("(先生|女士|小姐|老师|经理|总监|总裁|董事|总|工)$");

    private PersonNameParser() {
    }

    public static boolean hasHonorificSuffix(String personName) {
        if (personName == null || personName.isBlank()) {
            return false;
        }
        return HONORIFIC_SUFFIX.matcher(personName.trim()).find();
    }

    /**
     * 去掉敬称后缀，如 戴先生 → 戴（用于在员工库中做姓/名前缀匹配）。
     */
    public static String stripHonorific(String personName) {
        if (personName == null || personName.isBlank()) {
            return "";
        }
        String n = personName.trim();
        String stripped = HONORIFIC_SUFFIX.matcher(n).replaceAll("").trim();
        return stripped.isEmpty() ? n : stripped;
    }
}
