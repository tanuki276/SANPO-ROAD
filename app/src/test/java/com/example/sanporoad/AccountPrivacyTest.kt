package com.example.sanporoad

import org.junit.Assert.*
import org.junit.Test

class AccountPrivacyTest {
    @Test fun masksLocalAndDomain() {
        assertEquals("a**e@e**", maskAccountEmail("alice@example.com"))
        assertEquals("**@e**", maskAccountEmail("a@example.com"))
        assertEquals("**@e**", maskAccountEmail("ab@example.com"))
    }
    @Test fun missingOrMalformedAddressIsNeverDisplayed() {
        listOf(null, "", "secret", "@example.com", "secret@").forEach {
            assertEquals("Googleでログイン済み", maskAccountEmail(it))
        }
    }
}
