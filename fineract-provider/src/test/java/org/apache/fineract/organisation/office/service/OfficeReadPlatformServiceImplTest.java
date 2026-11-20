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
package org.apache.fineract.organisation.office.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.apache.fineract.infrastructure.core.data.PaginationParametersDataValidator;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.service.CurrencyReadPlatformService;
import org.apache.fineract.organisation.office.data.OfficeData;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepository;
import org.apache.fineract.organisation.office.mapper.OfficeDataMapper;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class OfficeReadPlatformServiceImplTest {

    private static final String SUBQUERY_ORDER_BY = "(case (select count(*) from m_appuser where username like'adm%') when 0 then o.id else o.name end)";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DatabaseSpecificSQLGenerator sqlGenerator;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private CurrencyReadPlatformService currencyReadPlatformService;
    @Mock
    private OfficeRepository officeRepository;
    @Mock
    private OfficeDataMapper officeDataMapper;

    private OfficeReadPlatformServiceImpl service;

    @BeforeEach
    void setUp() {
        Office office = mock(Office.class);
        lenient().when(office.getHierarchy()).thenReturn(".");
        AppUser user = mock(AppUser.class);
        lenient().when(user.getOffice()).thenReturn(office);
        lenient().when(context.authenticatedUser()).thenReturn(user);
        service = new OfficeReadPlatformServiceImpl(jdbcTemplate, sqlGenerator, context, currencyReadPlatformService,
                new PaginationParametersDataValidator(), officeRepository, officeDataMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = { SUBQUERY_ORDER_BY, "(select max(password) from m_appuser)", "o.id; drop table m_office", "o.name, o.id",
            "unknown_column", "NAME" })
    void retrieveAllOfficesRejectsOrderByNotInAllowlist(String orderBy) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy(orderBy).sortOrder("asc").build();

        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> service.retrieveAllOffices(false, searchParameters));

        assertEquals("validation.msg.offices.orderBy.value.is.not.supported",
                exception.getErrors().get(0).getUserMessageGlobalisationCode());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = { "asc, (select max(password) from m_appuser)", "desc; drop table m_office", "random" })
    void retrieveAllOfficesRejectsSortOrderNotInAllowlist(String sortOrder) {
        SearchParameters searchParameters = SearchParameters.builder().orderBy("name").sortOrder(sortOrder).build();

        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> service.retrieveAllOffices(false, searchParameters));

        assertEquals("validation.msg.offices.sortOrder.value.is.not.supported",
                exception.getErrors().get(0).getUserMessageGlobalisationCode());
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void retrieveAllOfficesRejectsMaliciousSortOrderEvenWithoutOrderBy() {
        SearchParameters searchParameters = SearchParameters.builder().sortOrder("asc, (select max(password) from m_appuser)").build();

        assertThrows(PlatformApiDataValidationException.class, () -> service.retrieveAllOffices(false, searchParameters));
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void retrieveAllOfficesSortsByAllowlistedColumn() {
        List<OfficeData> expected = List.of(mock(OfficeData.class));
        doReturn(expected).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        SearchParameters searchParameters = SearchParameters.builder().orderBy("name").sortOrder("desc").build();

        assertEquals(expected, service.retrieveAllOffices(true, searchParameters));

        assertTrue(capturedSql().endsWith(" where o.hierarchy like ? order by o.name DESC"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "id:o.id", "externalId:o.external_id", "openingDate:o.opening_date", "hierarchy:o.hierarchy",
            "parentId:parent.id", "parentName:parent.name", "o.name:o.name", "o.opening_date:o.opening_date" })
    void retrieveAllOfficesMapsOrderByToFixedColumn(String mapping) {
        String[] parts = mapping.split(":");
        SearchParameters searchParameters = SearchParameters.builder().orderBy(parts[0]).sortOrder("ASC").build();

        service.retrieveAllOffices(false, searchParameters);

        assertTrue(capturedSql().endsWith(" order by " + parts[1] + " ASC"));
    }

    @Test
    void retrieveAllOfficesDefaultsToHierarchyOrder() {
        service.retrieveAllOffices(false, SearchParameters.builder().build());

        assertTrue(capturedSql().endsWith(" order by o.hierarchy"));
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), eq(".%"));
        return sql.getValue();
    }
}
