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

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.ActiveLoan;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.InstallmentDue;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.LoanTotal;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.NextInstallment;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.SavingsTotal;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.ActiveLoanRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.InstallmentDueRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.SavingsBalanceRow;
import org.junit.jupiter.api.Test;

class ClientFinancialSnapshotAssemblerTest {

    private static final Long CLIENT_ID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);
    private static final CurrencyData SAR = new CurrencyData("SAR", "Saudi Riyal", 2, 0, "SR", "currency.SAR");
    private static final CurrencyData USD = new CurrencyData("USD", "US Dollar", 2, 0, "$", "currency.USD");

    @Test
    void clientWithoutAccountsGetsEmptySnapshot() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(), List.of());

        assertThat(snapshot.getClientId()).isEqualTo(CLIENT_ID);
        assertThat(snapshot.getAsOfDate()).isEqualTo(TODAY);
        assertThat(snapshot.getSavingsTotals()).isEmpty();
        assertThat(snapshot.getLoanTotals()).isEmpty();
        assertThat(snapshot.getActiveLoans()).isEmpty();
        assertThat(snapshot.getHasOverdue()).isFalse();
        assertThat(snapshot.getOverdueSinceDate()).isNull();
        assertThat(snapshot.getNextInstallment()).isNull();
    }

    @Test
    void savingsBalancesAreTotalledPerCurrencyAndSortedByCurrencyCode() {
        ClientFinancialSnapshotData snapshot = assemble(
                List.of(savings(USD, "100.50"), savings(SAR, "1000.00"), savings(USD, "49.50"), savings(SAR, "250.25")), List.of(),
                List.of());

        assertThat(snapshot.getSavingsTotals()).hasSize(2);
        SavingsTotal sar = snapshot.getSavingsTotals().get(0);
        assertThat(sar.getCurrency()).isEqualTo(SAR);
        assertThat(sar.getActiveAccountCount()).isEqualTo(2);
        assertThat(sar.getTotalBalance()).isEqualByComparingTo("1250.25");
        SavingsTotal usd = snapshot.getSavingsTotals().get(1);
        assertThat(usd.getCurrency()).isEqualTo(USD);
        assertThat(usd.getActiveAccountCount()).isEqualTo(2);
        assertThat(usd.getTotalBalance()).isEqualByComparingTo("150.00");
    }

    @Test
    void nullAndOverdrawnSavingsBalancesAreIncludedInTotal() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(savings(SAR, null), savings(SAR, "-30.00"), savings(SAR, "100.00")),
                List.of(), List.of());

        SavingsTotal sar = snapshot.getSavingsTotals().get(0);
        assertThat(sar.getActiveAccountCount()).isEqualTo(3);
        assertThat(sar.getTotalBalance()).isEqualByComparingTo("70.00");
    }

    @Test
    void loansWithoutOverdueAmountAreNotInArrears() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(),
                List.of(loan(1L, SAR, "5000.00", null, null), loan(2L, SAR, "2000.00", "0", LocalDate.of(2026, 10, 1))), List.of());

        assertThat(snapshot.getHasOverdue()).isFalse();
        assertThat(snapshot.getOverdueSinceDate()).isNull();
        assertThat(snapshot.getActiveLoans()).extracting(ActiveLoan::getInArrears).containsExactly(false, false);
        // a stale overdue-since date without an overdue amount is not reported
        assertThat(snapshot.getActiveLoans().get(1).getOverdueSinceDate()).isNull();
        assertThat(snapshot.getActiveLoans().get(0).getTotalOverdue()).isEqualByComparingTo("0");

        LoanTotal sar = snapshot.getLoanTotals().get(0);
        assertThat(sar.getActiveLoanCount()).isEqualTo(2);
        assertThat(sar.getTotalOutstanding()).isEqualByComparingTo("7000.00");
        assertThat(sar.getTotalOverdue()).isEqualByComparingTo("0");
    }

    @Test
    void overdueLoansAreFlaggedWithEarliestOverdueDate() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(loan(1L, SAR, "5000.00", "300.00", LocalDate.of(2026, 11, 1)),
                loan(2L, SAR, "2000.00", null, null), loan(3L, USD, "800.00", "50.00", LocalDate.of(2026, 9, 15))), List.of());

        assertThat(snapshot.getHasOverdue()).isTrue();
        assertThat(snapshot.getOverdueSinceDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(snapshot.getActiveLoans()).extracting(ActiveLoan::getId).containsExactly(1L, 2L, 3L);
        assertThat(snapshot.getActiveLoans()).extracting(ActiveLoan::getInArrears).containsExactly(true, false, true);
        assertThat(snapshot.getActiveLoans().get(0).getOverdueSinceDate()).isEqualTo(LocalDate.of(2026, 11, 1));

        assertThat(snapshot.getLoanTotals()).hasSize(2);
        LoanTotal sar = snapshot.getLoanTotals().get(0);
        assertThat(sar.getCurrency()).isEqualTo(SAR);
        assertThat(sar.getActiveLoanCount()).isEqualTo(2);
        assertThat(sar.getTotalOutstanding()).isEqualByComparingTo("7000.00");
        assertThat(sar.getTotalOverdue()).isEqualByComparingTo("300.00");
        LoanTotal usd = snapshot.getLoanTotals().get(1);
        assertThat(usd.getActiveLoanCount()).isEqualTo(1);
        assertThat(usd.getTotalOutstanding()).isEqualByComparingTo("800.00");
        assertThat(usd.getTotalOverdue()).isEqualByComparingTo("50.00");
    }

    @Test
    void nextInstallmentIsTheEarliestUpcomingOne() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(loan(1L, SAR, "5000.00", null, null)),
                List.of(installment(1L, 4, TODAY.plusDays(30), SAR, "450.00"), installment(1L, 3, TODAY.plusDays(5), SAR, "450.00")));

        NextInstallment next = snapshot.getNextInstallment();
        assertThat(next.getDueDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(next.getInstallments()).singleElement().satisfies(i -> {
            assertThat(i.getLoanId()).isEqualTo(1L);
            assertThat(i.getAccountNo()).isEqualTo("000000001");
            assertThat(i.getInstallmentNumber()).isEqualTo(3);
            assertThat(i.getAmountDue()).isEqualByComparingTo("450.00");
        });
        assertThat(next.getTotals()).singleElement().satisfies(t -> {
            assertThat(t.getCurrency()).isEqualTo(SAR);
            assertThat(t.getAmount()).isEqualByComparingTo("450.00");
        });
    }

    @Test
    void installmentDueTodayIsTheNextInstallment() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(loan(1L, SAR, "5000.00", null, null)),
                List.of(installment(1L, 2, TODAY, SAR, "450.00"), installment(1L, 3, TODAY.plusMonths(1), SAR, "450.00")));

        assertThat(snapshot.getNextInstallment().getDueDate()).isEqualTo(TODAY);
    }

    @Test
    void installmentsDueOnSameDateAreSummedPerCurrency() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(),
                List.of(loan(1L, SAR, "5000.00", null, null), loan(2L, SAR, "2000.00", null, null), loan(3L, USD, "800.00", null, null)),
                List.of(installment(3L, 1, TODAY.plusDays(3), USD, "80.00"), installment(2L, 6, TODAY.plusDays(3), SAR, "200.00"),
                        installment(1L, 2, TODAY.plusDays(3), SAR, "450.00")));

        NextInstallment next = snapshot.getNextInstallment();
        assertThat(next.getDueDate()).isEqualTo(TODAY.plusDays(3));
        assertThat(next.getInstallments()).extracting(InstallmentDue::getLoanId).containsExactly(1L, 2L, 3L);
        assertThat(next.getTotals()).hasSize(2);
        assertThat(next.getTotals().get(0).getCurrency()).isEqualTo(SAR);
        assertThat(next.getTotals().get(0).getAmount()).isEqualByComparingTo("650.00");
        assertThat(next.getTotals().get(1).getCurrency()).isEqualTo(USD);
        assertThat(next.getTotals().get(1).getAmount()).isEqualByComparingTo("80.00");
    }

    @Test
    void pastDueAndFullySettledInstallmentsAreNotTheNextInstallment() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(loan(1L, SAR, "5000.00", "450.00", TODAY.minusDays(10))),
                List.of(installment(1L, 1, TODAY.minusDays(10), SAR, "450.00"), installment(1L, 2, TODAY.plusDays(1), SAR, "0.00"),
                        installment(1L, 3, TODAY.plusDays(2), SAR, null), installment(1L, 4, TODAY.plusDays(20), SAR, "450.00")));

        assertThat(snapshot.getNextInstallment().getDueDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(snapshot.getNextInstallment().getInstallments()).extracting(InstallmentDue::getInstallmentNumber).containsExactly(4);
    }

    @Test
    void noNextInstallmentWhenNothingIsUpcoming() {
        ClientFinancialSnapshotData snapshot = assemble(List.of(), List.of(loan(1L, SAR, "450.00", "450.00", TODAY.minusDays(10))),
                List.of(installment(1L, 1, TODAY.minusDays(10), SAR, "450.00")));

        assertThat(snapshot.getHasOverdue()).isTrue();
        assertThat(snapshot.getNextInstallment()).isNull();
    }

    private static ClientFinancialSnapshotData assemble(List<SavingsBalanceRow> savings, List<ActiveLoanRow> loans,
            List<InstallmentDueRow> installments) {
        return ClientFinancialSnapshotAssembler.assemble(CLIENT_ID, TODAY, savings, loans, installments);
    }

    private static SavingsBalanceRow savings(CurrencyData currency, String balance) {
        return new SavingsBalanceRow(currency, amount(balance));
    }

    private static ActiveLoanRow loan(Long id, CurrencyData currency, String outstanding, String overdue, LocalDate overdueSince) {
        return new ActiveLoanRow(id, accountNo(id), "Murabaha", currency, amount(outstanding), amount(overdue), overdueSince);
    }

    private static InstallmentDueRow installment(Long loanId, Integer number, LocalDate dueDate, CurrencyData currency, String due) {
        return new InstallmentDueRow(loanId, accountNo(loanId), number, dueDate, currency, amount(due));
    }

    private static String accountNo(Long id) {
        return String.format("%09d", id);
    }

    private static BigDecimal amount(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
