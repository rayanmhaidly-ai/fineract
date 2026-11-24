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
package org.apache.fineract.portfolio.search.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.serialization.FromJsonHelper;
import org.apache.fineract.infrastructure.security.service.SqlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdHocQueryDataValidatorTest {

    // Passes every pattern of the default SqlValidator configuration
    private static final String SUBQUERY_INJECTION = "> 0 OR (SELECT COUNT(*) FROM m_appuser) > 0 OR a.percentOut >";

    // No-op SqlValidator mock: the condition allowlist alone must reject injections
    private final AdHocQueryDataValidator underTest = new AdHocQueryDataValidator(mock(SqlValidator.class), new FromJsonHelper());

    @ParameterizedTest
    @ValueSource(strings = { SUBQUERY_INJECTION, "> (SELECT COUNT(*) FROM m_appuser)", "LIKE", ">= ", "BETWEEN", "" })
    void rejectsUnsupportedOutStandingAmountPercentageCondition(final String condition) {
        final String json = percentageRequest(condition).toString();

        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveSearchConditions(json));

        assertEquals(AdHocQuerySearchConstants.outStandingAmountPercentageConditionParamName,
                exception.getErrors().get(0).getParameterName());
    }

    @ParameterizedTest
    @ValueSource(strings = { SUBQUERY_INJECTION, "> (SELECT COUNT(*) FROM m_appuser)", "LIKE", ">= ", "BETWEEN", "" })
    void rejectsUnsupportedOutstandingAmountCondition(final String condition) {
        final String json = outstandingAmountRequest(condition).toString();

        final PlatformApiDataValidationException exception = assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.retrieveSearchConditions(json));

        assertEquals(AdHocQuerySearchConstants.outstandingAmountConditionParamName, exception.getErrors().get(0).getParameterName());
    }

    @Test
    void rejectsNullCondition() {
        final JsonObject request = baseRequest();
        request.addProperty(AdHocQuerySearchConstants.includeOutStandingAmountPercentageParamName, true);
        request.add(AdHocQuerySearchConstants.outStandingAmountPercentageConditionParamName, com.google.gson.JsonNull.INSTANCE);
        request.addProperty(AdHocQuerySearchConstants.outStandingAmountPercentageParamName, 10);

        assertThrows(PlatformApiDataValidationException.class, () -> underTest.retrieveSearchConditions(request.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "=", "<", "<=", ">", ">=" })
    void acceptsSupportedComparisonConditions(final String condition) {
        final JsonObject request = percentageRequest(condition);
        request.addProperty(AdHocQuerySearchConstants.includeOutstandingAmountParamName, true);
        request.addProperty(AdHocQuerySearchConstants.outstandingAmountConditionParamName, condition);
        request.addProperty(AdHocQuerySearchConstants.outstandingAmountParamName, 500);

        final AdHocQuerySearchConditions conditions = underTest.retrieveSearchConditions(request.toString());

        assertEquals(condition, conditions.getOutStandingAmountPercentageCondition());
        assertEquals(new BigDecimal("10"), conditions.getOutStandingAmountPercentage());
        assertEquals(condition, conditions.getOutstandingAmountCondition());
        assertEquals(new BigDecimal("500"), conditions.getOutstandingAmount());
    }

    @Test
    void acceptsBetweenCondition() {
        final JsonObject request = baseRequest();
        request.addProperty(AdHocQuerySearchConstants.includeOutStandingAmountPercentageParamName, true);
        request.addProperty(AdHocQuerySearchConstants.outStandingAmountPercentageConditionParamName, "between");
        request.addProperty(AdHocQuerySearchConstants.minOutStandingAmountPercentageParamName, 10);
        request.addProperty(AdHocQuerySearchConstants.maxOutStandingAmountPercentageParamName, 60);
        request.addProperty(AdHocQuerySearchConstants.includeOutstandingAmountParamName, true);
        request.addProperty(AdHocQuerySearchConstants.outstandingAmountConditionParamName, "between");
        request.addProperty(AdHocQuerySearchConstants.minOutstandingAmountParamName, 100);
        request.addProperty(AdHocQuerySearchConstants.maxOutstandingAmountParamName, 5000);

        final AdHocQuerySearchConditions conditions = underTest.retrieveSearchConditions(request.toString());

        assertEquals("between", conditions.getOutStandingAmountPercentageCondition());
        assertEquals(new BigDecimal("10"), conditions.getMinOutStandingAmountPercentage());
        assertEquals(new BigDecimal("60"), conditions.getMaxOutStandingAmountPercentage());
        assertEquals("between", conditions.getOutstandingAmountCondition());
        assertEquals(new BigDecimal("100"), conditions.getMinOutstandingAmount());
        assertEquals(new BigDecimal("5000"), conditions.getMaxOutstandingAmount());
    }

    private static JsonObject baseRequest() {
        final JsonObject request = new JsonObject();
        final JsonArray entities = new JsonArray();
        entities.add("loan");
        request.add(AdHocQuerySearchConstants.entitiesParamName, entities);
        request.addProperty(AdHocQuerySearchConstants.localeParamName, "en");
        return request;
    }

    private static JsonObject percentageRequest(final String condition) {
        final JsonObject request = baseRequest();
        request.addProperty(AdHocQuerySearchConstants.includeOutStandingAmountPercentageParamName, true);
        request.addProperty(AdHocQuerySearchConstants.outStandingAmountPercentageConditionParamName, condition);
        request.addProperty(AdHocQuerySearchConstants.outStandingAmountPercentageParamName, 10);
        return request;
    }

    private static JsonObject outstandingAmountRequest(final String condition) {
        final JsonObject request = baseRequest();
        request.addProperty(AdHocQuerySearchConstants.includeOutstandingAmountParamName, true);
        request.addProperty(AdHocQuerySearchConstants.outstandingAmountConditionParamName, condition);
        request.addProperty(AdHocQuerySearchConstants.outstandingAmountParamName, 500);
        return request;
    }
}
