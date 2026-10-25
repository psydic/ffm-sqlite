package io.github.revsnai.shop.web;

import io.github.revsnai.shop.ShopException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

import java.util.Map;

/** Business errors become {@code {"error": "..."}} with the status the exception carries (400, 404, 409). */
@Singleton
public class ShopExceptionHandler implements ExceptionHandler<ShopException, HttpResponse<Map<String, String>>> {

    @Override
    public HttpResponse<Map<String, String>> handle(HttpRequest request, ShopException e) {
        return HttpResponse.<Map<String, String>>status(io.micronaut.http.HttpStatus.valueOf(e.status()))
                .body(Map.of("error", e.getMessage()));
    }
}
