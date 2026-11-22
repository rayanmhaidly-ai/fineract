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
package org.apache.fineract.portfolio.accountdetails.data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.organisation.monetary.data.CurrencyData;

/**
 * One-call overview of a client's financial position, intended for the home screen of a customer facing (mobile) app.
 * Amounts are grouped per currency because balances in different currencies cannot be added up.
 */
@Getter
@RequiredArgsConstructor
public class ClientFinancialSnapshotData implements Serializable {

    private final Long clientId;
    private final LocalDate asOfDate;
    private final List<SavingsTotal> savingsTotals;
    private final List<LoanTotal> loanTotals;
    private final List<ActiveLoan> activeLoans;
    private final Boolean hasOverdue;
    private final LocalDate overdueSinceDate;
    private final NextInstallment nextInstallment;

    @Getter
    @RequiredArgsConstructor
    public static class SavingsTotal implements Serializable {

        private final CurrencyData currency;
        private final Integer activeAccountCount;
        private final BigDecimal totalBalance;
    }

    @Getter
    @RequiredArgsConstructor
    public static class LoanTotal implements Serializable {

        private final CurrencyData currency;
        private final Integer activeLoanCount;
        private final BigDecimal totalOutstanding;
        private final BigDecimal totalOverdue;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ActiveLoan implements Serializable {

        private final Long id;
        private final String accountNo;
        private final String productName;
        private final CurrencyData currency;
        private final BigDecimal totalOutstanding;
        private final Boolean inArrears;
        private final BigDecimal totalOverdue;
        private final LocalDate overdueSinceDate;
    }

    @Getter
    @RequiredArgsConstructor
    public static class NextInstallment implements Serializable {

        private final LocalDate dueDate;
        private final List<CurrencyAmount> totals;
        private final List<InstallmentDue> installments;
    }

    @Getter
    @RequiredArgsConstructor
    public static class CurrencyAmount implements Serializable {

        private final CurrencyData currency;
        private final BigDecimal amount;
    }

    @Getter
    @RequiredArgsConstructor
    public static class InstallmentDue implements Serializable {

        private final Long loanId;
        private final String accountNo;
        private final Integer installmentNumber;
        private final CurrencyData currency;
        private final BigDecimal amountDue;
    }
}
