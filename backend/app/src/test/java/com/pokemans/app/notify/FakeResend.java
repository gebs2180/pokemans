package com.pokemans.app.notify;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A stand-in for Resend's POST /emails on a local port. Answers 200 with a new id unless told otherwise, and
 * remembers every request.
 */
class FakeResend implements AutoCloseable {

    /** One request as received. */
    record Request(String authorization, String idempotencyKey, String contentType, String body) {

        String to() {
            List<String> to = JsonPath.read(body, "$.to");
            return to.getFirst();
        }

        String field(String name) {
            return JsonPath.read(body, "$." + name);
        }
    }

    /** A canned answer; a delay makes the client time out. */
    record Answer(int status, String body, Duration delay) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Answer> answers = new ConcurrentLinkedDeque<>();

    FakeResend() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/emails", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), exchange.getRequestHeaders().getFirst("Content-Type"), body));
            Answer answer = answers.pollFirst();
            if (answer == null) {
                answer = new Answer(200, "{\"id\":\"" + UUID.randomUUID() + "\"}", Duration.ZERO);
            }
            if (!answer.delay().isZero()) {
                try {
                    Thread.sleep(answer.delay());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** The next request gets this answer instead of a 200. */
    void answerNext(int status, String body) {
        answers.addLast(new Answer(status, body, Duration.ZERO));
    }

    void answerNextAfter(Duration delay) {
        answers.addLast(new Answer(200, "{\"id\":\"late\"}", delay));
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    List<Request> requestsTo(String email) {
        List<Request> to = new ArrayList<>();
        for (Request r : requests) {
            if (r.to().equals(email)) {
                to.add(r);
            }
        }
        return to;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
