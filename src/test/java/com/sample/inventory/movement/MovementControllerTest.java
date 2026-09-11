package com.sample.inventory.movement;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MovementController.class)
class MovementControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean MovementService service;

  @Test
  void searchReturnsPagedMovements() throws Exception {
    var dto =
        new MovementResponse(
            1L,
            2L,
            "KB-100",
            3L,
            "JKT-1",
            MovementType.RESERVE,
            5,
            "ORDER",
            9L,
            Instant.parse("2026-09-11T00:00:00Z"));
    var page = new PageImpl<>(List.of(dto));
    when(service.search(isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(page);
    mvc.perform(get("/api/v1/stock-movements"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].type").value("RESERVE"))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }
}
