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
package org.apache.fineract.portfolio.client.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.core.data.PaginationParametersDataValidator;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.utils.ColumnValidator;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.client.mapper.ClientMapper;
import org.apache.fineract.portfolio.collateralmanagement.domain.ClientCollateralManagementRepositoryWrapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ClientReadPlatformServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private CodeValueReadPlatformService codeValueReadPlatformService;
    @Mock
    private PaginationHelper paginationHelper;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;
    @Mock
    private ColumnValidator columnValidator;
    @Mock
    private ClientCollateralManagementRepositoryWrapper clientCollateralManagementRepositoryWrapper;
    @Mock
    private ClientRepositoryWrapper clientRepositoryWrapper;
    @Mock
    private ClientMapper clientMapper;

    private ClientReadPlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        underTest = new ClientReadPlatformServiceImpl(jdbcTemplate, context, codeValueReadPlatformService, paginationHelper, sqlGenerator,
                columnValidator, clientCollateralManagementRepositoryWrapper, clientRepositoryWrapper, clientMapper,
                new PaginationParametersDataValidator());
    }

    @ParameterizedTest
    @ValueSource(strings = { "(CASE WHEN (1<2) THEN c.id ELSE c.display_name END)",
            "(select case when substring(version(),1,1)='8' then c.id else c.display_name end)", "c.id, (select sleep(5))",
            "displayName; drop table m_client", "1" })
    void retrieveAllRejectsOrderByNotInAllowlistBeforeBuildingQuery(String maliciousOrderBy) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy(maliciousOrderBy).sortOrder("ASC").build();

        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveAll(searchParameters));

        assertEquals("validation.msg.client.orderBy.value.is.not.supported",
                exception.getErrors().get(0).getUserMessageGlobalisationCode());
        verifyNoInteractions(context, sqlGenerator, paginationHelper, jdbcTemplate, columnValidator);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ASC, (CASE WHEN (1<2) THEN c.id ELSE c.display_name END)", "DESC; drop table m_client",
            "ASC limit 1 union select 1", "ascending" })
    void retrieveAllRejectsSortOrderOtherThanAscOrDescBeforeBuildingQuery(String maliciousSortOrder) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy("displayName").sortOrder(maliciousSortOrder).build();

        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveAll(searchParameters));

        assertEquals("validation.msg.client.sortOrder.value.is.not.supported",
                exception.getErrors().get(0).getUserMessageGlobalisationCode());
        verifyNoInteractions(context, sqlGenerator, paginationHelper, jdbcTemplate, columnValidator);
    }

    @ParameterizedTest
    @CsvSource({ "displayName,DESC,c.display_name DESC", "displayName,asc,c.display_name ASC", "id,ASC,c.id ASC",
            "accountNo,desc,c.account_no DESC", "officeName,DESC,o.name DESC", "staffName,ASC,s.display_name ASC" })
    void retrieveAllSortsByAllowlistedColumn(String orderBy, String sortOrder, String expectedOrderByClause) {
        stubSecurityContextAndSqlGenerator();
        Page<ClientData> expectedPage = new Page<>(List.of(), 0);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(paginationHelper.fetchPage(eq(jdbcTemplate), sqlCaptor.capture(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(expectedPage);

        Page<ClientData> result = underTest.retrieveAll(SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).limit(50).build());

        assertSame(expectedPage, result);
        String sql = sqlCaptor.getValue();
        assertTrue(sql.endsWith(" order by " + expectedOrderByClause + " limit 50"), sql);
    }

    @Test
    void retrieveAllWithoutOrderByAddsNoOrderByClause() {
        stubSecurityContextAndSqlGenerator();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(paginationHelper.fetchPage(eq(jdbcTemplate), sqlCaptor.capture(), any(Object[].class), any(RowMapper.class)))
                .thenReturn(new Page<>(List.of(), 0));

        underTest.retrieveAll(SearchParameters.builder().limit(50).build());

        assertFalse(sqlCaptor.getValue().contains(" order by "), sqlCaptor.getValue());
    }

    @Test
    void retrieveAllForLookupValidatesExtraCriteriaBeforeAppendingIt() {
        String maliciousCriteria = "c.id = 1 or (select 1)";
        doThrow(new PlatformApiDataValidationException("error.msg.invalid.table.column", "Invalid", "column")).when(columnValidator)
                .validateSqlInjection(anyString(), eq(maliciousCriteria));

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.retrieveAllForLookup(maliciousCriteria));

        ArgumentCaptor<String> schemaCaptor = ArgumentCaptor.forClass(String.class);
        verify(columnValidator).validateSqlInjection(schemaCaptor.capture(), eq(maliciousCriteria));
        assertFalse(schemaCaptor.getValue().contains(maliciousCriteria), schemaCaptor.getValue());
        verifyNoInteractions(jdbcTemplate);
    }

    private void stubSecurityContextAndSqlGenerator() {
        AppUser appUser = mock(AppUser.class);
        when(appUser.getId()).thenReturn(1L);
        when(context.authenticatedUser()).thenReturn(appUser);
        when(context.officeHierarchy()).thenReturn(".");
        when(sqlGenerator.calcFoundRows()).thenReturn("");
        when(sqlGenerator.limit(50)).thenReturn("limit 50");
    }
}
