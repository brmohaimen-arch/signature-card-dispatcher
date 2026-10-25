package ly.alwaha.card;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CustomerLookupTest {

    @Test
    void probeShowsRawResponsesAndWhatEachPointerPicks() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH));
            String report = new CustomerLookup(onboardingApi.config())
                    .probe(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
            assertTrue(report.contains("\"accountId\""), report);
            assertTrue(report.contains("/body/0/accountId -> " + TestSupport.ACCOUNT), report);
            assertTrue(report.contains("get CIF (" + TestSupport.ACCOUNT.substring(0, 7) + ")"), report);
            assertTrue(report.contains("OK: all pointers matched"), report);
        }
    }

    @Test
    void probeSaysWhichPointerDidNotMatch() throws Exception {
        try (StubOnboardingApi onboardingApi = new StubOnboardingApi()) {
            onboardingApi.byNationalId.put(TestSupport.NID, new StubOnboardingApi.Customer(TestSupport.ACCOUNT, TestSupport.NAME, TestSupport.BRANCH));
            Config.Api api = onboardingApi.config();
            Config.Api wrong = new Config.Api(api.baseUrl(), api.accountPath(), api.cifPath(), "/body/0/accountNo",
                    api.fullNamePointer(), api.branchPointer(), 7, "", null, 5);
            String report = new CustomerLookup(wrong)
                    .probe(new CustomerLookup.Identity(TestSupport.NID, TestSupport.PASSPORT, TestSupport.PHONE));
            assertTrue(report.contains("STOPPED: account number not found in the onboarding API response (/body/0/accountNo)"), report);
        }
    }
}
