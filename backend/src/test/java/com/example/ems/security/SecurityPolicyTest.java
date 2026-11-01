package com.example.ems.security;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SecurityPolicyTest {
 @Test void confirmedThresholds(){var p=new SecurityPolicy();assertEquals(10,p.getWindowMinutes());assertEquals(3,p.getWarningThreshold());assertEquals(6,p.getFreezeThreshold());p.validate();}
 @Test void unsafeThresholdsRejected(){var p=new SecurityPolicy();p.setFreezeThreshold(2);assertThrows(IllegalArgumentException.class,p::validate);}
 @Test void passwordByteLimit(){assertDoesNotThrow(()->SecurityPolicy.password("Abc12345"));assertThrows(IllegalArgumentException.class,()->SecurityPolicy.password("A1"+"中".repeat(24)));assertDoesNotThrow(()->SecurityPolicy.password("A1"+"中".repeat(23)));}
 @Test void secretsHaveEntropyAndAreNotReused(){String a=SecurityPolicy.secret(),b=SecurityPolicy.secret();assertTrue(a.matches("[A-Za-z0-9_-]{43}"));assertNotEquals(a,b);}
 @Test void addressesAreMasked(){assertEquals("192.168.*.*",SecurityPolicy.address("192.168.1.123"));assertFalse(SecurityPolicy.address("2001:db8::1").contains("db8"));}
 @Test void resetEndpointLimiterRejectsSixthAttempt(){var l=new AttemptLimiter();for(int i=0;i<5;i++)l.check("same");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->l.check("same"));assertDoesNotThrow(()->l.check("other"));}
}
