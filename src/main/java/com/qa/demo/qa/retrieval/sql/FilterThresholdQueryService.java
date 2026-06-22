package com.qa.demo.qa.retrieval.sql;

import com.qa.demo.qa.config.QaAssistantProperties;
import com.qa.demo.qa.core.ContextChunk;
import com.qa.demo.qa.core.InformationNeed;
import com.qa.demo.qa.retrieval.filter.FilterFieldQuestionSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阈值筛选列表（成立年限等）专用 SQL，避免 unified 向量样本缺字段触发误澄清。
 */
@Service
public class FilterThresholdQueryService {

    private static final Logger log = LoggerFactory.getLogger(FilterThresholdQueryService.class);
    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d+)\\s*年");
    private static final Pattern CAPITAL_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(万|万元|w|W|kw|KW|千万|亿)?");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final QaAssistantProperties properties;

    public FilterThresholdQueryService(QaAssistantProperties properties) {
        this.properties = properties;
    }

    public List<ContextChunk> retrieve(String question, InformationNeed need, int limit) {
        if (!properties.isMysqlEnabled()
                || question == null
                || question.isBlank()
                || need == null
                || !FilterFieldQuestionSupport.isFilterThresholdNeed(need)) {
            return List.of();
        }
        String facet = need.facet() == null ? "" : need.facet().trim().toLowerCase();
        if ("establishment_date".equals(facet)) {
            return retrieveEstablishmentOlderThan(question, limit);
        }
        if ("registered_capital".equals(facet)) {
            return retrieveRegisteredCapitalAbove(question, limit);
        }
        return List.of();
    }

    private List<ContextChunk> retrieveEstablishmentOlderThan(String question, int limit) {
        int years = parseYearsThreshold(question, 10);
        LocalDate cutoff = LocalDate.now().minusYears(years);
        String cutoffToken = cutoff.format(YYYYMMDD);
        int capped = Math.max(1, Math.min(limit, properties.getSqlQueryMaxRows()));
        String sql = "SELECT id AS company_id, company_name AS company_name, establishment_date AS establishment_date "
                + "FROM company WHERE deleteflag = 0 "
                + "AND establishment_date IS NOT NULL AND establishment_date != '' "
                + "AND establishment_date <= '" + cutoffToken + "' "
                + "ORDER BY establishment_date LIMIT " + capped;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(capped);
            try (ResultSet rs = statement.executeQuery(sql)) {
                List<ContextChunk> chunks = new ArrayList<>();
                int row = 0;
                while (rs.next() && row < capped) {
                    row++;
                    String companyId = safe(rs.getString("company_id"));
                    String companyName = safe(rs.getString("company_name"));
                    String estDate = safe(rs.getString("establishment_date"));
                    if (companyId.isBlank()) {
                        companyId = "row-" + row;
                    }
                    if (companyName.isBlank()) {
                        companyName = companyId;
                    }
                    String snippet = "company_name=" + companyName
                            + "; 成立日期=" + estDate
                            + "; establishment_date=" + estDate;
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "filter_threshold",
                            snippet,
                            18.0 - row * 0.1,
                            "mysql-filter-threshold"
                    ));
                }
                return chunks;
            }
        } catch (Exception e) {
            log.warn("FilterThresholdQueryService establishment failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static int parseYearsThreshold(String question, int defaultYears) {
        Matcher matcher = YEARS_PATTERN.matcher(question);
        if (matcher.find()) {
            try {
                return Math.max(1, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return defaultYears;
            }
        }
        return defaultYears;
    }

    private List<ContextChunk> retrieveRegisteredCapitalAbove(String question, int limit) {
        long thresholdYuan = parseCapitalThresholdYuan(question, 1_000_000L);
        int capped = Math.max(1, Math.min(limit, properties.getSqlQueryMaxRows()));
        String sql = "SELECT id AS company_id, company_name AS company_name, "
                + "COALESCE(reg_capital, registered_capital, '') AS reg_capital "
                + "FROM company WHERE deleteflag = 0 "
                + "AND COALESCE(reg_capital, registered_capital, '') != '' "
                + "ORDER BY id LIMIT " + capped;
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.setMaxRows(capped);
            try (ResultSet rs = statement.executeQuery(sql)) {
                List<ContextChunk> chunks = new ArrayList<>();
                int row = 0;
                while (rs.next()) {
                    String capRaw = safe(rs.getString("reg_capital"));
                    Long capYuan = parseStoredCapitalYuan(capRaw);
                    if (capYuan == null || capYuan < thresholdYuan) {
                        continue;
                    }
                    row++;
                    if (row > capped) {
                        break;
                    }
                    String companyId = safe(rs.getString("company_id"));
                    String companyName = safe(rs.getString("company_name"));
                    if (companyId.isBlank()) {
                        companyId = "row-" + row;
                    }
                    if (companyName.isBlank()) {
                        companyName = companyId;
                    }
                    String snippet = "company_name=" + companyName
                            + "; 注册资本=" + capRaw
                            + "; reg_capital=" + capRaw
                            + "; registered_capital=" + capRaw;
                    chunks.add(ContextChunk.ofCompany(
                            companyId,
                            companyName,
                            "filter_threshold",
                            snippet,
                            18.0 - row * 0.1,
                            "mysql-filter-threshold"
                    ));
                }
                return chunks;
            }
        } catch (Exception e) {
            log.warn("FilterThresholdQueryService registered_capital failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static long parseCapitalThresholdYuan(String question, long defaultYuan) {
        if (question == null || question.isBlank()) {
            return defaultYuan;
        }
        Matcher matcher = CAPITAL_PATTERN.matcher(question);
        if (!matcher.find()) {
            return defaultYuan;
        }
        double amount;
        try {
            amount = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ex) {
            return defaultYuan;
        }
        String unit = matcher.group(2) == null ? "" : matcher.group(2).trim().toLowerCase();
        double multiplier = switch (unit) {
            case "亿" -> 100_000_000D;
            case "千万", "kw" -> 10_000_000D;
            case "万", "万元", "w" -> 10_000D;
            default -> inferCapitalUnitFromQuestion(question, amount);
        };
        return Math.max(0L, Math.round(amount * multiplier));
    }

    private static double inferCapitalUnitFromQuestion(String question, double amount) {
        String q = question.toLowerCase();
        if (q.contains("亿")) {
            return 100_000_000D;
        }
        if (q.contains("千万")) {
            return 10_000_000D;
        }
        if (q.contains("万") || q.contains("w")) {
            return 10_000D;
        }
        return amount >= 1_000_000D ? 1D : 10_000D;
    }

    static Long parseStoredCapitalYuan(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip().toLowerCase();
        if (text.contains("未维护") || text.contains("暂无") || text.equals("-")) {
            return null;
        }
        Matcher matcher = CAPITAL_PATTERN.matcher(text);
        if (matcher.find()) {
            try {
                double amount = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2) == null ? "" : matcher.group(2).trim().toLowerCase();
                double multiplier = switch (unit) {
                    case "亿" -> 100_000_000D;
                    case "千万", "kw" -> 10_000_000D;
                    case "万", "万元", "w" -> 10_000D;
                    default -> text.contains("亿") ? 100_000_000D
                            : text.contains("千万") ? 10_000_000D
                            : (text.contains("万") || text.contains("w")) ? 10_000D : 1D;
                };
                return Math.round(amount * multiplier);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        String digits = text.replaceAll("[^0-9.]", "");
        if (digits.isBlank()) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(digits));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                properties.getBusinessMysqlUrl(),
                properties.getBusinessMysqlUsername(),
                properties.getBusinessMysqlPassword()
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
