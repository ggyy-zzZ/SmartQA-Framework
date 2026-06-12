package com.qa.demo.qa.admin;

import java.util.ArrayList;
import java.util.List;

/**
 * 静态脚本参数构造器。每个方法返回完整可执行的 {@code [script, arg1, arg2, ...]} 列表。
 * 不持有 Spring 组件；纯函数。
 */
public final class AdminOpsServiceScripts {

    public static final String SYNC_NEO4J = "scripts/enterprise_pipeline/sync_neo4j.py";
    public static final String SYNC_VECTORS = "scripts/enterprise_pipeline/sync_vectors_qdrant.py";
    public static final String WIPE_NEO4J = "scripts/ops/wipe_neo4j.py";

    private AdminOpsServiceScripts() {
    }

    public static List<String> neo4jSyncArgs(boolean wipe, boolean slim, int limit) {
        List<String> args = new ArrayList<>();
        args.add(SYNC_NEO4J);
        args.add("--input");
        args.add("data/knowledge/enterprise_mysql_clean.jsonl");
        args.add("--truncate");
        args.add("4000");
        if (slim) {
            args.add("--slim");
        }
        if (wipe) {
            args.add("--wipe");
        }
        if (limit > 0) {
            args.add("--limit");
            args.add(String.valueOf(limit));
        }
        return args;
    }

    public static List<String> neo4jWipeOnlyArgs() {
        return List.of(WIPE_NEO4J);
    }

    public static List<String> qdrantSyncKnowledgeArgs(boolean recreate) {
        List<String> args = new ArrayList<>();
        args.add(SYNC_VECTORS);
        args.add("--input");
        args.add("data/knowledge/enterprise_mysql_clean.jsonl");
        args.add("--collection");
        args.add("enterprise_knowledge_v2");
        if (recreate) {
            args.add("--recreate");
        }
        return args;
    }

    public static List<String> qdrantSyncActiveLearningArgs(boolean recreate) {
        List<String> args = new ArrayList<>();
        args.add(SYNC_VECTORS);
        args.add("--input");
        args.add("data/knowledge/enterprise_mysql_clean.jsonl");
        args.add("--collection");
        args.add("enterprise_active_learning_v2");
        if (recreate) {
            args.add("--recreate");
        }
        return args;
    }
}
