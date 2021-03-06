package org.apereo.cas.util;

import lombok.val;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Scott Battaglia
 * @since 3.0.0
 */
@Slf4j
public class DefaultUniqueTicketIdGeneratorTests {

    @Test
    public void verifyUniqueGenerationOfTicketIds() {
        val generator = new DefaultUniqueTicketIdGenerator(10);

        assertNotSame(generator.getNewTicketId("TEST"), generator.getNewTicketId("TEST"));
    }

    @Test
    public void verifySuffix() {
        val suffix = "suffix";
        val generator = new DefaultUniqueTicketIdGenerator(10, suffix);

        assertTrue(generator.getNewTicketId("test").endsWith(suffix));
    }

    @Test
    public void verifyNullSuffix() {
        val lengthWithoutSuffix = 23;
        val generator = new DefaultUniqueTicketIdGenerator(12, null);

        val ticketId = generator.getNewTicketId("test");
        assertEquals(lengthWithoutSuffix, ticketId.length());
    }
}
