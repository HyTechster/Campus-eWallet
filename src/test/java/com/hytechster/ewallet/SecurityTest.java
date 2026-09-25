package com.hytechster.ewallet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.user.User;

class SecurityTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousUsersAreSentToLogin() throws Exception {
        mvc.perform(get("/wallet")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/login")).andExpect(status().isOk());
        mvc.perform(get("/register")).andExpect(status().isOk());
    }

    @Test
    void studentCannotReachAdminOrMerchantPages() throws Exception {
        var student = user(principal(newStudent("Nosy")));
        mvc.perform(get("/admin").with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users").with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/export.csv?from=2026-01-01&to=2026-12-31").with(student)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/users/1/freeze").with(student).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/merchant").with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/wallet").with(student)).andExpect(status().isOk());
    }

    @Test
    void merchantCannotReachAdminOrStudentPages() throws Exception {
        Merchant kafe = newMerchant("Kafe Guard");
        var merchant = user(principal(userService.get(kafe.getUserId())));
        mvc.perform(get("/admin").with(merchant)).andExpect(status().isForbidden());
        mvc.perform(get("/wallet").with(merchant)).andExpect(status().isForbidden());
        mvc.perform(get("/merchant").with(merchant)).andExpect(status().isOk());
    }

    @Test
    void adminCanReachAdminPages() throws Exception {
        var admin = user(principal(newAdmin()));
        mvc.perform(get("/admin").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/users").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/admin/entries").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/wallet").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void studentCannotViewSomeoneElsesEntry() throws Exception {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        User outsider = newStudent("Outsider");
        fund(ali, 1000);
        long entryId = ledger.transfer(ali.getId(), siti.getId(), 300, UUID.randomUUID(), null).entryId();

        mvc.perform(get("/wallet/receipt/" + entryId).with(user(principal(outsider)))).andExpect(status().isNotFound());
        mvc.perform(get("/wallet/receipt/" + entryId).with(user(principal(ali)))).andExpect(status().isOk());
        mvc.perform(get("/wallet/receipt/" + entryId).with(user(principal(siti)))).andExpect(status().isOk());
    }

    @Test
    void postsWithoutCsrfTokenAreRejected() throws Exception {
        User ali = newStudent("Ali");
        User siti = newStudent("Siti");
        fund(ali, 1000);

        mvc.perform(post("/wallet/transfer").with(user(principal(ali)))
                        .param("recipientUserId", siti.getId().toString())
                        .param("amountSen", "100")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(balanceOf(ali.getId())).isEqualTo(1000);
    }
}
