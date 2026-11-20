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
package org.apache.fineract.portfolio.group.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import org.apache.fineract.infrastructure.codes.service.CodeValueReadPlatformService;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.data.PaginationParameters;
import org.apache.fineract.infrastructure.core.data.PaginationParametersDataValidator;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.PaginationHelper;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.service.OfficeReadPlatformService;
import org.apache.fineract.organisation.staff.service.StaffReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CenterReadPlatformServiceImplTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private ClientReadPlatformService clientReadPlatformService;
    @Mock
    private OfficeReadPlatformService officeReadPlatformService;
    @Mock
    private StaffReadPlatformService staffReadPlatformService;
    @Mock
    private CodeValueReadPlatformService codeValueReadPlatformService;
    @Mock
    private ConfigurationDomainService configurationDomainService;
    @Mock
    private PaginationHelper paginationHelper;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;

    private CenterReadPlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        underTest = new CenterReadPlatformServiceImpl(jdbcTemplate, context, clientReadPlatformService, officeReadPlatformService,
                staffReadPlatformService, codeValueReadPlatformService, configurationDomainService, paginationHelper, sqlGenerator,
                new PaginationParametersDataValidator());
    }

    static Stream<Arguments> maliciousOrderings() {
        return Stream.of(Arguments.of("name; DROP TABLE m_group; --", "ASC"),
                Arguments.of("(CASE WHEN (SELECT 1 FROM m_appuser WHERE id = 1) = 1 THEN g.id ELSE g.display_name END)", "ASC"),
                Arguments.of("g.display_name", "ASC"), Arguments.of("NAME", "ASC"), Arguments.of("name", "DESC, (SELECT SLEEP(5))"),
                Arguments.of("name", "ASC; DELETE FROM m_group"), Arguments.of("name", "ascending"));
    }

    @ParameterizedTest
    @MethodSource("maliciousOrderings")
    void retrievePagedAllRejectsOrderingOutsideAllowlist(String orderBy, String sortOrder) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).build();
        PaginationParameters parameters = PaginationParameters.builder().paged(true).orderBy(orderBy).sortOrder(sortOrder).build();

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.retrievePagedAll(searchParameters, parameters));

        verifyNoInteractions(jdbcTemplate, paginationHelper);
    }

    @ParameterizedTest
    @MethodSource("maliciousOrderings")
    void retrieveAllRejectsOrderingOutsideAllowlistWithoutPaginationParameters(String orderBy, String sortOrder) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).build();

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.retrieveAll(searchParameters, null));

        verifyNoInteractions(jdbcTemplate, paginationHelper);
    }

    @ParameterizedTest
    @CsvSource({ "id,desc,' order by g.id DESC'", "name,DESC,' order by g.display_name DESC'", "officeId,asc,' order by g.office_id ASC'",
            "officeName,Asc,' order by o.name ASC'" })
    @SuppressWarnings("unchecked")
    void retrievePagedAllSortsByAllowlistedColumn(String orderBy, String sortOrder, String expectedOrderBy) {
        mockAuthenticatedUser();
        SearchParameters searchParameters = SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).build();
        PaginationParameters parameters = PaginationParameters.builder().paged(true).orderBy(orderBy).sortOrder(sortOrder).build();

        underTest.retrievePagedAll(searchParameters, parameters);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(paginationHelper).fetchPage(eq(jdbcTemplate), sql.capture(), any(Object[].class), any(RowMapper.class));
        assertTrue(sql.getValue().endsWith(expectedOrderBy), sql.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveAllSortsByAllowlistedColumnWithoutSortOrder() {
        mockAuthenticatedUser();
        SearchParameters searchParameters = SearchParameters.builder().orderBy("name").build();

        underTest.retrieveAll(searchParameters, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertTrue(sql.getValue().endsWith(" order by g.display_name"), sql.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrieveAllWithoutOrderByAddsNoOrderClause() {
        mockAuthenticatedUser();

        underTest.retrieveAll(SearchParameters.builder().build(), null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertFalse(sql.getValue().contains("order by"), sql.getValue());
    }

    private void mockAuthenticatedUser() {
        AppUser user = mock(AppUser.class);
        Office office = mock(Office.class);
        when(office.getHierarchy()).thenReturn(".");
        when(user.getOffice()).thenReturn(office);
        when(context.authenticatedUser()).thenReturn(user);
    }
}
