package com.qa.demo.qa.rules.fact;

/**
 * Drools Fact：QA 主链路的最小上下文。
 * <p>
 * 所有字段都通过 getter/setter 暴露（Drools 9.x 反射读取 JavaBean 风格属性）。
 */
public class QaContextFact {
    private String turnId;
    private String scope;
    private String conversationId;
    private String question;
    /** 小写 + trim 后的 question，便于 DRL 关键字匹配 */
    private String normalizedQuestion;

    public QaContextFact() {
    }

    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getNormalizedQuestion() { return normalizedQuestion; }
    public void setNormalizedQuestion(String normalizedQuestion) { this.normalizedQuestion = normalizedQuestion; }
}
