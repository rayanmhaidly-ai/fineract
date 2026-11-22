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
package org.apache.fineract.portfolio.accountdetails.service;

import static org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotReadPlatformServiceImpl.ACTIVE_LOANS_SQL;
import static org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotReadPlatformServiceImpl.NEXT_INSTALLMENTS_SQL;
import static org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotReadPlatformServiceImpl.SAVINGS_SQL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.ActiveLoanRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.InstallmentDueRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.SavingsBalanceRow;
import org.apache.fineract.portfolio.client.exception.ClientNotFoundException;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class ClientFinancialSnapshotReadPlatformServiceImplTest {

    private static final Long CLIENT_ID = 42L;
    private static final Integer LOAN_ACTIVE = 300;
    private static final Integer SAVINGS_ACTIVE = 300;
    private static final Integer SAVINGS_DEPOSIT = 100;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final CurrencyData SAR = new CurrencyData("SAR", "Saudi Riyal", 2, 0, "SR", "currency.SAR");

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ClientReadPlatformService clientReadPlatformService;

    @InjectMocks
    private ClientFinancialSnapshotReadPlatformServiceImpl underTest;

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, TODAY)));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void unknownClientIsRejectedBeforeAnyQuery() {
        when(clientReadPlatformService.retrieveOne(CLIENT_ID)).thenThrow(new ClientNotFoundException(CLIENT_ID));

        assertThatThrownBy(() -> underTest.retrieveClientFinancialSnapshot(CLIENT_ID)).isInstanceOf(ClientNotFoundException.class);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void queriesAreBoundToClientStatusesAndBusinessDate() {
        when(jdbcTemplate.query(eq(SAVINGS_SQL), ArgumentMatchers.<RowMapper<SavingsBalanceRow>>any(), eq(CLIENT_ID), eq(SAVINGS_ACTIVE),
                eq(SAVINGS_DEPOSIT))).thenReturn(List.of(new SavingsBalanceRow(SAR, new BigDecimal("1200.00"))));
        when(jdbcTemplate.query(eq(ACTIVE_LOANS_SQL), ArgumentMatchers.<RowMapper<ActiveLoanRow>>any(), eq(CLIENT_ID), eq(LOAN_ACTIVE)))
                .thenReturn(List.of(new ActiveLoanRow(5L, "000000005", "Murabaha", SAR, new BigDecimal("9000.00"),
                        new BigDecimal("750.00"), TODAY.minusDays(12))));
        when(jdbcTemplate.query(eq(NEXT_INSTALLMENTS_SQL), ArgumentMatchers.<RowMapper<InstallmentDueRow>>any(), eq(CLIENT_ID),
                eq(LOAN_ACTIVE), eq(CLIENT_ID), eq(LOAN_ACTIVE), eq(TODAY)))
                .thenReturn(List.of(new InstallmentDueRow(5L, "000000005", 4, TODAY.plusDays(18), SAR, new BigDecimal("750.00"))));

        ClientFinancialSnapshotData snapshot = underTest.retrieveClientFinancialSnapshot(CLIENT_ID);

        verify(clientReadPlatformService).retrieveOne(CLIENT_ID);
        assertThat(snapshot.getClientId()).isEqualTo(CLIENT_ID);
        assertThat(snapshot.getAsOfDate()).isEqualTo(TODAY);
        assertThat(snapshot.getSavingsTotals()).singleElement()
                .satisfies(t -> assertThat(t.getTotalBalance()).isEqualByComparingTo("1200.00"));
        assertThat(snapshot.getLoanTotals()).singleElement().satisfies(t -> {
            assertThat(t.getTotalOutstanding()).isEqualByComparingTo("9000.00");
            assertThat(t.getTotalOverdue()).isEqualByComparingTo("750.00");
        });
        assertThat(snapshot.getHasOverdue()).isTrue();
        assertThat(snapshot.getOverdueSinceDate()).isEqualTo(TODAY.minusDays(12));
        assertThat(snapshot.getNextInstallment().getDueDate()).isEqualTo(TODAY.plusDays(18));
        assertThat(snapshot.getNextInstallment().getTotals()).singleElement()
                .satisfies(t -> assertThat(t.getAmount()).isEqualByComparingTo("750.00"));
    }

    @Test
    void installmentQueryIsSkippedWhenClientHasNoActiveLoans() {
        when(jdbcTemplate.query(eq(SAVINGS_SQL), ArgumentMatchers.<RowMapper<SavingsBalanceRow>>any(), eq(CLIENT_ID), eq(SAVINGS_ACTIVE),
                eq(SAVINGS_DEPOSIT))).thenReturn(List.of());
        when(jdbcTemplate.query(eq(ACTIVE_LOANS_SQL), ArgumentMatchers.<RowMapper<ActiveLoanRow>>any(), eq(CLIENT_ID), eq(LOAN_ACTIVE)))
                .thenReturn(List.of());

        ClientFinancialSnapshotData snapshot = underTest.retrieveClientFinancialSnapshot(CLIENT_ID);

        verify(jdbcTemplate, never()).query(eq(NEXT_INSTALLMENTS_SQL), ArgumentMatchers.<RowMapper<InstallmentDueRow>>any(),
                any(Object[].class));
        assertThat(snapshot.getActiveLoans()).isEmpty();
        assertThat(snapshot.getHasOverdue()).isFalse();
        assertThat(snapshot.getNextInstallment()).isNull();
    }

    @Test
    void sqlUsesBindPlaceholdersOnly() {
        assertThat(SAVINGS_SQL).doesNotContain(CLIENT_ID.toString());
        assertThat(placeholders(SAVINGS_SQL)).isEqualTo(3);
        assertThat(placeholders(ACTIVE_LOANS_SQL)).isEqualTo(2);
        assertThat(placeholders(NEXT_INSTALLMENTS_SQL)).isEqualTo(5);
    }

    @Test
    void amountDueOfPartlyPaidInstallmentIsWhatRemains() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBigDecimal(anyString())).thenReturn(null);
        when(rs.getBigDecimal("principal")).thenReturn(new BigDecimal("100.00"));
        when(rs.getBigDecimal("principalPaid")).thenReturn(new BigDecimal("40.00"));
        when(rs.getBigDecimal("interest")).thenReturn(new BigDecimal("10.00"));
        when(rs.getBigDecimal("interestPaid")).thenReturn(new BigDecimal("3.00"));
        when(rs.getBigDecimal("interestWaived")).thenReturn(new BigDecimal("2.00"));
        when(rs.getBigDecimal("fee")).thenReturn(new BigDecimal("5.00"));
        when(rs.getBigDecimal("feeWrittenOff")).thenReturn(new BigDecimal("5.00"));
        when(rs.getBigDecimal("penalty")).thenReturn(new BigDecimal("1.50"));

        assertThat(ClientFinancialSnapshotReadPlatformServiceImpl.amountDue(rs)).isEqualByComparingTo("66.50");
    }

    @Test
    void amountDueOfUnpaidInstallmentIsTheFullScheduledAmount() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBigDecimal(anyString())).thenReturn(null);
        when(rs.getBigDecimal("principal")).thenReturn(new BigDecimal("400.00"));
        when(rs.getBigDecimal("interest")).thenReturn(new BigDecimal("50.00"));

        assertThat(ClientFinancialSnapshotReadPlatformServiceImpl.amountDue(rs)).isEqualByComparingTo("450.00");
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }
}
