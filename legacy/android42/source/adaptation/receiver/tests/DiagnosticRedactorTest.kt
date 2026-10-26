package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun removesIdentifiersAndCredentialsFromLocalReports() {
        val input="""sentBytes=512 interface=4
usbTransport=0123456789abcdef0123456789abcdef
hostId=private-host
systemBuid=private-buid
ssid=private-network
192.168.1.2
aa:bb:cc:dd:ee:ff
/Users/person/Documents/report
Authorization: Bearer secret
-----BEGIN PRIVATE KEY-----
synthetic-test-content
-----END PRIVATE KEY-----"""
        val result=DiagnosticRedactor.clean(input)
        for(secret in listOf("0123456789abcdef","private-host","private-buid","private-network","192.168.1.2","aa:bb:cc:dd:ee:ff","/Users/person","Bearer secret","synthetic-test-content"))
            assertFalse(secret,result.contains(secret))
        assertTrue(result.contains("sentBytes=512 interface=4"))
    }
}
