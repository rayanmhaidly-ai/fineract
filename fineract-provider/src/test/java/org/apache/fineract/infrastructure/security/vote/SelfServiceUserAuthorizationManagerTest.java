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
package org.apache.fineract.infrastructure.security.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class SelfServiceUserAuthorizationManagerTest {

    private final SelfServiceUserAuthorizationManager manager = new SelfServiceUserAuthorizationManager();

    @ParameterizedTest(name = "{0} {1} selfServiceUser={2} -> granted={3}")
    @CsvSource({ //
            "GET, /v1/self/clients/1/snapshot, true, true", //
            "GET, /v1/self/clients/1/snapshot, false, false", //
            "GET, /v1/clients/1/snapshot, false, true", //
            "GET, /v1/clients/1/snapshot, true, false", //
            "GET, /v1/clients/external-id/self/snapshot, true, false", //
            "GET, /v1/clients/external-id/self/snapshot, false, true", //
            "GET, /v1/clients/external-id/self/accounts, true, false", //
            "GET, /v1/clients/external-id/self, true, false", //
            "OPTIONS, /v1/clients/1, true, true" })
    void grantsSelfServiceUsersOnlySelfServiceRoutes(String method, String pathInfo, boolean selfServiceUser, boolean granted) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/fineract-provider/api" + pathInfo);
        request.setContextPath("/fineract-provider");
        request.setServletPath("/api");
        request.setPathInfo(pathInfo);

        AppUser user = mock(AppUser.class);
        when(user.isSelfServiceUser()).thenReturn(selfServiceUser);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);

        assertThat(manager.check(() -> authentication, new RequestAuthorizationContext(request)).isGranted()).isEqualTo(granted);
    }
}
