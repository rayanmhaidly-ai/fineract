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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.ActiveLoan;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.CurrencyAmount;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.InstallmentDue;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.LoanTotal;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.NextInstallment;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData.SavingsTotal;

final class ClientFinancialSnapshotAssembler {

    record SavingsBalanceRow(CurrencyData currency, BigDecimal balance) {
    }

    record ActiveLoanRow(Long id, String accountNo, String productName, CurrencyData currency, BigDecimal totalOutstanding,
            BigDecimal totalOverdue, LocalDate overdueSinceDate) {
    }

    record InstallmentDueRow(Long loanId, String accountNo, Integer installmentNumber, LocalDate dueDate, CurrencyData currency,
            BigDecimal amountDue) {
    }

    private ClientFinancialSnapshotAssembler() {}

    static ClientFinancialSnapshotData assemble(final Long clientId, final LocalDate asOfDate, final List<SavingsBalanceRow> savings,
            final List<ActiveLoanRow> loans, final List<InstallmentDueRow> upcomingInstallments) {
        final List<ActiveLoan> activeLoans = toActiveLoans(loans);
        final boolean hasOverdue = activeLoans.stream().anyMatch(ActiveLoan::getInArrears);
        final LocalDate overdueSinceDate = activeLoans.stream().map(ActiveLoan::getOverdueSinceDate).filter(d -> d != null)
                .min(Comparator.naturalOrder()).orElse(null);
        return new ClientFinancialSnapshotData(clientId, asOfDate, toSavingsTotals(savings), toLoanTotals(activeLoans), activeLoans,
                hasOverdue, overdueSinceDate, toNextInstallment(asOfDate, upcomingInstallments));
    }

    private static List<SavingsTotal> toSavingsTotals(final List<SavingsBalanceRow> rows) {
        final Map<String, List<SavingsBalanceRow>> byCurrency = new TreeMap<>();
        for (SavingsBalanceRow row : rows) {
            byCurrency.computeIfAbsent(row.currency().getCode(), k -> new ArrayList<>()).add(row);
        }
        final List<SavingsTotal> totals = new ArrayList<>();
        for (List<SavingsBalanceRow> group : byCurrency.values()) {
            final BigDecimal total = group.stream().map(r -> zeroIfNull(r.balance())).reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.add(new SavingsTotal(group.get(0).currency(), group.size(), total));
        }
        return totals;
    }

    private static List<ActiveLoan> toActiveLoans(final List<ActiveLoanRow> rows) {
        final List<ActiveLoan> loans = new ArrayList<>();
        for (ActiveLoanRow row : rows) {
            final BigDecimal overdue = zeroIfNull(row.totalOverdue());
            final boolean inArrears = overdue.signum() > 0;
            loans.add(new ActiveLoan(row.id(), row.accountNo(), row.productName(), row.currency(), zeroIfNull(row.totalOutstanding()),
                    inArrears, overdue, inArrears ? row.overdueSinceDate() : null));
        }
        return loans;
    }

    private static List<LoanTotal> toLoanTotals(final List<ActiveLoan> loans) {
        final Map<String, List<ActiveLoan>> byCurrency = new TreeMap<>();
        for (ActiveLoan loan : loans) {
            byCurrency.computeIfAbsent(loan.getCurrency().getCode(), k -> new ArrayList<>()).add(loan);
        }
        final List<LoanTotal> totals = new ArrayList<>();
        for (List<ActiveLoan> group : byCurrency.values()) {
            final BigDecimal outstanding = group.stream().map(ActiveLoan::getTotalOutstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
            final BigDecimal overdue = group.stream().map(ActiveLoan::getTotalOverdue).reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.add(new LoanTotal(group.get(0).getCurrency(), group.size(), outstanding, overdue));
        }
        return totals;
    }

    private static NextInstallment toNextInstallment(final LocalDate asOfDate, final List<InstallmentDueRow> rows) {
        final List<InstallmentDueRow> payable = rows.stream()
                .filter(r -> r.dueDate() != null && !r.dueDate().isBefore(asOfDate) && zeroIfNull(r.amountDue()).signum() > 0).toList();
        final LocalDate nextDueDate = payable.stream().map(InstallmentDueRow::dueDate).min(Comparator.naturalOrder()).orElse(null);
        if (nextDueDate == null) {
            return null;
        }
        final List<InstallmentDueRow> dueOnDate = payable.stream().filter(r -> r.dueDate().isEqual(nextDueDate))
                .sorted(Comparator.comparing(InstallmentDueRow::loanId).thenComparing(InstallmentDueRow::installmentNumber,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        final Map<String, CurrencyData> currencies = new TreeMap<>();
        final Map<String, BigDecimal> amounts = new TreeMap<>();
        final List<InstallmentDue> installments = new ArrayList<>();
        for (InstallmentDueRow row : dueOnDate) {
            final String code = row.currency().getCode();
            currencies.putIfAbsent(code, row.currency());
            amounts.merge(code, row.amountDue(), BigDecimal::add);
            installments.add(new InstallmentDue(row.loanId(), row.accountNo(), row.installmentNumber(), row.currency(), row.amountDue()));
        }
        final List<CurrencyAmount> totals = amounts.entrySet().stream()
                .map(e -> new CurrencyAmount(currencies.get(e.getKey()), e.getValue())).toList();
        return new NextInstallment(nextDueDate, totals, installments);
    }

    private static BigDecimal zeroIfNull(final BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
