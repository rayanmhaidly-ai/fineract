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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.domain.JdbcSupport;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.organisation.monetary.data.CurrencyData;
import org.apache.fineract.portfolio.accountdetails.data.ClientFinancialSnapshotData;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.ActiveLoanRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.InstallmentDueRow;
import org.apache.fineract.portfolio.accountdetails.service.ClientFinancialSnapshotAssembler.SavingsBalanceRow;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.loanaccount.domain.LoanStatus;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClientFinancialSnapshotReadPlatformServiceImpl implements ClientFinancialSnapshotReadPlatformService {

    static final String SAVINGS_SQL = "select sa.currency_code as currencyCode, sa.currency_digits as currencyDigits,"
            + " sa.currency_multiplesof as inMultiplesOf, curr.name as currencyName,"
            + " curr.internationalized_name_code as currencyNameCode, curr.display_symbol as currencyDisplaySymbol,"
            + " sa.account_balance_derived as accountBalance" //
            + " from m_savings_account sa join m_currency curr on curr.code = sa.currency_code"
            + " where sa.client_id = ? and sa.status_enum = ? and sa.deposit_type_enum = ?";

    static final String ACTIVE_LOANS_SQL = "select l.id as id, l.account_no as accountNo, lp.name as productName,"
            + " l.currency_code as currencyCode, l.currency_digits as currencyDigits, l.currency_multiplesof as inMultiplesOf,"
            + " curr.name as currencyName, curr.internationalized_name_code as currencyNameCode,"
            + " curr.display_symbol as currencyDisplaySymbol, l.total_outstanding_derived as totalOutstanding,"
            + " la.total_overdue_derived as totalOverdue, la.overdue_since_date_derived as overdueSinceDate" //
            + " from m_loan l join m_product_loan lp on lp.id = l.product_id join m_currency curr on curr.code = l.currency_code"
            + " left join m_loan_arrears_aging la on la.loan_id = l.id" //
            + " where l.client_id = ? and l.loan_status_id = ? order by l.id";

    private static final String INSTALLMENT_AMOUNT_DUE = "(coalesce(%1$s.principal_amount, 0) - coalesce(%1$s.principal_completed_derived, 0)"
            + " - coalesce(%1$s.principal_writtenoff_derived, 0)"
            + " + coalesce(%1$s.interest_amount, 0) - coalesce(%1$s.interest_completed_derived, 0)"
            + " - coalesce(%1$s.interest_waived_derived, 0) - coalesce(%1$s.interest_writtenoff_derived, 0)"
            + " + coalesce(%1$s.fee_charges_amount, 0) - coalesce(%1$s.fee_charges_completed_derived, 0)"
            + " - coalesce(%1$s.fee_charges_waived_derived, 0) - coalesce(%1$s.fee_charges_writtenoff_derived, 0)"
            + " + coalesce(%1$s.penalty_charges_amount, 0) - coalesce(%1$s.penalty_charges_completed_derived, 0)"
            + " - coalesce(%1$s.penalty_charges_waived_derived, 0) - coalesce(%1$s.penalty_charges_writtenoff_derived, 0))";

    static final String NEXT_INSTALLMENTS_SQL = "select ls.loan_id as loanId, l.account_no as accountNo,"
            + " ls.installment as installmentNumber, ls.duedate as dueDate," //
            + " l.currency_code as currencyCode, l.currency_digits as currencyDigits, l.currency_multiplesof as inMultiplesOf,"
            + " curr.name as currencyName, curr.internationalized_name_code as currencyNameCode,"
            + " curr.display_symbol as currencyDisplaySymbol," //
            + " ls.principal_amount as principal, ls.principal_completed_derived as principalPaid,"
            + " ls.principal_writtenoff_derived as principalWrittenOff," //
            + " ls.interest_amount as interest, ls.interest_completed_derived as interestPaid,"
            + " ls.interest_waived_derived as interestWaived, ls.interest_writtenoff_derived as interestWrittenOff,"
            + " ls.fee_charges_amount as fee, ls.fee_charges_completed_derived as feePaid,"
            + " ls.fee_charges_waived_derived as feeWaived, ls.fee_charges_writtenoff_derived as feeWrittenOff,"
            + " ls.penalty_charges_amount as penalty, ls.penalty_charges_completed_derived as penaltyPaid,"
            + " ls.penalty_charges_waived_derived as penaltyWaived, ls.penalty_charges_writtenoff_derived as penaltyWrittenOff"
            + " from m_loan_repayment_schedule ls join m_loan l on l.id = ls.loan_id"
            + " join m_currency curr on curr.code = l.currency_code"
            + " where l.client_id = ? and l.loan_status_id = ? and ls.completed_derived = false"
            + " and ls.duedate = (select min(ls2.duedate) from m_loan_repayment_schedule ls2 join m_loan l2 on l2.id = ls2.loan_id"
            + " where l2.client_id = ? and l2.loan_status_id = ? and ls2.completed_derived = false and ls2.duedate >= ?" + " and "
            + INSTALLMENT_AMOUNT_DUE.formatted("ls2") + " > 0)" //
            + " order by ls.loan_id, ls.installment";

    private static final RowMapper<SavingsBalanceRow> SAVINGS_MAPPER = (rs, rowNum) -> new SavingsBalanceRow(currency(rs),
            rs.getBigDecimal("accountBalance"));

    private static final RowMapper<ActiveLoanRow> ACTIVE_LOAN_MAPPER = (rs, rowNum) -> new ActiveLoanRow(JdbcSupport.getLong(rs, "id"),
            rs.getString("accountNo"), rs.getString("productName"), currency(rs), rs.getBigDecimal("totalOutstanding"),
            rs.getBigDecimal("totalOverdue"), JdbcSupport.getLocalDate(rs, "overdueSinceDate"));

    private static final RowMapper<InstallmentDueRow> INSTALLMENT_MAPPER = (rs, rowNum) -> new InstallmentDueRow(
            JdbcSupport.getLong(rs, "loanId"), rs.getString("accountNo"), JdbcSupport.getInteger(rs, "installmentNumber"),
            JdbcSupport.getLocalDate(rs, "dueDate"), currency(rs), amountDue(rs));

    private final JdbcTemplate jdbcTemplate;
    private final ClientReadPlatformService clientReadPlatformService;

    @Override
    public ClientFinancialSnapshotData retrieveClientFinancialSnapshot(final Long clientId) {
        // validates that the client exists and is within the user's office hierarchy
        this.clientReadPlatformService.retrieveOne(clientId);

        final LocalDate asOfDate = DateUtils.getBusinessLocalDate();
        final Integer activeLoan = LoanStatus.ACTIVE.getValue();

        final List<SavingsBalanceRow> savings = this.jdbcTemplate.query(SAVINGS_SQL, SAVINGS_MAPPER, clientId,
                SavingsAccountStatusType.ACTIVE.getValue(), DepositAccountType.SAVINGS_DEPOSIT.getValue());
        final List<ActiveLoanRow> loans = this.jdbcTemplate.query(ACTIVE_LOANS_SQL, ACTIVE_LOAN_MAPPER, clientId, activeLoan);
        final List<InstallmentDueRow> installments = loans.isEmpty() ? List.of()
                : this.jdbcTemplate.query(NEXT_INSTALLMENTS_SQL, INSTALLMENT_MAPPER, clientId, activeLoan, clientId, activeLoan, asOfDate);

        return ClientFinancialSnapshotAssembler.assemble(clientId, asOfDate, savings, loans, installments);
    }

    /**
     * Amount still to be paid on an installment: what is scheduled minus what is already paid, waived or written off.
     * Mirrors {@link #INSTALLMENT_AMOUNT_DUE}, which is used to find the next installment date.
     */
    static BigDecimal amountDue(final ResultSet rs) throws SQLException {
        return remaining(rs, "principal", "principalPaid", "principalWrittenOff")
                .add(remaining(rs, "interest", "interestPaid", "interestWaived", "interestWrittenOff"))
                .add(remaining(rs, "fee", "feePaid", "feeWaived", "feeWrittenOff"))
                .add(remaining(rs, "penalty", "penaltyPaid", "penaltyWaived", "penaltyWrittenOff"));
    }

    private static BigDecimal remaining(final ResultSet rs, final String scheduledColumn, final String... settledColumns)
            throws SQLException {
        BigDecimal remaining = JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, scheduledColumn);
        for (String settledColumn : settledColumns) {
            remaining = remaining.subtract(JdbcSupport.getBigDecimalDefaultToZeroIfNull(rs, settledColumn));
        }
        return remaining;
    }

    private static CurrencyData currency(final ResultSet rs) throws SQLException {
        return new CurrencyData(rs.getString("currencyCode"), rs.getString("currencyName"), JdbcSupport.getInteger(rs, "currencyDigits"),
                JdbcSupport.getInteger(rs, "inMultiplesOf"), rs.getString("currencyDisplaySymbol"), rs.getString("currencyNameCode"));
    }
}
