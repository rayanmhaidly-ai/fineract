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
package org.apache.fineract.infrastructure.dataqueries.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.dataqueries.data.GenericResultsetData;
import org.apache.fineract.infrastructure.security.exception.SqlValidationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlInjectionPreventerService;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;

@ExtendWith(MockitoExtension.class)
class ReadReportingServiceImplTest {

    private static final String REPORT_NAME = "Client Listing";
    private static final String REPORT_SQL = "select c.id from m_client c join m_office o on o.id = c.office_id"
            + " where o.hierarchy like concat('${currentUserHierarchy}', '%') and o.id = ${officeId}"
            + " and c.activation_date <= '${endDate}'";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private GenericDataService genericDataService;
    @Mock
    private SqlInjectionPreventerService sqlInjectionPreventerService;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;
    @Mock
    private FineractProperties fineractProperties;

    @InjectMocks
    private ReadReportingServiceImpl underTest;

    @BeforeEach
    void setUp() {
        lenient().when(sqlInjectionPreventerService.quoteIdentifier(anyString())).thenAnswer(i -> "\"" + i.getArgument(0) + "\"");
        lenient().when(genericDataService.wrapSQL(anyString())).thenAnswer(i -> i.getArgument(0));
        SqlRowSet rowSet = mock(SqlRowSet.class);
        lenient().when(rowSet.next()).thenReturn(true);
        lenient().when(rowSet.getString("the_sql")).thenReturn(REPORT_SQL);
        lenient().when(jdbcTemplate.queryForRowSet(anyString(), eq(REPORT_NAME))).thenReturn(rowSet);
        AppUser user = mock(AppUser.class);
        Office office = mock(Office.class);
        lenient().when(office.getHierarchy()).thenReturn(".1.");
        lenient().when(user.getOffice()).thenReturn(office);
        lenient().when(user.getId()).thenReturn(7L);
        lenient().when(context.authenticatedUser()).thenReturn(user);
        lenient().when(sqlGenerator.currentBusinessDate()).thenReturn("DATE '2023-12-31'");
        lenient().when(sqlGenerator.currentTenantDateTime()).thenReturn("TIMESTAMP '2023-12-31 00:00:00'");
    }

    @Test
    void validReportParametersAreSubstitutedAndReportRuns() {
        GenericResultsetData expected = mock(GenericResultsetData.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(genericDataService.fillGenericResultSet(sqlCaptor.capture())).thenReturn(expected);

        GenericResultsetData result = underTest.retrieveGenericResultset(REPORT_NAME, "report",
                Map.of("${officeId}", "1", "${endDate}", "2023-12-31"), false);

        assertThat(result).isSameAs(expected);
        assertThat(sqlCaptor.getValue()).isEqualTo("select c.id from m_client c join m_office o on o.id = c.office_id"
                + " where o.hierarchy like concat('.1.', '%') and o.id = 1 and c.activation_date <= '2023-12-31'");
    }

    @Test
    void maliciousUnquotedReportParameterIsRejectedBeforeSqlExecution() {
        Map<String, String> params = Map.of("${officeId}", "1 OR 2>1", "${endDate}", "2023-12-31");

        assertThatThrownBy(() -> underTest.retrieveGenericResultset(REPORT_NAME, "report", params, false))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("officeId");
        verify(genericDataService, never()).fillGenericResultSet(anyString());
    }

    @Test
    void maliciousQuotedReportParameterIsRejectedBeforeSqlExecution() {
        Map<String, String> params = Map.of("${officeId}", "1", "${endDate}",
                "2023-12-31' union all select password from m_appuser where 'a'<'b");

        assertThatThrownBy(() -> underTest.retrieveGenericResultset(REPORT_NAME, "report", params, false))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("endDate");
        verify(genericDataService, never()).fillGenericResultSet(anyString());
    }

    @Test
    void reportParameterCannotOverrideCurrentUserHierarchy() {
        Map<String, String> params = Map.of("${officeId}", "1", "${endDate}", "2023-12-31", "${currentUserHierarchy}", ".");

        assertThatThrownBy(() -> underTest.retrieveGenericResultset(REPORT_NAME, "report", params, false))
                .isInstanceOf(SqlValidationException.class).hasMessageContaining("reserved");
        verify(genericDataService, never()).fillGenericResultSet(anyString());
    }
}
