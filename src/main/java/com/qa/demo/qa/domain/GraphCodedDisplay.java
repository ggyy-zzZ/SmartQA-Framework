package com.qa.demo.qa.domain;

/**
 * 图节点/边上「码值 + 描述」的统一展示格式。
 */
public final class GraphCodedDisplay {

    private GraphCodedDisplay() {
    }

    public static String idWithLabel(String code, String label) {
        String c = code == null ? "" : code.trim();
        String l = label == null ? "" : label.trim();
        if (c.isEmpty()) {
            return l;
        }
        if (l.isEmpty() || c.equals(l)) {
            return c;
        }
        return c + "(" + l + ")";
    }

    public static String personDisplay(String personId, String displayName, String name) {
        String disp = firstNonBlank(displayName, name);
        String pid = personId == null ? "" : personId.trim();
        if (pid.isEmpty()) {
            return disp;
        }
        if (disp.isEmpty()) {
            return pid;
        }
        return idWithLabel(pid, disp);
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }
}
