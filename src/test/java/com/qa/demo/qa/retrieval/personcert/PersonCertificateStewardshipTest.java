package com.qa.demo.qa.retrieval.personcert;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonCertificateStewardshipTest {

    @Test
    void evidenceLineUsesBusinessLabelsOnly() {
        String line = new PersonCertificateStewardship(
                "ICP备案",
                "万仕道（北京）管理咨询有限责任公司",
                "证照执行人",
                "有效",
                "cert-99",
                "4",
                "110008506",
                "9"
        ).toEvidenceLine();

        assertTrue(line.contains("证照类型=ICP备案"));
        assertTrue(line.contains("公司=万仕道"));
        assertTrue(line.contains("角色=证照执行人"));
        assertFalse(line.contains("110008506"));
        assertFalse(line.contains("cert-99"));
        assertFalse(line.contains("; 9"));
    }
}
