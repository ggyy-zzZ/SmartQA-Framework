package com.qa.demo.qa.cdc.graph;

/**
 * 从 CDC 行解析出的一条「人员 — 任职角色 — 公司」绑定（元数据，不含姓名详情）。
 */
public record CdcPersonRoleBinding(
        String sourceColumn,
        String roleLabel,
        String personId,
        String personKey
) {
}
