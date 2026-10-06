package com.mstech.vitrin.gateway.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.GatewayProblems;
import io.micrometer.tracing.Tracer;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Problems {
    private Problems() {}

    public static GatewayProblems create() {
        // The application's mapper flattens ProblemDetail properties through this mix-in.
        JsonMapper mapper =
                JsonMapper.builder()
                        .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                        .build();
        return new GatewayProblems(mapper, emptyTracers());
    }

    public static ObjectProvider<Tracer> emptyTracers() {
        return new EmptyTracers();
    }

    public static JsonNode body(MockHttpServletResponse response)
            throws UnsupportedEncodingException {
        return Objects.requireNonNull(
                JsonMapper.builder().build().readTree(response.getContentAsString()));
    }

    public static void assertProblem(MockHttpServletResponse response, int status, String code)
            throws UnsupportedEncodingException {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = body(response);
        assertThat(body.path("type").asString()).isEqualTo("about:blank");
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        if (status == 401 || status == 403) {
            assertThat(body.path("deniedBy").asString()).isEqualTo("gateway");
        } else {
            assertThat(body.has("deniedBy")).isFalse();
        }
    }

    private static final class EmptyTracers implements ObjectProvider<Tracer> {
        @Override
        public Tracer getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Tracer getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public @Nullable Tracer getIfAvailable() {
            return null;
        }

        @Override
        public @Nullable Tracer getIfUnique() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<Tracer> iterator() {
            throw new UnsupportedOperationException();
        }
    }
}
