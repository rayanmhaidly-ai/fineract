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

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.core.service.database.DatabaseType;
import org.apache.fineract.infrastructure.core.service.database.DatabaseTypeResolver;
import org.apache.fineract.infrastructure.core.service.database.RoutingDataSource;
import org.apache.fineract.infrastructure.dataqueries.data.EntityTables;
import org.apache.fineract.infrastructure.dataqueries.data.ResultsetColumnHeaderData;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.apache.fineract.portfolio.search.service.SearchUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class DatatableUtilTest {

    private static final String DATATABLE = "dt_client_extra";
    private static final String BASE_SQL = "select * from \"dt_client_extra\" where client_id = 1";

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private SqlValidator sqlValidator;
    @Mock
    private PlatformSecurityContext context;
    @Mock
    private GenericDataService genericDataService;
    @Mock
    private DatabaseTypeResolver databaseTypeResolver;
    @Mock
    private RoutingDataSource routingDataSource;

    private List<ResultsetColumnHeaderData> columnHeaders;
    private DatatableUtil underTest;

    @BeforeEach
    void setUp() {
        lenient().when(databaseTypeResolver.isPostgreSQL()).thenReturn(true);
        DatabaseSpecificSQLGenerator sqlGenerator = new DatabaseSpecificSQLGenerator(databaseTypeResolver, routingDataSource);
        underTest = new DatatableUtil(new SearchUtil(sqlValidator), jdbcTemplate, sqlValidator, context, genericDataService, sqlGenerator);
        columnHeaders = List.of(column("client_id"), column("date_of_birth"), column("name"));
        when(genericDataService.fillResultsetColumnHeaders(DATATABLE)).thenReturn(columnHeaders);
    }

    @ParameterizedTest
    @ValueSource(strings = { "name; DROP TABLE m_client", "name desc; DELETE FROM m_client", "(SELECT 1)",
            "(CASE WHEN (SELECT 1 FROM m_appuser WHERE id = 1) = 1 THEN name ELSE client_id END)", "name desc, (SELECT pg_sleep(5))",
            "name descending", "name desc limit 1", "1", "m_client.id", "unknown_column", "`name` desc --", "\"name\"\" desc", "name,",
            "name,,client_id" })
    void rejectsOrderOutsideColumnAllowlist(String order) {
        assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveDataTableGenericResultSet(EntityTables.CLIENT, DATATABLE, 1L, order, null));

        verify(genericDataService, never()).fillResultsetRowData(anyString(), anyList());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "name|ORDER BY \"name\" ASC", "name desc|ORDER BY \"name\" DESC",
            "NAME DESC|ORDER BY \"name\" DESC", "`Date of Birth` desc|ORDER BY \"date_of_birth\" DESC",
            "\"date_of_birth\" asc|ORDER BY \"date_of_birth\" ASC", "Date of Birth|ORDER BY \"date_of_birth\" ASC",
            "dateOfBirth desc, name|ORDER BY \"date_of_birth\" DESC, \"name\" ASC" })
    void sortsByDatatableColumn(String order, String expectedOrderBy) {
        underTest.retrieveDataTableGenericResultSet(EntityTables.CLIENT, DATATABLE, 1L, order, null);

        verify(genericDataService).fillResultsetRowData(BASE_SQL + " " + expectedOrderBy, columnHeaders);
    }

    @Test
    void noOrderAddsNoOrderClause() {
        underTest.retrieveDataTableGenericResultSet(EntityTables.CLIENT, DATATABLE, 1L, null, null);

        verify(genericDataService).fillResultsetRowData(BASE_SQL, columnHeaders);
    }

    @Test
    void escapesColumnForMySql() {
        when(databaseTypeResolver.isMySQL()).thenReturn(true);

        underTest.retrieveDataTableGenericResultSet(EntityTables.CLIENT, DATATABLE, 1L, "`Date of Birth` desc", null);

        verify(genericDataService).fillResultsetRowData("select * from `dt_client_extra` where client_id = 1 ORDER BY `date_of_birth` DESC",
                columnHeaders);
    }

    private static ResultsetColumnHeaderData column(String name) {
        ResultsetColumnHeaderData header = ResultsetColumnHeaderData.detailed(name, "varchar", 50L, true, false, emptyList(), null, false,
                false, DatabaseType.POSTGRESQL);
        assertEquals(name, header.getColumnName());
        return header;
    }
}
