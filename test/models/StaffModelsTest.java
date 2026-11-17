package models;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class StaffModelsTest {
    @Test
    public void staffNavigationIsLimitedToAssignedQueue() {
        for (String section : new String[]{"verifications", "listings", "flags", "reports", "payments", "audit", "team", "activity"}) {
            assertTrue(StaffModels.StaffRole.STAFF_LEAD.canSee(section));
        }
        assertFalse(StaffModels.StaffRole.STAFF_LEAD.canSee("unknown"));
        assertTrue(StaffModels.StaffRole.VERIFICATION_REVIEWER.canSee("verifications"));
        assertFalse(StaffModels.StaffRole.VERIFICATION_REVIEWER.canSee("payments"));
        assertFalse(StaffModels.StaffRole.PAYMENT_REVIEWER.canSee("verifications"));
        assertTrue(StaffModels.StaffRole.PAYMENT_REVIEWER.canSee("payments"));
        assertFalse(StaffModels.StaffRole.SAFETY_MODERATOR.canSee("team"));
        assertTrue(StaffModels.StaffRole.STAFF_LEAD.canSee("team"));
    }
}
