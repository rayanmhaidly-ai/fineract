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
package org.apache.fineract.infrastructure.core.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.SearchParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaginationParametersDataValidatorTest {

    private static final Set<String> SUPPORTED = Set.of("id", "name");

    private final PaginationParametersDataValidator underTest = new PaginationParametersDataValidator();

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "name; DROP TABLE m_group|ASC|orderBy", "(SELECT 1)|DESC|orderBy",
            "name|DESC, (SELECT SLEEP(5))|sortOrder", "name|ASC --|sortOrder" })
    void searchParametersOutsideAllowlistAreRejected(String orderBy, String sortOrder, String invalidParameter) {
        SearchParameters parameters = SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).build();

        PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validateParameterValues(parameters, SUPPORTED, "centers"));

        assertEquals(invalidParameter, exception.getErrors().get(0).getParameterName());
    }

    @ParameterizedTest
    @CsvSource({ "name,DESC", "id,asc", "id," })
    void searchParametersInsideAllowlistAreAccepted(String orderBy, String sortOrder) {
        SearchParameters parameters = SearchParameters.builder().orderBy(orderBy).sortOrder(sortOrder).build();

        assertDoesNotThrow(() -> underTest.validateParameterValues(parameters, SUPPORTED, "centers"));
    }

    @Test
    void paginationParametersOutsideAllowlistAreRejected() {
        PaginationParameters parameters = PaginationParameters.builder().orderBy("name").sortOrder("DESC; --").build();

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.validateParameterValues(parameters, SUPPORTED, "centers"));
    }
}
