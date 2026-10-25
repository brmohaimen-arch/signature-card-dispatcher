package ly.alwaha.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomerLookupTest {

    @Test
    void probeShowsRawResponsesAndWhatEachPointerPicks() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
            String report = new CustomerLookup(onboardingApi.config())
                    .probe(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
            assertTrue(report.contains("\"accountNumber\""), report);
            assertTrue(report.contains("/body/0/accountNumber -> " + TestSupport.ACCOUNT), report);
            assertTrue(report.contains("Arabic part for the card -> " + TestSupport.NAME), report);
            assertTrue(report.contains("OK: all fields matched"), report);
        }
    }

    @Test
    void probeSaysWhichPointerDidNotMatch() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
            Config.Api api = onboardingApi.config();
            Config.Api wrong = new Config.Api(api.baseUrl(), api.accountPath(), false, api.cifPath(), "/body/0/accountNo",
                    api.fullNamePointer(), api.branchPointer(), 7, "", null, 5, api.branches());
            String report = new CustomerLookup(wrong)
                    .probe(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
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
    void lookupReturnsArabicNameBranchNameAndFifteenDigits() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
            CardData d = new CustomerLookup(onboardingApi.config())
                    .lookup(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
            assertEquals(TestSupport.NAME, d.fullName(), "English part dropped");
            assertEquals(TestSupport.BRANCH, d.branch(), "code turned into the branch name");
            assertEquals(TestSupport.ACCOUNT, d.accountNumber());
            assertEquals(1, onboardingApi.requests.size(), "get CIF is off by default");
        }
    }

    @Test
    void withGetCifSwitchedOnTheCifIsTheFirstSevenDigits() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH_CODE));
            CardData d = new CustomerLookup(onboardingApi.config(true))
                    .lookup(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
            assertEquals(2, onboardingApi.requests.size());
            assertEquals(StubOnboardingApi.CIF_PATH + TestSupport.ACCOUNT.substring(0, 7), onboardingApi.requests.get(1));
            assertEquals(TestSupport.NAME, d.fullName());
            assertEquals(TestSupport.BRANCH, d.branch());
        }
    }
}
