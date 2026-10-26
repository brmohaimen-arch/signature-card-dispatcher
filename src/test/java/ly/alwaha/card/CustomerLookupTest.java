package ly.alwaha.card;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CustomerLookupTest {

    private static final CustomerLookup.Identity ID =
            new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE);

    private static StubOnboardingApi stubWithCustomer() throws Exception {
        StubOnboardingApi s = new StubOnboardingApi();
        s.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
        return s;
    }

    @Test
    void probeShowsRawResponsesAndWhatEachFieldPicks() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            String report = new CustomerLookup(s.config()).probe(ID);
            assertTrue(report.contains("\"accountNumber\""), report);
            assertTrue(report.contains("/body/0/accountNumber -> " + TestSupport.ACCOUNT + " (15 digits)"), report);
            assertTrue(report.contains("Arabic part for the card -> " + TestSupport.NAME), report);
            assertTrue(report.contains("branch on the card -> " + TestSupport.BRANCH + " (Arabic, from the branch table)"), report);
            assertTrue(report.contains("OK: all fields matched"), report);
        }
    }

    @Test
    void probeSaysWhichFieldDidNotMatch() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            Config.Api api = s.config();
            Config.Api wrong = new Config.Api(api.baseUrl(), api.accountPath(), "/body/0/accountNo",
                    api.fullNamePointer(), api.branchPointer(), api.branchesPath(), api.branchesListPointer(),
                    api.branchCodeField(), api.branchNameField(), "", null, 5, api.branches(), null);
            String report = new CustomerLookup(wrong).probe(ID);
            assertTrue(report.contains("STOPPED: account number not found in the onboarding API response (/body/0/accountNo)"), report);
        }
    }

    @Test
    void cardGetsOnlyTheArabicPartOfTheName() throws Exception {
        assertEquals("محمد عبدالباسط عمر هجول",
                CustomerLookup.arabicPart("MOHAMED ABDULBASET OMAR HAGUL محمد عبدالباسط عمر هجول"));
        assertEquals("أحمد سالم", CustomerLookup.arabicPart("  أحمد   سالم  "));
        assertThrows(RejectedException.class, () -> CustomerLookup.arabicPart("ENGLISH ONLY NAME"));
    }

    @Test
    void savedArabicBranchNameIsUsedWithoutCallingGetBranches() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            CardData d = new CustomerLookup(s.config()).lookup(ID);
            assertEquals(TestSupport.NAME, d.fullName(), "English part dropped");
            assertEquals(TestSupport.BRANCH, d.branch(), "saved Arabic name");
            assertEquals(TestSupport.ACCOUNT, d.accountNumber());
            assertEquals(1, s.requests.size(), "only the account call: no get CIF, no getBranches");
        }
    }

    @Test
    void withoutASavedArabicNameTheEnglishNameFromGetBranchesIsUsedAndCached() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            CustomerLookup lookup = new CustomerLookup(s.config(Map.of()));
            assertEquals(TestSupport.BRANCH_ENGLISH, lookup.lookup(ID).branch());
            assertEquals(TestSupport.BRANCH_ENGLISH, lookup.lookup(ID).branch());
            assertEquals(1, s.branchCalls(), "branch list fetched once, then cached");
        }
    }

    @Test
    void branchCodeNotInGetBranchesIsRejected() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            s.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, "LY9999999"));
            RejectedException e = assertThrows(RejectedException.class, () -> new CustomerLookup(s.config(Map.of())).lookup(ID));
            assertTrue(e.getMessage().contains("branch code LY9999999 is not in the getBranches list"), e.getMessage());
        }
    }

    @Test
    void getBranchesDownIsTemporary() throws Exception {
        try (StubOnboardingApi s = stubWithCustomer()) {
            CustomerLookup lookup = new CustomerLookup(s.config(Map.of()));
            lookup.lookup(ID); // fills the cache
            s.down = true;
            assertThrows(java.io.IOException.class, () -> lookup.branches(true), "a forced refresh while down fails as temporary");
        }
    }

    @Test
    void arabicSuggestionsForKnownPlacesOnly() {
        assertEquals("فرع سوق الجمعة", BranchNames.suggest("Sooq Eljoma Branch"));
        assertEquals("فرع زليتن", BranchNames.suggest("Zliten Branch"));
        assertEquals("فرع بنغازي", BranchNames.suggest("BENGHAZI BRANCH"));
        assertEquals("مصرف الواحة", BranchNames.suggest("AlWaha Bank"));
        assertNull(BranchNames.suggest("Qaminis Branch"), "no confident match: no guess");
    }
}
