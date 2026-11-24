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
package org.apache.fineract.portfolio.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.portfolio.loanproduct.service.LoanProductReadPlatformService;
import org.apache.fineract.portfolio.search.data.AdHocQuerySearchConditions;
import org.apache.fineract.portfolio.search.data.AdHocQuerySearchConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SearchReadPlatformServiceImplTest {

    private static final String SUBQUERY_INJECTION = "> 0 OR (SELECT COUNT(*) FROM m_appuser) > 0 OR a.percentOut >";

    private final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    private final SearchReadPlatformServiceImpl underTest = new SearchReadPlatformServiceImpl(jdbcTemplate,
            mock(PlatformSecurityContext.class), mock(LoanProductReadPlatformService.class), mock(OfficeReadPlatformService.class),
            mock(DatabaseSpecificSQLGenerator.class));

    @ParameterizedTest
    @ValueSource(strings = { SUBQUERY_INJECTION, "> (SELECT COUNT(*) FROM m_appuser)", "LIKE", "BETWEEN" })
    void rejectsUnsupportedOutStandingAmountPercentageConditionBeforeQuerying(final String condition) {
        final AdHocQuerySearchConditions conditions = conditions(true, condition, false, null);

        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveAdHocQueryMatchingData(conditions));

        assertEquals(AdHocQuerySearchConstants.outStandingAmountPercentageConditionParamName,
                exception.getErrors().get(0).getParameterName());
        verifyNoInteractions(jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = { SUBQUERY_INJECTION, "> (SELECT COUNT(*) FROM m_appuser)", "LIKE", "BETWEEN" })
    void rejectsUnsupportedOutstandingAmountConditionBeforeQuerying(final String condition) {
        final AdHocQuerySearchConditions conditions = conditions(false, null, true, condition);

        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveAdHocQueryMatchingData(conditions));

        assertEquals(AdHocQuerySearchConstants.outstandingAmountConditionParamName, exception.getErrors().get(0).getParameterName());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsMissingConditionWhenFilterIsIncluded() {
        final AdHocQuerySearchConditions conditions = conditions(true, null, false, null);

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.retrieveAdHocQueryMatchingData(conditions));
        verifyNoInteractions(jdbcTemplate);
    }

    @ParameterizedTest
    @ValueSource(strings = { "=", "<", "<=", ">", ">=" })
    void bindsValuesForSupportedComparisonConditions(final String condition) {
        final String sql = executeAndCaptureSql(conditions(true, condition, true, condition));

        assertTrue(sql.contains(" a.percentOut " + condition + " :outStandingAmountPercentage "), sql);
        assertTrue(sql.contains(" a.outstandingAmt " + condition + " :outstandingAmount "), sql);
    }

    @Test
    void bindsValuesForBetweenCondition() {
        final String sql = executeAndCaptureSql(conditions(true, "between", true, "between"));

        assertTrue(sql.contains("a.percentOut between :minOutStandingAmountPercentage and :maxOutStandingAmountPercentage"), sql);
        assertTrue(sql.contains("a.outstandingAmt between :minOutstandingAmount and :maxOutstandingAmount"), sql);
    }

    @Test
    void ignoresConditionsWhenFiltersAreNotIncluded() {
        final String sql = executeAndCaptureSql(conditions(false, SUBQUERY_INJECTION, false, SUBQUERY_INJECTION));

        assertFalse(sql.contains("m_appuser"), sql);
        assertFalse(sql.contains(":outStandingAmountPercentage"), sql);
        assertFalse(sql.contains(":outstandingAmount"), sql);
    }

    @SuppressWarnings("unchecked")
    private String executeAndCaptureSql(final AdHocQuerySearchConditions conditions) {
        underTest.retrieveAdHocQueryMatchingData(conditions);
        final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        return sql.getValue();
    }

    private static AdHocQuerySearchConditions conditions(final boolean includePercentage, final String percentageCondition,
            final boolean includeAmount, final String amountCondition) {
        return AdHocQuerySearchConditions.instance(null, null, null, null, null, null, includePercentage, percentageCondition,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, includeAmount, amountCondition, BigDecimal.ONE, BigDecimal.TEN,
                BigDecimal.TEN);
    }
}
