/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.infrastructure.security.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.fineract.infrastructure.security.exception.SqlValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportSqlPlaceholderResolverTest {

    private static final Map<String, String> SYSTEM = Map.of("currentUserHierarchy", ".1.", "currentUserId", "7");

    @Test
    void resolvesValidValuesInQuotedAndUnquotedContexts() {
        String sql = "select * from m_client c join m_office o on o.id = c.office_id"
                + " where o.hierarchy like concat('${currentUserHierarchy}', '%') and o.id = ${officeId}"
                + " and c.display_name like '${name}%' and c.activation_date <= '${endDate}' and c.id in (${ids})";

        String resolved = ReportSqlPlaceholderResolver.resolve(sql,
                Map.of("officeId", "-1", "name", "Jane O Doe", "endDate", "2023-12-31", "ids", "1, 2,3"), SYSTEM);

        assertThat(resolved).isEqualTo("select * from m_client c join m_office o on o.id = c.office_id"
                + " where o.hierarchy like concat('.1.', '%') and o.id = -1"
                + " and c.display_name like 'Jane O Doe%' and c.activation_date <= '2023-12-31' and c.id in (1, 2,3)");
    }

    @Test
    void allowsQuotedStringListOutsideQuotes() {
        String sql = "select * from stretchy_report sr where sr.report_name in(${reportListing}) and sr.x = DATE ${startDate}";

        String resolved = ReportSqlPlaceholderResolver.resolve(sql,
                Map.of("reportListing", "'Client Listing','Loan Listing'", "startDate", "'2023-01-01'"), SYSTEM);

        assertThat(resolved).isEqualTo(
                "select * from stretchy_report sr where sr.report_name in('Client Listing','Loan Listing') and sr.x = DATE '2023-01-01'");
    }

    @ParameterizedTest
    @ValueSource(strings = { "1 or 1=1", "1 OR 2>1", "1) union all select password from m_appuser where (1=1", "(select 1)",
            "1; drop table m_client", "1 -- ", "'a' or 'b'<'c'", "'a'' or 1=1 --'", "" })
    void rejectsMaliciousValueOutsideQuotes(String value) {
        String sql = "select * from m_office o where o.id = ${officeId}";

        assertThatThrownBy(() -> ReportSqlPlaceholderResolver.resolve(sql, Map.of("officeId", value), SYSTEM))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("officeId");
    }

    @ParameterizedTest
    @ValueSource(strings = { "2023-01-01' or '1'='1", "x' union all select username from m_appuser where 'a'<'b", "a\\' or 1=1 -- ",
            "x\" or \"a\"<\"b" })
    void rejectsMaliciousValueInsideQuotes(String value) {
        String sql = "select * from m_loan l where l.disbursedon_date >= '${startDate}' and l.x = \"${startDate}\"";

        assertThatThrownBy(() -> ReportSqlPlaceholderResolver.resolve(sql, Map.of("startDate", value), SYSTEM))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("startDate");
    }

    @Test
    void rejectsOverrideOfSystemPlaceholder() {
        String sql = "select * from m_office o where o.hierarchy like concat('${currentUserHierarchy}', '%')";

        assertThatThrownBy(() -> ReportSqlPlaceholderResolver.resolve(sql, Map.of("currentUserHierarchy", "."), SYSTEM))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("reserved");
    }

    @Test
    void doesNotReScanSubstitutedValues() {
        String sql = "select '${a}' as a, ${b} as b";

        String resolved = ReportSqlPlaceholderResolver.resolve(sql, Map.of("a", "${b}", "b", "1"), SYSTEM);

        assertThat(resolved).isEqualTo("select '${b}' as a, 1 as b");
    }

    @Test
    void tracksQuotesAndCommentsAroundPlaceholders() {
        String sql = "select 'it''s' as t, ${id} as i /* don't ${id} */ -- client's ${id}\n, '${name}' as n";

        String resolved = ReportSqlPlaceholderResolver.resolve(sql, Map.of("id", "5", "name", "abc"), SYSTEM);

        assertThat(resolved).isEqualTo("select 'it''s' as t, 5 as i /* don't ${id} */ -- client's ${id}\n, 'abc' as n");
    }

    @Test
    void leavesUnknownPlaceholdersUntouched() {
        String sql = "select ${unknown} from t where id = ${id}";

        assertThat(ReportSqlPlaceholderResolver.resolve(sql, Map.of("id", "1"), SYSTEM)).isEqualTo("select ${unknown} from t where id = 1");
    }
}
