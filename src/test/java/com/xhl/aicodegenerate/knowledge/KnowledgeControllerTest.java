package com.xhl.aicodegenerate.knowledge;

import com.xhl.aicodegenerate.controller.KnowledgeController;
import com.xhl.aicodegenerate.entity.User;
import com.xhl.aicodegenerate.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeControllerTest {
    @Test void responseKeepsLargeIdsAsStringsAndBindsAuthenticatedUser() throws Exception {
        var store = mock(KnowledgeStore.class);
        var documents = mock(KnowledgeDocumentService.class);
        var users = mock(UserService.class);
        var properties = new KnowledgeProperties();
        User user = new User(); user.setId(7L);
        when(users.getLoginUser(any())).thenReturn(user);
        when(store.list(7)).thenReturn(List.of(new KnowledgeTypes.DocumentView(9007199254740993L, "接口.md", null, null,
                9007199254740995L, 1, "PROCESSING", null, 1, 100, 1)));
        when(store.quota(7)).thenReturn(new KnowledgeTypes.Quota(100, 1, 2097152, 20971520, 50));
        var mvc = MockMvcBuilders.standaloneSetup(new KnowledgeController(store, documents, users, properties)).build();
        mvc.perform(get("/knowledge/documents").param("ownerId", "999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.records[0].pendingVersionId").value("9007199254740995"))
                .andExpect(jsonPath("$.data.records[0].raw").doesNotExist());
        verify(store).list(7);
        verify(store, never()).list(999);
    }
}
