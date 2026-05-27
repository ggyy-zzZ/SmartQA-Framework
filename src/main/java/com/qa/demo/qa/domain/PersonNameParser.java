package com.qa.demo.qa.domain;

import com.qa.demo.qa.config.BusinessRulesConfig;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 问句/槽位中人名的轻量解析（敬称剥离等），供意图 enrich 与实体抽取使用。
 * <p>
 * 敬称后缀列表从 {@link BusinessRulesConfig} 读取，不再硬编码。
 */
@Component
public final class PersonNameParser {

    private static Pattern HONORIFIC_SUFFIX;

    private PersonNameParser() {
    }

    /**
     * 设置敬称后缀模式（由配置初始化调用）。
     */
    public static void setHonorificSuffixPattern(Pattern pattern) {
        HONORIFIC_SUFFIX = pattern;
    }

    public static boolean hasHonorificSuffix(String personName) {
        if (personName == null || personName.isBlank() || HONORIFIC_SUFFIX == null) {
            return false;
        }
        return HONORIFIC_SUFFIX.matcher(personName.trim()).find();
    }

    /**
     * 去掉敬称后缀，如 戴先生 → 戴（用于在员工库中做姓/名前缀匹配）。
     */
    public static String stripHonorific(String personName) {
        if (personName == null || personName.isBlank() || HONORIFIC_SUFFIX == null) {
            return personName == null ? "" : personName;
        }
        String n = personName.trim();
        String stripped = HONORIFIC_SUFFIX.matcher(n).replaceAll("").trim();
        return stripped.isEmpty() ? n : stripped;
    }
}
