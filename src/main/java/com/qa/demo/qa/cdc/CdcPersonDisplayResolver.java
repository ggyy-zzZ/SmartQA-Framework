package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.qa.demo.qa.retrieval.EmployeeBaseKnowledgeService;
import org.springframework.stereotype.Component;

/**
 * CDC 写入前将 employee id 解析为姓名/花名（优先 CDC 行，其次启动时员工索引）。
 */
@Component
public class CdcPersonDisplayResolver {

    private final EmployeeBaseKnowledgeService employeeKnowledge;

    public CdcPersonDisplayResolver(EmployeeBaseKnowledgeService employeeKnowledge) {
        this.employeeKnowledge = employeeKnowledge;
    }

    public CdcPersonDisplay fromEmployeeRow(JsonNode row) {
        String personId = CdcEntityIdResolver.resolveEntityId("employee", row);
        String name = CdcTdcompFields.employeeName(row);
        String anotherName = CdcTdcompFields.employeeAnotherName(row);
        if ((name == null || name.isBlank()) && personId != null) {
            CdcPersonDisplay cached = fromPersonId(personId);
            if (cached != null) {
                return mergePreferRow(personId, name, anotherName, cached);
            }
        }
        return new CdcPersonDisplay(personId, name, anotherName);
    }

    public CdcPersonDisplay fromPersonId(String personId) {
        if (personId == null || personId.isBlank()) {
            return new CdcPersonDisplay(null, null, null);
        }
        try {
            int id = Integer.parseInt(personId.trim());
            EmployeeBaseKnowledgeService.EmployeeRecord record = employeeKnowledge.getEmployeeById(id);
            if (record == null) {
                return new CdcPersonDisplay(personId, null, null);
            }
            return new CdcPersonDisplay(
                    String.valueOf(record.id()),
                    record.name(),
                    record.anotherName()
            );
        } catch (NumberFormatException e) {
            return new CdcPersonDisplay(personId, null, null);
        }
    }

    private static CdcPersonDisplay mergePreferRow(
            String personId,
            String rowName,
            String rowAnother,
            CdcPersonDisplay cached
    ) {
        String name = rowName != null && !rowName.isBlank() ? rowName : cached.name();
        String another = rowAnother != null && !rowAnother.isBlank() ? rowAnother : cached.anotherName();
        return new CdcPersonDisplay(personId, name, another);
    }
}
