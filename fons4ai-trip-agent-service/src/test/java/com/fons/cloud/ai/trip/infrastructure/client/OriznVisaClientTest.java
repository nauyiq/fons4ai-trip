package com.fons.cloud.ai.trip.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.VisaQuickCheckResult;
import com.fons.cloud.ai.trip.common.dto.VisaRequirementResult;
import com.fons.cloud.ai.trip.infrastructure.config.properties.OriznVisaConfigProperties;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OriznVisaClientTest {

    private HttpServer server;
    private OriznVisaClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/visa/check", this::quickCheck);
        server.createContext("/api/v1/visa", this::detail);
        server.start();
        OriznVisaConfigProperties properties = new OriznVisaConfigProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("enterprise-key");
        client = new OriznVisaClient(RestClient.builder(), new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void convertsBothResponsesAndIgnoresUpgradePlaceholder() {
        VisaQuickCheckResult quick = client.quickCheck("CHN", "JPN");
        assertEquals("visa_required", quick.requirement());
        assertTrue(quick.visaRequired());
        assertEquals("CHN", quick.passport());

        VisaRequirementResult detail = client.checkRequirement("CHN", "JPN", "en");
        assertEquals("en", detail.language());
        assertEquals(2, detail.documentsRequired().size());
        assertEquals(3, detail.passportValidityMonths());
        assertFalse(detail.extendedDetails().containsKey("visa_fee"));
        assertTrue(detail.extendedDetails().containsKey("processing_days"));
    }

    @Test
    void unknownCountryPairIsNotTreatedAsVisaFree() {
        BusinessRuntimeException error = assertThrows(BusinessRuntimeException.class,
                () -> client.quickCheck("USA", "JPN"));
        assertEquals(TripAgentToolResultCode.VISA_RECORD_NOT_FOUND.getCode(), error.getCode());
    }

    private void quickCheck(HttpExchange exchange) throws IOException {
        if (!"enterprise-key".equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
            respond(exchange, 401, "{}");
            return;
        }
        if (exchange.getRequestURI().getRawQuery().contains("passport=USA")) {
            respond(exchange, 404, "{}");
            return;
        }
        respond(exchange, 200, """
                {"passport":"CHN","destination":"JPN","requirement":"visa_required",
                 "visa_required":true,"visa_free_days":null,"_upgrade_preview":{"visa_fees":true}}
                """);
    }

    private void detail(HttpExchange exchange) throws IOException {
        respond(exchange, 200, """
                {"data":{"passport":"CHN","destination":"JPN","requirement":"visa_required",
                 "visa_required":true,"description":"Visa required",
                 "documents_required":["Passport","Application"],"process":["Apply online"],
                 "passport_validity_months":3,"visa_fee":{"upgrade":"paid tier"},
                 "processing_days":{"standard":5}},"meta":{"lang":"en"}}
                """);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
