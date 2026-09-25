package com.hytechster.ewallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.hytechster.ewallet.IntegrationTest;
import com.hytechster.ewallet.merchant.Merchant;
import com.hytechster.ewallet.user.User;

class WalletControllerTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void transferFlowShowsRecipientNameThenPostsOnce() throws Exception {
        User ali = newStudent("Ali Hassan");
        User siti = newStudent("Siti Aminah");
        fund(ali, 5000);
        var asAli = user(principal(ali));

        mvc.perform(post("/wallet/transfer/review").with(asAli).with(csrf())
                        .param("recipient", siti.getEmail()).param("amount", "12.50").param("note", "Lunch"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Siti Aminah")))
                .andExpect(content().string(containsString("RM 12.50")))
                .andExpect(content().string(containsString("name=\"idempotencyKey\"")));

        UUID key = UUID.randomUUID();
        long entriesBefore = entryCount();
        String first = mvc.perform(post("/wallet/transfer").with(asAli).with(csrf())
                        .param("recipientUserId", siti.getId().toString())
                        .param("amountSen", "1250").param("note", "Lunch")
                        .param("idempotencyKey", key.toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        // Double click: same key again.
        mvc.perform(post("/wallet/transfer").with(asAli).with(csrf())
                        .param("recipientUserId", siti.getId().toString())
                        .param("amountSen", "1250").param("note", "Lunch")
                        .param("idempotencyKey", key.toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(first));

        assertThat(entryCount()).isEqualTo(entriesBefore + 1);
        assertThat(balanceOf(ali.getId())).isEqualTo(3750);
        assertThat(balanceOf(siti.getId())).isEqualTo(1250);

        mvc.perform(get(first).with(asAli))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("To Siti Aminah")));
    }

    @Test
    void senderComesFromTheLoginNotTheForm() throws Exception {
        User ali = newStudent("Ali");
        User victim = newStudent("Victim");
        User siti = newStudent("Siti");
        fund(victim, 5000);

        // There is no field to name a sender; Ali's session can only spend Ali's money.
        mvc.perform(post("/wallet/transfer").with(user(principal(ali))).with(csrf())
                        .param("fromUserId", victim.getId().toString())
                        .param("recipientUserId", siti.getId().toString())
                        .param("amountSen", "1000")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balanceOf(victim.getId())).isEqualTo(5000);
        assertThat(balanceOf(siti.getId())).isZero();
    }

    @Test
    void reviewShowsFriendlyErrorsOnTheForm() throws Exception {
        User ali = newStudent("Ali");
        var asAli = user(principal(ali));

        mvc.perform(post("/wallet/transfer/review").with(asAli).with(csrf())
                        .param("recipient", ali.getEmail()).param("amount", "5.00"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You can&#39;t send money to yourself.")));

        mvc.perform(post("/wallet/transfer/review").with(asAli).with(csrf())
                        .param("recipient", "nobody@nowhere.test").param("amount", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Enter an amount like 10.00")));
    }

    @Test
    void frozenUserSeesFailureScreenAndNothingMoves() throws Exception {
        User ali = newStudent("Ali");
        Merchant kafe = newMerchant("Kafe Freeze");
        fund(ali, 2000);
        userService.setFrozen(ali.getId(), true);

        mvc.perform(post("/wallet/pay").with(user(principal(ali))).with(csrf())
                        .param("code", kafe.getCode()).param("amountSen", "500")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("Wallet frozen")))
                .andExpect(content().string(not(containsString("Exception"))));

        assertThat(balanceOf(ali.getId())).isEqualTo(2000);
    }

    @Test
    void pagesRender() throws Exception {
        User ali = newStudent("Ali");
        Merchant kafe = newMerchant("Kafe Render");
        fund(ali, 3000);
        ledger.pay(ali.getId(), kafe.getUserId(), 650, UUID.randomUUID(), "Nasi lemak");
        var asAli = user(principal(ali));

        mvc.perform(get("/wallet").with(asAli)).andExpect(status().isOk())
                .andExpect(content().string(containsString("23.50")))
                .andExpect(content().string(containsString("Kafe Render")));
        mvc.perform(get("/wallet/topup").with(asAli)).andExpect(status().isOk());
        mvc.perform(get("/wallet/transfer").with(asAli)).andExpect(status().isOk());
        mvc.perform(get("/wallet/pay?code=" + kafe.getCode()).with(asAli)).andExpect(status().isOk())
                .andExpect(content().string(containsString(kafe.getCode())));
        mvc.perform(get("/wallet/history?type=PAYMENT").with(asAli)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Money out")));

        var asKafe = user(principal(userService.get(kafe.getUserId())));
        mvc.perform(get("/merchant").with(asKafe)).andExpect(status().isOk())
                .andExpect(content().string(containsString("6.50")));
        mvc.perform(get("/merchant/payments").with(asKafe)).andExpect(status().isOk());

        var asAdmin = user(principal(newAdmin()));
        mvc.perform(get("/admin").with(asAdmin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Pass")));
        mvc.perform(get("/admin/users?q=Ali").with(asAdmin)).andExpect(status().isOk());
        mvc.perform(get("/admin/topup").with(asAdmin)).andExpect(status().isOk());
        mvc.perform(get("/admin/entries?type=PAYMENT").with(asAdmin)).andExpect(status().isOk());
        mvc.perform(get("/admin/export.csv?from=2020-01-01&to=2999-01-01").with(asAdmin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("entry_id,created_at")));
    }
}
