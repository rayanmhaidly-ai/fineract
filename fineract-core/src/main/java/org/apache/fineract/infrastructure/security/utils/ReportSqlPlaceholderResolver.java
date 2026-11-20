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
package org.apache.fineract.infrastructure.security.utils;

import java.util.Map;
import java.util.regex.Pattern;
import org.apache.fineract.infrastructure.security.exception.SqlValidationException;

/**
 * Resolves <code>${name}</code> placeholders in stretchy report / parameter SQL.
 *
 * <p>
 * Report SQL is stored as a template and placeholders cannot be turned into JDBC bind variables without breaking
 * existing report definitions, so every user supplied value is validated against the lexical context of the placeholder
 * before it is substituted:
 * <ul>
 * <li>inside a quoted literal or identifier, the value must not contain quote characters or backslashes, so it cannot
 * terminate the literal;</li>
 * <li>outside quotes, the value must be a number, a single quoted string without quotes or backslashes, or a comma
 * separated list of those.</li>
 * </ul>
 * Substitution is done in a single pass over the template, so substituted values are never re-scanned for placeholders.
 * Trusted system values (current user hierarchy etc.) cannot be overridden by user parameters. Placeholders inside SQL
 * comments and placeholders without a value are left untouched.
 */
public final class ReportSqlPlaceholderResolver {

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final String LITERAL = "(?:-?\\d+(?:\\.\\d+)?|'[^'\"`\\\\]*')";
    private static final Pattern UNQUOTED_VALUE = Pattern.compile("\\s*" + LITERAL + "(?:\\s*,\\s*" + LITERAL + ")*\\s*");
    private static final Pattern QUOTED_FORBIDDEN = Pattern.compile("['\"`\\\\\\x00]");

    private enum Context {
        NONE, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT
    }

    private ReportSqlPlaceholderResolver() {}

    /**
     * @param sql
     *            the trusted report SQL template
     * @param userParams
     *            untrusted values supplied by the caller, keyed by placeholder name (without <code>${}</code>)
     * @param systemParams
     *            trusted values computed by the platform, keyed by placeholder name (without <code>${}</code>)
     * @return the SQL with all known placeholders resolved
     * @throws SqlValidationException
     *             if a user value would change the structure of the SQL or tries to override a system value
     */
    public static String resolve(final String sql, final Map<String, String> userParams, final Map<String, String> systemParams) {
        for (String name : userParams.keySet()) {
            if (systemParams.containsKey(name)) {
                throw new SqlValidationException(String.format("report parameter '%s' is reserved", name));
            }
        }

        final StringBuilder result = new StringBuilder(sql.length());
        Context context = Context.NONE;
        int i = 0;
        while (i < sql.length()) {
            final char c = sql.charAt(i);
            final char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (c == '$' && next == '{' && context != Context.LINE_COMMENT && context != Context.BLOCK_COMMENT) {
                final int end = sql.indexOf('}', i + 2);
                final String name = end > 0 ? sql.substring(i + 2, end) : null;
                if (name != null && PLACEHOLDER_NAME.matcher(name).matches()) {
                    if (systemParams.containsKey(name)) {
                        result.append(systemParams.get(name));
                        i = end + 1;
                        continue;
                    }
                    if (userParams.containsKey(name)) {
                        final String value = userParams.get(name);
                        validate(name, value, context);
                        result.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }

            int consumed = 1;
            switch (context) {
                case NONE -> {
                    if (c == '\'') {
                        context = Context.SINGLE_QUOTE;
                    } else if (c == '"') {
                        context = Context.DOUBLE_QUOTE;
                    } else if (c == '`') {
                        context = Context.BACKTICK;
                    } else if (c == '-' && next == '-') {
                        context = Context.LINE_COMMENT;
                        consumed = 2;
                    } else if (c == '/' && next == '*') {
                        context = Context.BLOCK_COMMENT;
                        consumed = 2;
                    }
                }
                case SINGLE_QUOTE -> context = c == '\'' ? Context.NONE : context;
                case DOUBLE_QUOTE -> context = c == '"' ? Context.NONE : context;
                case BACKTICK -> context = c == '`' ? Context.NONE : context;
                case LINE_COMMENT -> context = c == '\n' || c == '\r' ? Context.NONE : context;
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        context = Context.NONE;
                        consumed = 2;
                    }
                }
            }
            result.append(sql, i, i + consumed);
            i += consumed;
        }
        return result.toString();
    }

    private static void validate(final String name, final String value, final Context context) {
        final boolean valid;
        if (value == null) {
            valid = false;
        } else if (context == Context.NONE) {
            valid = UNQUOTED_VALUE.matcher(value).matches();
        } else {
            valid = !QUOTED_FORBIDDEN.matcher(value).find();
        }
        if (!valid) {
            throw new SqlValidationException(String.format("invalid value for report parameter '%s'", name));
        }
    }
}
