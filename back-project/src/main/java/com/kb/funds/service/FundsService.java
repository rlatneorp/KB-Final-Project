package com.kb.funds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.funds.dto.FundsDTO;
import com.kb.funds.dto.SuikChartDTO;
import com.kb.funds.mapper.FundsMapper;
import com.kb.funds.mapper.SuikChartMapper;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class FundsService {

    private final ObjectMapper objectMapper;
    private final FundsMapper fundsMapper;
    private final RestTemplate restTemplate;
    private final SuikChartMapper suikChartMapper;
    private static final Logger logger = LoggerFactory.getLogger(FundsService.class);

    @Transactional
    public void crawlAndSaveFunds() throws JsonProcessingException {
        int pageNo = 1;
        boolean hasMoreFunds = true;

        while (hasMoreFunds) {
            List<FundsDTO> funds = crawlFundsFromWebsite(pageNo);

            if (funds.isEmpty()) {
                hasMoreFunds = false;
            } else {
                for (FundsDTO fund : funds) {
                    try {
//                        logger.info("Processing fund: {}", fund);
                        List<SuikChartDTO> suikCharts = crawlSuikChart(fund);
                        List<SuikChartDTO> detailedCharts = crawlDetailedData(fund);

                        if (fund.getId() == null) {
                            fundsMapper.insertFund(fund);
//                            logger.info("Inserted new fund ID: {}", fund.getFundCd());

                            if (fund.getId() == null) {
                                logger.error("Fund ID is still null after insert.");
                                continue; // 적절한 오류 처리 추가
                            }
                        } else {
                            fundsMapper.updateFund(fund);
                            suikChartMapper.deleteSuikChartByFundId(fund.getId());
                        }

                        // 통합된 차트 리스트 생성
                        List<SuikChartDTO> combinedCharts = new ArrayList<>(suikCharts);
                        combinedCharts.addAll(detailedCharts);
                        for (SuikChartDTO chart : combinedCharts) {
                            chart.setFundId(fund.getId());
                        }
                        suikChartMapper.insertSuikCharts(combinedCharts);
//                        logger.info("Inserted SuikCharts for Fund ID: {}", fund.getFundCd());

                    } catch (Exception e) {
                        logger.error("Error occurred while processing fund ID: {}", fund.getId(), e);
                    }
                }
                pageNo++;
            }
        }
    }


    private List<FundsDTO> crawlFundsFromWebsite(int pageNo) throws JsonProcessingException {
        String url = "https://www.samsungfund.com/api/v1/fund/product.do?graphTerm=1&orderBy=DESC&orderByType=SUIK_RT3&pageNo=" + pageNo;
        String jsonResponse = restTemplate.getForObject(url, String.class);
        List<FundsDTO> fundList = objectMapper.readValue(jsonResponse, new TypeReference<List<FundsDTO>>() {});
        JsonNode rootNode = objectMapper.readTree(jsonResponse);

        for (FundsDTO fund : fundList) {
            Long fundId = fund.getId();

            if (fundId == null) {
                continue;
            }

            JsonNode fundNode = null;

            for (JsonNode node : rootNode) {
                if (node.get("fId").asText().equals(fundId.toString())) {
                    fundNode = node;
                    break;
                }
            }

            if (fundNode != null) {
                JsonNode suikChartNode = fundNode.get("suikChart");

                if (suikChartNode != null && suikChartNode.isArray() && suikChartNode.size() > 0) {
                    List<SuikChartDTO> suikCharts = new ArrayList<>();
                    for (JsonNode chart : suikChartNode) {
                        SuikChartDTO suikChart = objectMapper.treeToValue(chart, SuikChartDTO.class);
                        suikChart.setFundId(fundId);
                        suikCharts.add(suikChart);
                    }
                    fund.setSuikChart(suikCharts);
                }
            }
        }

        return fundList;
    }

    @Scheduled(fixedRate = 3600000) // 1시간마다 실행
    public void scheduleCrawl() {
        try {
//            logger.info("Scheduled crawl started at: {}", LocalDateTime.now());
            crawlAndSaveFunds();
        } catch (JsonProcessingException e) {
            logger.error("Error occurred while crawling funds: {}", e.getMessage(), e);
        }
    }

    private List<SuikChartDTO> crawlSuikChart(FundsDTO fund) {
        List<SuikChartDTO> suikCharts = new ArrayList<>(fund.getSuikChart());
//        System.out.println("Fetched SuikCharts: " + suikCharts.size() + " for fund ID: " + fund.getId());
        suikCharts.forEach(suikChart -> {
            if (fund.getId() != null) {
                suikChart.setFundId(fund.getId());
            }
        });
        return suikCharts;
    }

    private List<SuikChartDTO> crawlDetailedData(FundsDTO fund) {
        List<SuikChartDTO> suikCharts = new ArrayList<>();
        if (fund == null || fund.getId() == null) {
            logger.error("Invalid fund object or fund ID is null.");
            return suikCharts; // fund가 null이거나 ID가 null일 경우 빈 리스트 반환
        }

        String fundCd = fund.getFundCd();
        String url = "https://www.samsungfund.com/fund/product/view.do?id=" + fundCd;

        ChromeOptions options = new ChromeOptions();
        System.setProperty("webdriver.chrome.driver", "C:/chromeDriver/chromedriver-win64/chromedriver.exe");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--headless");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.90 Safari/537.36");
        options.addArguments("--verbose");
        options.addArguments("disable-blink-features=AutomationControlled");
        System.setProperty("webdriver.http.factory", "jdk-http-client");

        WebDriver driver = new ChromeDriver(options);

        try {
            driver.get(url);
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("div.assets-weight__table")));

            String htmlResponse = driver.getPageSource();
            Document document = Jsoup.parse(htmlResponse);

            String rawGijunYmdStr = document.select("div.assets-weight__table .noti").text();
            logger.debug("Extracted raw gijunYmdStr: {}", rawGijunYmdStr);

            String gijunYmdStr = null;
            Pattern pattern = Pattern.compile("(\\d{2})\\.\\s*(\\d{1,2})\\.\\s*(\\d{1,2})");
            Matcher matcher = pattern.matcher(rawGijunYmdStr);
            if (matcher.find()) {
                gijunYmdStr = matcher.group(0).replace(" ", "");
                logger.debug("Extracted gijunYmd: {}", gijunYmdStr);
            } else {
                logger.warn("Could not find valid gijunYmd in string: {}", rawGijunYmdStr);
                return suikCharts; // gijunYmd가 없으면 빈 리스트 반환
            }

            LocalDate gijunYmd = null;
            if (gijunYmdStr != null && !gijunYmdStr.isEmpty()) {
                try {
                    gijunYmd = LocalDate.parse(gijunYmdStr, DateTimeFormatter.ofPattern("yy.M.d"));
                } catch (DateTimeParseException e) {
                    logger.warn("Invalid gijunYmd format for fund ID: {}. Value: {}", fund.getId(), gijunYmdStr);
                }
            } else {
                logger.warn("Missing gijunYmd for fund ID: {}", fund.getId());
                return suikCharts; // gijunYmd가 null일 경우 빈 리스트 반환
            }

            Elements rows = document.select("div.assets-weight__table tbody tr");
            if (rows.isEmpty()) {
                logger.warn("No rows found in the detailed data for fund ID: {}", fundCd);
            }

            for (Element row : rows) {
                Elements columns = row.select("td");
                logger.debug("Columns: {}", columns.eachText());

                if (columns.size() < 3) {
                    continue;
                }

                SuikChartDTO suikChart = new SuikChartDTO();
                suikChart.setFundId(fund.getId());

                String 평가액Str = columns.get(1).text().replaceAll(",", "");
                String 비중Str = columns.get(2).text().replaceAll("%", "").trim();

                logger.debug("Columns: {}, 평가액Str: {}, 비중Str: {}", columns, 평가액Str, 비중Str);

                double 평가액 = 0.0;
                double 비중 = 0.0;

                if ("-".equals(평가액Str) || 평가액Str.isEmpty()) {
                    평가액 = 0.0;
                    logger.warn("평가액이 '-'로 설정되어 0.0으로 변환되었습니다. fund ID: {}", fund.getId());
                } else {
                    try {
                        평가액 = Double.parseDouble(평가액Str);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid format for 평가액: {}", 평가액Str);
                    }
                }

                if ("-".equals(비중Str) || 비중Str.isEmpty()) {
                    비중 = 0.0;
                    logger.warn("비중이 '-'로 설정되어 0.0으로 변환되었습니다. fund ID: {}", fund.getId());
                } else {
                    try {
                        비중 = Double.parseDouble(비중Str) / 100;
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid format for 비중: {}", 비중Str);
                    }
                }

                suikChart.setGijunYmd(gijunYmd);
                suikChart.setCategory(columns.get(0).text());
                suikChart.setEvaluationAmount(평가액);
                suikChart.setWeight(비중);

                suikCharts.add(suikChart);
            }
        } catch (Exception e) {
            logger.error("Error occurred while crawling detailed data for fund ID: {}. Error: {}", fund.getId(), e);
        } finally {
            driver.quit();
        }
        return suikCharts;
    }


    public List<FundsDTO> searchFunds(String keyword) {
        return fundsMapper.searchFunds(keyword);
    }

    public List<FundsDTO> findAllFunds() {
        return fundsMapper.findAllFunds();
    }
}
