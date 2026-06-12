package com.qa.demo.qa.cdc;

/**
 * 图谱/向量写入用的人员展示信息（姓名 + 花名 + id）。
 */
public record CdcPersonDisplay(
        String personId,
        String name,
        String anotherName
) {
    public String displayName() {
        String n = name == null ? "" : name.trim();
        String a = anotherName == null ? "" : anotherName.trim();
        if (n.isEmpty() && a.isEmpty()) {
            return personId == null || personId.isBlank() ? "" : "员工#" + personId;
        }
        if (a.isEmpty() || a.equals(n)) {
            return n;
        }
        if (n.isEmpty()) {
            return a;
        }
        return n + "(" + a + ")";
    }
}
