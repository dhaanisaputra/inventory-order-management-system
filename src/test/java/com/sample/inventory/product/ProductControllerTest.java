package com.sample.inventory.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean ProductService service;

  @Test
  void createReturns201Wrapped() throws Exception {
    when(service.create(any())).thenReturn(new ProductResponse(1L, "SKU-1", "Keyboard", true));
    mvc.perform(post("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"SKU-1\",\"name\":\"Keyboard\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.sku").value("SKU-1"))
        .andExpect(jsonPath("$.error").isEmpty());
  }

  @Test
  void searchReturnsPagedResult() throws Exception {
    var page = new PageImpl<>(List.of(new ProductResponse(1L, "SKU-1", "Keyboard", true)));
    when(service.search(eq(null), any(Pageable.class))).thenReturn(page);
    mvc.perform(get("/api/v1/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content[0].sku").value("SKU-1"))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  @Test
  void invalidBodyReturns400() throws Exception {
    mvc.perform(post("/api/v1/products")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sku\":\"\",\"name\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION"));
  }
}
