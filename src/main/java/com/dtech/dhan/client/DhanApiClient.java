package com.dtech.dhan.client;

import com.dtech.dhan.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * REST Client for Dhan API v2
 * Uses proper DTOs for type-safe API calls (NOT plain JSON Maps)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DhanApiClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${dhan.api.base-url:https://api.dhan.co/v2}")
    private String baseUrl;

    @Value("${dhan.api.timeout:30000}")
    private int timeout;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== Historical Data ====================

    /**
     * Get daily historical data
     */
    public DhanHistoricalResponse getHistoricalDaily(
            String securityId,
            String exchangeSegment,
            String instrument,
            LocalDate fromDate,
            LocalDate toDate,
            String accessToken
    ) {
        String url = baseUrl + "/charts/historical";

        DhanHistoricalRequest request = DhanHistoricalRequest.builder()
                .securityId(securityId)
                .exchangeSegment(exchangeSegment)
                .instrument(instrument)
                .expiryCode(0)
                .fromDate(fromDate.format(DATE_FORMATTER))
                .toDate(toDate.format(DATE_FORMATTER))
                .build();

        return executePost(url, request, DhanHistoricalResponse.class, accessToken);
    }

    /**
     * Get intraday historical data
     */
    public DhanHistoricalResponse getHistoricalIntraday(
            String securityId,
            String exchangeSegment,
            String instrument,
            int interval, // 1, 5, 15, 25, 60
            LocalDate fromDate,
            LocalDate toDate,
            String accessToken
    ) {
        String url = baseUrl + "/charts/intraday";

        DhanHistoricalRequest request = DhanHistoricalRequest.builder()
                .securityId(securityId)
                .exchangeSegment(exchangeSegment)
                .instrument(instrument)
                .interval(interval)
                .expiryCode(0)
                .fromDate(fromDate.format(DATE_FORMATTER))
                .toDate(toDate.format(DATE_FORMATTER))
                .build();

        return executePost(url, request, DhanHistoricalResponse.class, accessToken);
    }

    // ==================== Market Quotes ====================

    /**
     * Get market quote for single instrument
     */
    public DhanQuoteResponse.QuoteData getQuote(
            String securityId,
            String exchangeSegment,
            String accessToken
    ) {
        List<DhanQuoteResponse.QuoteData> quotes = getQuotes(
                Collections.singletonList(
                    DhanQuoteRequest.InstrumentIdentifier.builder()
                        .securityId(securityId)
                        .exchangeSegment(exchangeSegment)
                        .build()
                ),
                accessToken
        );
        return quotes.isEmpty() ? null : quotes.get(0);
    }

    /**
     * Get market quotes for multiple instruments
     */
    public List<DhanQuoteResponse.QuoteData> getQuotes(
            List<DhanQuoteRequest.InstrumentIdentifier> instruments,
            String accessToken
    ) {
        String url = baseUrl + "/marketfeed/quote";

        DhanQuoteRequest request = DhanQuoteRequest.builder()
                .instruments(instruments)
                .build();

        DhanQuoteResponse response = executePost(url, request, DhanQuoteResponse.class, accessToken);
        return response != null && response.getData() != null ? response.getData() : Collections.emptyList();
    }

    // ==================== LTP (Last Traded Price) ====================

    /**
     * Get LTP for multiple instruments
     */
    public List<DhanLTPResponse.LTPData> getLTP(
            List<DhanLTPRequest.InstrumentIdentifier> instruments,
            String accessToken
    ) {
        String url = baseUrl + "/marketfeed/ltp";

        DhanLTPRequest request = DhanLTPRequest.builder()
                .instruments(instruments)
                .build();

        DhanLTPResponse response = executePost(url, request, DhanLTPResponse.class, accessToken);
        return response != null && response.getData() != null ? response.getData() : Collections.emptyList();
    }

    // ==================== Helper Methods ====================

    /**
     * Execute POST request with proper error handling
     */
    private <T, R> R executePost(String url, T request, Class<R> responseClass, String accessToken) {
        try {
            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<T> entity = new HttpEntity<>(request, headers);

            log.debug("Dhan API POST: {} with request type: {}", url, request.getClass().getSimpleName());

            ResponseEntity<R> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    responseClass
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Dhan API success: {}", response.getStatusCode());
                return response.getBody();
            } else {
                log.error("Dhan API error: status={}", response.getStatusCode());
                throw new RestClientException("Dhan API error: " + response.getStatusCode());
            }

        } catch (RestClientException e) {
            log.error("Failed to call Dhan API: {}", url, e);
            throw new RuntimeException("Dhan API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Create HTTP headers with authentication
     */
    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("access-token", accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }
}
