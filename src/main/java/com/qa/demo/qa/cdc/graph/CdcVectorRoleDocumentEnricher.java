package com.qa.demo.qa.cdc.graph;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 cdc-graph-sync.json 的 vectorDocumentEnrichment 为公司 CDC 文档追加人员角色行。
 */
@Component
public class CdcVectorRoleDocumentEnricher {

    private final CdcGraphSyncCatalog catalog;
    private final CdcPersonRoleBindingExtractor bindingExtractor;

    public CdcVectorRoleDocumentEnricher(
            CdcGraphSyncCatalog catalog,
            CdcPersonRoleBindingExtractor bindingExtractor
    ) {
        this.catalog = catalog;
        this.bindingExtractor = bindingExtractor;
    }

    public void appendCompanyRoleSections(StringBuilder sb, JsonNode row) {
        CdcGraphSyncCatalog.VectorTableEnrichmentDef enrichment =
                catalog.vectorDocumentEnrichment().forTable("company");
        if (enrichment == null || enrichment.sections().isEmpty()) {
            return;
        }
        List<CdcPersonRoleBinding> bindings = bindingExtractor.fromCompanyRow(row);
        for (CdcGraphSyncCatalog.VectorRoleSectionDef section : enrichment.sections()) {
            List<String> parts = new ArrayList<>();
            for (CdcPersonRoleBinding binding : bindings) {
                if (!bindingExtractor.matchesRoleLabel(binding, section.roleLabels())) {
                    continue;
                }
                parts.add(bindingExtractor.formatBinding(binding, section.format()));
            }
            if (parts.isEmpty()) {
                continue;
            }
            sb.append(section.label()).append(": ").append(String.join(section.join(), parts)).append('\n');
        }
    }
}
