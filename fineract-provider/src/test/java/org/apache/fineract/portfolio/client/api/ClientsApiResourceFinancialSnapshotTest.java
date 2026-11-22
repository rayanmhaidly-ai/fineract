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
package org.apache.fineract.portfolio.client.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.infrastructure.core.api.ApiRequestParameterHelper;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.serialization.CommandProcessingResultJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.DefaultToApiJsonSerializer;
import org.apache.fineract.infrastructure.core.serialization.ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson;
import org.apache.fineract.infrastructure.core.serialization.GoogleGsonSerializerHelper;
import org.apache.fineract.infrastructure.security.exception.NoAuthorizationException;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.ActiveLoan;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.CurrencyAmount;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.InstallmentDue;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.LoanTotal;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.NextInstallment;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.SavingsTotal;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotReadPlatformService;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;

class ClientsApiResourceFinancialSnapshotTest {

    private static final Long CLIENT_ID = 7L;
    private static final CurrencyData SAR = new CurrencyData("SAR", "Saudi Riyal", 2, 0, "SR", "currency.SAR");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final LocalDate OVERDUE_SINCE = LocalDate.of(2026, 10, 1);

    private static final String EXTERNAL_ID = "ext-7";

    private final ClientFinancialSnapshotReadPlatformService snapshotService = mock(ClientFinancialSnapshotReadPlatformService.class);
    private final ClientReadPlatformService clientReadPlatformService = mock(ClientReadPlatformService.class);
    private final AppUser user = mock(AppUser.class);
    private final DefaultToApiJsonSerializer<ClientFinancialSnapshotData> serializer = new DefaultToApiJsonSerializer<>(
            new ExcludeNothingWithPrettyPrintingOffJsonSerializerGoogleGson(), new CommandProcessingResultJsonSerializer(),
            new GoogleGsonSerializerHelper());
    private ClientsApiResource resource;
    private JsonObject fullSnapshot;

    @BeforeEach
    void setUp() {
        PlatformSecurityContext context = mock(PlatformSecurityContext.class);
        when(context.authenticatedUser()).thenReturn(user);
        when(clientReadPlatformService.retrieveClientIdByExternalId(new ExternalId(EXTERNAL_ID))).thenReturn(CLIENT_ID);
        resource = new ClientsApiResource(context, clientReadPlatformService, null, null, null, new ApiRequestParameterHelper(), null, null,
                null, null, null, null, null, snapshotService, serializer);

        ClientFinancialSnapshotData snapshot = new ClientFinancialSnapshotData(CLIENT_ID, TODAY,
                List.of(new SavingsTotal(SAR, 1, new BigDecimal("1000.00"))),
                List.of(new LoanTotal(SAR, 1, new BigDecimal("5000.00"), new BigDecimal("200.00"))),
                List.of(new ActiveLoan(11L, "000000011", "Personal", SAR, new BigDecimal("5000.00"), true, new BigDecimal("200.00"),
                        OVERDUE_SINCE)),
                true, OVERDUE_SINCE,
                new NextInstallment(LocalDate.of(2026, 12, 1), List.of(new CurrencyAmount(SAR, new BigDecimal("450.00"))),
                        List.of(new InstallmentDue(11L, "000000011", 3, SAR, new BigDecimal("450.00")))));
        when(snapshotService.retrieveClientFinancialSnapshot(CLIENT_ID)).thenReturn(snapshot);
        fullSnapshot = JsonParser.parseString(serializer.serialize(snapshot)).getAsJsonObject();
    }

    @Test
    void fieldsNextInstallmentKeepsNestedDueDateAndAmounts() throws Exception {
        assertFieldsReturnFullSections("nextInstallment");
    }

    @Test
    void fieldsActiveLoansKeepsNestedLoanFieldsIncludingOverdueSinceDate() throws Exception {
        assertFieldsReturnFullSections("activeLoans");
    }

    @Test
    void fieldsSavingsAndLoanTotalsKeepNestedFields() throws Exception {
        assertFieldsReturnFullSections("savingsTotals", "loanTotals");
    }

    @Test
    void withoutFieldsTheFullSnapshotIsReturned() throws Exception {
        JSONAssert.assertEquals(fullSnapshot.toString(), resource.retrieveFinancialSnapshot(CLIENT_ID, uriInfo(null)), true);
    }

    @Test
    void userWithClientLoanAndSavingsReadPermissionGetsSnapshotByExternalId() throws Exception {
        JSONAssert.assertEquals(fullSnapshot.toString(), resource.retrieveFinancialSnapshotByExternalId(EXTERNAL_ID, uriInfo(null)), true);

        verify(user).validateHasReadPermission("client");
        verify(user).validateHasReadPermission("LOAN");
        verify(user).validateHasReadPermission("savingsaccount");
    }

    @ParameterizedTest
    @ValueSource(strings = { "client", "LOAN", "savingsaccount" })
    void missingReadPermissionRejectsSnapshotById(String missingPermission) {
        doThrow(new NoAuthorizationException("no " + missingPermission)).when(user).validateHasReadPermission(missingPermission);

        assertThatThrownBy(() -> resource.retrieveFinancialSnapshot(CLIENT_ID, uriInfo(null))).isInstanceOf(NoAuthorizationException.class);
        verify(snapshotService, never()).retrieveClientFinancialSnapshot(any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "client", "LOAN", "savingsaccount" })
    void missingReadPermissionRejectsSnapshotByExternalIdBeforeLookingUpTheClient(String missingPermission) {
        doThrow(new NoAuthorizationException("no " + missingPermission)).when(user).validateHasReadPermission(missingPermission);

        assertThatThrownBy(() -> resource.retrieveFinancialSnapshotByExternalId(EXTERNAL_ID, uriInfo(null)))
                .isInstanceOf(NoAuthorizationException.class);
        verifyNoInteractions(clientReadPlatformService, snapshotService);
    }

    private void assertFieldsReturnFullSections(String... fields) throws Exception {
        JsonObject expected = new JsonObject();
        for (String field : fields) {
            expected.add(field, fullSnapshot.get(field));
        }
        String actual = resource.retrieveFinancialSnapshot(CLIENT_ID, uriInfo(String.join(",", fields)));
        JSONAssert.assertEquals(expected.toString(), actual, true);
    }

    private static UriInfo uriInfo(String fields) {
        MultivaluedMap<String, String> queryParameters = new MultivaluedHashMap<>();
        if (fields != null) {
            queryParameters.add("fields", fields);
        }
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(queryParameters);
        return uriInfo;
    }
}
