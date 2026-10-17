package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.sql.SQLException;
import java.time.Clock;
import org.hibernate.TransactionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.security.SecurityControlRejectionEvents;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.json.JsonMapper;

@Tag("FAST")
@Tag("API")
@Tag("SECURITY")
class DependencyUnavailableFilterTest {
    private static final String PRIVATE = "SYNTHETIC_PRIVATE_CONNECTION_MATERIAL_731";
    private final JsonMapper json = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
    private final ApiProblemWriter problems = new ApiProblemWriter(json, new RequestTraceContext(),
            new SecurityControlRejectionEvents(Clock.systemUTC()));
    private final DependencyUnavailableFilter filter = new DependencyUnavailableFilter(problems);

    @Test
    void sessionResourceFailureBecomesSanitized503BeforeSessionFilter() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/auth/session");
        request.setQueryString("private=" + PRIVATE);
        var response = new MockHttpServletResponse();
        response.setStatus(206);
        response.setHeader("Content-Length", "100");
        response.setHeader("Content-Range", "bytes 0-99/100");
        response.setHeader("ETag", "\"synthetic-validator\"");
        response.setHeader("Location", "/private-uncommitted-representation");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.getOutputStream().write(new byte[] {1, 2, 3});
        filter.doFilter(request, response, (req, res) -> {
            throw new CannotGetJdbcConnectionException(PRIVATE);
        });
        assertUnavailable(response);
        assertThat(response.getHeader("Content-Length")).isNull();
        assertThat(response.getHeader("Content-Range")).isNull();
        assertThat(response.getHeader("ETag")).isNull();
        assertThat(response.getHeader("Location")).isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(filter.getClass().getAnnotation(Order.class).value())
                .isLessThan(SessionRepositoryFilter.DEFAULT_ORDER);
    }

    @Test
    void wrappedTransactionAcquisitionFailureUsesTheSameContract() throws Exception {
        for (String state : new String[] {null, "57P03"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/notes"), response, (req, res) -> {
                throw new ServletException(new CannotCreateTransactionException(PRIVATE, new SQLException(PRIVATE, state)));
            });
            assertUnavailable(response);
        }
    }

    @Test
    void brokenConnectionDuringJpaRollbackUsesTheSameSanitizedContract() throws Exception {
        for (String state : new String[] {null, "08006", "57P01", "57P02", "57P03"}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/session"), response, (req, res) -> {
                throw new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, state)));
            });
            assertUnavailable(response);
        }
    }

    @Test
    void committedBytesAreNotRewrittenAndOnlyASanitizedInterruptionEscapes() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/notes/synthetic/attachments/synthetic/content");
        var response = new MockHttpServletResponse();
        response.setStatus(206); response.setContentLengthLong(100);
        response.getOutputStream().write(new byte[] {1, 2, 3}); response.flushBuffer();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new CannotCreateTransactionException(PRIVATE);
        })).isInstanceOf(ResponseStreamInterruptedException.class)
                .hasMessage("response_stream_interrupted").hasNoCause();
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getHeader("Content-Length")).isEqualTo("100");
        assertThat(response.getContentAsByteArray()).containsExactly(1, 2, 3);
        assertThat(request.getAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void programmingAndSqlSyntaxDefectsAreNotHiddenAsDependencyOutages() {
        for (var failure : java.util.List.of(new IllegalStateException(PRIVATE),
                new BadSqlGrammarException("synthetic", "select synthetic", new SQLException(PRIVATE)),
                new CannotCreateTransactionException(PRIVATE,
                        new BadSqlGrammarException("synthetic", "select synthetic", new SQLException(PRIVATE))),
                new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, "23505"))),
                new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, "42601"))),
                new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, "22003"))),
                new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, "0A000"))),
                new JpaSystemException(new TransactionException(PRIVATE, new SQLException(PRIVATE, "40P01"))),
                new JpaSystemException(new org.hibernate.HibernateException(PRIVATE)),
                new IllegalStateException(new SQLException(PRIVATE)))) {
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(response.getContentAsByteArray()).isEmpty();
        }
    }

    @Test
    void theMvcBoundaryUsesTheSameNarrowClassification() {
        var handler = new ApiProblemHandler(problems);
        var request = new MockHttpServletRequest("GET", "/api/notes");
        var unavailable = handler.unexpected(new CannotCreateTransactionException(PRIVATE), request);
        assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
        assertThat(unavailable.getBody().getProperties()).containsEntry("code", "service_unavailable");
        var defect = handler.unexpected(new BadSqlGrammarException("synthetic", "select synthetic", new SQLException(PRIVATE)), request);
        assertThat(defect.getStatusCode().value()).isEqualTo(500);
        assertThat(defect.getBody().getProperties()).containsEntry("code", "internal_error");
    }

    private void assertUnavailable(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body = json.readTree(response.getContentAsByteArray());
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("code").asString()).isEqualTo("service_unavailable");
        assertThat(body.path("traceId").asString()).matches("tr_[0-9a-f]{32}");
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE, "SQLException", "select ", "jdbc:");
    }
}
