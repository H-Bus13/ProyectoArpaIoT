package com.example.eva2iot

import com.example.eva2iot.bluetooth.HarpProtocol
import org.junit.Assert.*
import org.junit.Test

class HarpProtocolTest {
    @Test fun knownChecksumMatchesFirmware() {
        assertEquals("<FREQ,1,440*01>\n", HarpProtocol.encode("FREQ,1,440"))
        assertEquals("PING", HarpProtocol.decode("<PING*10>"))
    }
    @Test fun corruptedFrameIsRejected() {
        assertNull(HarpProtocol.decode("<FREQ,1,441*01>"))
    }
    @Test fun malformedFramesAreRejected() {
        listOf("FREQ,1,440", "<FREQ,1,440>", "<PING*GG>", "<PING*100>", "<PING*10>junk").forEach {
            assertNull(HarpProtocol.decode(it))
        }
    }
    @Test fun serialLineEndingIsAccepted() {
        assertEquals("PING", HarpProtocol.decode("<PING*10>\r\n"))
    }
    @Test fun boundariesAreValid() {
        assertEquals("FREQ,1,100", HarpProtocol.frequency(1, 100))
        assertEquals("FREQ,4,2000", HarpProtocol.frequency(4, 2000))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidStringIsRejected() { HarpProtocol.frequency(5, 440) }
    @Test(expected = IllegalArgumentException::class) fun excessiveFrequencyIsRejected() { HarpProtocol.frequency(1, 2001) }
    @Test(expected = IllegalArgumentException::class) fun lowFrequencyIsRejected() { HarpProtocol.frequency(1, 99) }
}

