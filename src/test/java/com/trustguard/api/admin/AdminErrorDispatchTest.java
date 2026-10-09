package com.trustguard.api.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Criterion 25 / RULING 20 (amended). MockMvc never performs the container ERROR dispatch, so this runs
 * on a real port. An unhandled exception in an authenticated admin route must surface as 500; without
 * the ERROR permit in chain 3 the /error dispatch would be denied and hide the failure as 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminErrorDispatchTest extends AdminIntegrationTestBase {

    @Value("${local.server.port}")
    private int port;

    @Test
    void unhandled_exception_in_an_admin_route_returns_500_not_403() throws Exception {
        String token = loginForToken(seedAdmin());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/admin/probe/boom"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertEquals(500, response.statusCode());
    }
}