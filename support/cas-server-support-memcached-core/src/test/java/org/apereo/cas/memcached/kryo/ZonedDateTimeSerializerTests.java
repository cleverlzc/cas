package org.apereo.cas.memcached.kryo;

import lombok.val;

import com.esotericsoftware.kryo.io.ByteBufferOutput;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * This is {@link ZonedDateTimeSerializerTests}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
@RunWith(JUnit4.class)
@Slf4j
public class ZonedDateTimeSerializerTests {

    @Test
    public void verifyTranscoderWorks() {
        val pool = new CasKryoPool();
        try (val kryo = pool.borrow()) {
            val output = new ByteBufferOutput(2048);
            kryo.writeObject(output, ZonedDateTime.now(ZoneOffset.UTC));
            kryo.writeObject(output, ZonedDateTime.now());
        }
    }
}
