package com.kb.news.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
public class NewsController {

    private static final Logger logger = LoggerFactory.getLogger(NewsController.class);

    @Value("${naver.client.id}")
    private String clientId;

    @Value("${naver.client.secret}")
    private String clientSecret;

    @GetMapping("/api/news")
    public ResponseEntity<String> getNews(
            @RequestParam(value = "query", required = false, defaultValue = "금융") String query,
            @RequestParam(required = false, defaultValue = "10") int display) {

        logger.info("Received request for news. Query: {}, Display: {}", query, display);

        // 쿼리 인코딩
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String apiURL = "https://openapi.naver.com/v1/search/news.json?query=" + encodedQuery + "&display=" + display;

        // RestTemplate 사용
        RestTemplate restTemplate = new RestTemplate();

        // HTTP 헤더 설정
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Naver-Client-Id", clientId);
        headers.set("X-Naver-Client-Secret", clientSecret);

        // HTTP 요청 엔티티 생성
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            // URI 생성
            URI uri = new URI(apiURL);
            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);

            logger.info("API request successful. Status: {}", response.getStatusCode());
            return response;
        } catch (URISyntaxException e) {
            logger.error("URI Syntax error", e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            logger.error("Error occurred while fetching news", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
