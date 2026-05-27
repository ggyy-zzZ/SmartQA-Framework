package com.qa.demo.qa.retrieval;

import com.qa.demo.qa.config.BusinessRulesConfig;
import com.qa.demo.qa.config.StructuredDataProvider;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.IntentDecision;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用的场景数据查询服务。
 * <p>
 * 根据 {@link IntentDecision#getQueryType()} 匹配 business-rules.json 中的配置，
 * 委托 {@link StructuredDataProvider} 执行配置化的数据查询。
 * <p>
 * 原则：
 * <ul>
 *   <li>不硬编码特定业务场景的查询逻辑</li>
 *   <li>新增场景只需在 business-rules.json 中配置数据源</li>
 *   <li>所有结构化查询都通过配置驱动</li>
 * </ul>
 */
@Service
public class ScenarioQueryService {

    private final StructuredDataProvider dataProvider;
    private final BusinessRulesConfig config;

    public ScenarioQueryService(StructuredDataProvider dataProvider, BusinessRulesConfig config) {
        this.dataProvider = dataProvider;
        this.config = config;
    }

    /**
     * 根据意图决策执行场景化数据查询。
     *
     * @param intent 意图决策（包含 queryType、personName、personEmployeeId 等）
     * @param maxRows 最大返回条数
     * @return ContextChunk 列表
     */
    public List<ContextChunk> retrieveByIntent(IntentDecision intent, int maxRows) {
        if (intent == null || intent.queryType() == null || intent.queryType().isBlank()) {
            return List.of();
        }

        String queryType = intent.queryType();
        List<ContextChunk> result = new ArrayList<>();

        // 根据 queryType 匹配数据源配置
        for (BusinessRulesConfig.StructuredQueryConfig queryConfig : config.getDataSources().getStructuredQueries()) {
            // 目前配置中的 id 即为 queryType（简化设计）
            if (!queryType.equals(queryConfig.getId()) && !queryType.contains(queryConfig.getId())) {
                continue;
            }

            Set<Integer> entityIds = resolveEntityIds(intent);
            if (entityIds.isEmpty()) {
                continue;
            }

            List<ContextChunk> chunks = dataProvider.query(
                    queryConfig.getId(),
                    entityIds,
                    "employee_id", // 通用实体ID列名
                    maxRows
            );
            result.addAll(chunks);
        }

        return result;
    }

    /**
     * 通用实体ID解析。
     * 优先使用 intent 中的 employeeId，回退到通过姓名解析。
     */
    private Set<Integer> resolveEntityIds(IntentDecision intent) {
        Set<Integer> ids = new LinkedHashSet<>();

        if (intent.hasPersonEmployeeId()) {
            ids.add(intent.personEmployeeId());
            return ids;
        }

        if (intent.hasPersonFocus() && intent.personName() != null && !intent.personName().isBlank()) {
            // 通过姓名解析需要查询数据库，这里简化处理
            // 实际实现可以通过 EmployeeBaseKnowledgeService 或直接查询
            // 目前返回空，由调用方处理
        }

        return ids;
    }

    /**
     * 检查是否匹配某个场景的数据查询。
     */
    public boolean matchesScenario(IntentDecision intent, String scenarioId) {
        if (intent == null || scenarioId == null) {
            return false;
        }
        return scenarioId.equals(intent.queryType()) ||
               (intent.queryType() != null && intent.queryType().contains(scenarioId));
    }
}