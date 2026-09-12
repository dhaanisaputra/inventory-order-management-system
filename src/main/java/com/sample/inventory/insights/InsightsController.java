package com.sample.inventory.insights;

import com.sample.inventory.common.error.DomainException;
import com.sample.inventory.common.error.ErrorCode;
import com.sample.inventory.common.web.ApiResponse;
import com.sample.inventory.insights.anomaly.AnomalyDto;
import com.sample.inventory.insights.anomaly.AnomalyService;
import com.sample.inventory.insights.assistant.AssistantProvider;
import com.sample.inventory.insights.assistant.AssistantRequest;
import com.sample.inventory.insights.assistant.AssistantResponse;
import com.sample.inventory.insights.forecast.DemandForecastResponse;
import com.sample.inventory.insights.forecast.DemandForecastService;
import com.sample.inventory.insights.recommendation.RecommendationDto;
import com.sample.inventory.insights.recommendation.RecommendationService;
import com.sample.inventory.insights.replenishment.ReplenishmentService;
import com.sample.inventory.insights.replenishment.ReplenishmentSuggestion;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
@RequiredArgsConstructor
public class InsightsController {

  private final DemandForecastService forecast;
  private final ReplenishmentService replenishment;
  private final AnomalyService anomalies;
  private final RecommendationService recommendations;
  private final AssistantProvider assistant;

  @GetMapping("/demand")
  public ResponseEntity<ApiResponse<DemandForecastResponse>> demand(
      @RequestParam long productId, @RequestParam(defaultValue = "30") int days) {
    checkDays(days);
    return ResponseEntity.ok(ApiResponse.ok(forecast.forecast(productId, days)));
  }

  @GetMapping("/replenishment")
  public ResponseEntity<ApiResponse<List<ReplenishmentSuggestion>>> replenishment() {
    return ResponseEntity.ok(ApiResponse.ok(replenishment.suggestAll()));
  }

  @GetMapping("/anomalies")
  public ResponseEntity<ApiResponse<List<AnomalyDto>>> anomalies(
      @RequestParam(defaultValue = "30") int days) {
    checkDays(days);
    return ResponseEntity.ok(ApiResponse.ok(anomalies.scan(days)));
  }

  @GetMapping("/recommendations")
  public ResponseEntity<ApiResponse<List<RecommendationDto>>> recommendations(
      @RequestParam long productId) {
    return ResponseEntity.ok(ApiResponse.ok(recommendations.forProduct(productId)));
  }

  @PostMapping("/assistant")
  public ResponseEntity<ApiResponse<AssistantResponse>> assistant(
      @Valid @RequestBody AssistantRequest req) {
    return ResponseEntity.ok(ApiResponse.ok(assistant.answer(req.query())));
  }

  private static void checkDays(int days) {
    if (days < 1 || days > 90) {
      throw new DomainException(ErrorCode.VALIDATION);
    }
  }
}
