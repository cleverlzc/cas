package org.apereo.cas.services.util;

import lombok.val;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apereo.cas.services.AbstractRegisteredService;
import org.apereo.cas.services.RegexRegisteredService;
import org.apereo.cas.services.RegisteredServicePublicKeyImpl;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Optional;

import static org.junit.Assert.*;

/**
 * This is {@link RegisteredServicePublicKeyCipherExecutorTests}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
@Slf4j
public class RegisteredServicePublicKeyCipherExecutorTests {

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    
    @Test
    public void verifyCipherUnableToEncodeForStringIsTooLong() {
        val svc = getService("classpath:keys/RSA1024Public.key");

        val ticketId = RandomStringUtils.randomAlphanumeric(120);
        val e = new RegisteredServicePublicKeyCipherExecutor();
        assertNull(e.encode(ticketId, Optional.of(svc)));
    }

    @Test
    public void verifyCipherAbleToEncode() {
        val svc = getService("classpath:keys/RSA4096Public.key");
        val ticketId = RandomStringUtils.randomAlphanumeric(120);
        val e = new RegisteredServicePublicKeyCipherExecutor();
        assertNotNull(e.encode(ticketId, Optional.of(svc)));
    }

    private AbstractRegisteredService getService(final String keyLocation) {
        val svc = new RegexRegisteredService();
        svc.setServiceId("Testing");
        svc.setPublicKey(new RegisteredServicePublicKeyImpl(keyLocation, "RSA"));
        return svc;
    }
    
   
}
