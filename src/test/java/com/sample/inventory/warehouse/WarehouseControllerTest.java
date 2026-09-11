package com.sample.inventory.warehouse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WarehouseController.class)
class WarehouseControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean WarehouseService service;

  @Test
  void createReturns201Wrapped() throws Exception {
    when(service.create(any())).thenReturn(new WarehouseResponse(1L, "JKT-1", "Jakarta", 10));
    mvc.perform(post("/api/v1/warehouses")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"JKT-1\",\"name\":\"Jakarta\",\"priority\":10}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.code").value("JKT-1"));
  }

  @Test
  void listReturnsPlainArray() throws Exception {
    when(service.list()).thenReturn(List.of(new WarehouseResponse(1L, "JKT-1", "Jakarta", 10)));
    mvc.perform(get("/api/v1/warehouses"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].priority").value(10));
  }
}
