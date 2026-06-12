package com.qa.demo.qa.rules;

import org.kie.api.KieServices;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.StatelessKieSession;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Drools Kie 容器与 4 个 KieSession 的 Spring 装配。
 * <p>
 * 启动期使用 {@link KieServices.Factory#getKieClasspathContainer()} 一次性加载
 * {@code META-INF/kmodule.xml} 中声明的 kbase/session。加载失败时由
 * {@link ApplicationRunner#run(ApplicationArguments)} fail-fast 抛出，避免线上"沉默降级"。
 * <p>
 * 灰度开关 {@code qa.rule.drools-enabled}=false 时，{@link KieContainer} 不会被装配，
 * 所有 *RuleService 走静态 Java 老逻辑（{@code qa.rule.fallback.classic}=true）。
 */
@Configuration
@ConditionalOnProperty(name = "qa.rule.drools-enabled", havingValue = "true", matchIfMissing = true)
public class DroolsKieConfiguration {

    public static final String KBASE = "qa-kbase";
    public static final String SESSION_INTENT = "qa-intent-routing";
    public static final String SESSION_EVIDENCE = "qa-evidence-merge";
    public static final String SESSION_GATE = "qa-answer-gate";
    public static final String SESSION_CLARIFY = "qa-clarification";

    @Bean(destroyMethod = "dispose")
    public KieContainer kieContainer() {
        return KieServices.Factory.get().getKieClasspathContainer();
    }

    @Bean
    public StatelessKieSession qaIntentRoutingStateless(KieContainer container) {
        return container.newStatelessKieSession(SESSION_INTENT);
    }

    @Bean
    public KieSession qaEvidenceMergeStateful(KieContainer container) {
        return container.newKieSession(SESSION_EVIDENCE);
    }

    @Bean
    public StatelessKieSession qaAnswerGateStateless(KieContainer container) {
        return container.newStatelessKieSession(SESSION_GATE);
    }

    @Bean
    public StatelessKieSession qaClarificationStateless(KieContainer container) {
        return container.newStatelessKieSession(SESSION_CLARIFY);
    }

    /**
     * 启动期自检：四个 session 各 fire 一次空 fact，DRL 编译错误立即 fail-fast。
     * Order 取最小值，确保在所有业务 ApplicationRunner 之前完成。
     */
    @Bean
    @Order(Integer.MIN_VALUE + 100)
    public ApplicationRunner droolsSelfTest(
            StatelessKieSession qaIntentRoutingStateless,
            KieSession qaEvidenceMergeStateful,
            StatelessKieSession qaAnswerGateStateless,
            StatelessKieSession qaClarificationStateless
    ) {
        return args -> {
            fireOnceStateless(qaIntentRoutingStateless, SESSION_INTENT);
            fireOnceStateful(qaEvidenceMergeStateful, SESSION_EVIDENCE);
            fireOnceStateless(qaAnswerGateStateless, SESSION_GATE);
            fireOnceStateless(qaClarificationStateless, SESSION_CLARIFY);
        };
    }

    private static void fireOnceStateless(StatelessKieSession session, String name) {
        if (session == null) {
            throw new IllegalStateException("Drools self-test failed: " + name + " is null");
        }
        session.execute(new Object());
    }

    private static void fireOnceStateful(KieSession session, String name) {
        if (session == null) {
            throw new IllegalStateException("Drools self-test failed: " + name + " is null");
        }
        try {
            session.insert(new Object());
            session.fireAllRules();
        } finally {
            session.dispose();
        }
    }
}
