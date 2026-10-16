package org.notesknowledge.websupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** JDBC session failures occur before MVC. Never turn unavailable authority into
 * an anonymous fallback or expose the infrastructure exception to the browser. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class DependencyUnavailableFilter extends OncePerRequestFilter {
    private final ApiProblemWriter problems;

    public DependencyUnavailableFilter(ApiProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException failure) {
            if (!unavailable(failure)) throw failure;
            if (response.isCommitted()) {
                // Bytes cannot become a JSON error. Preserve the existing
                // interruption contract, without carrying SQL/connection data.
                request.setAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE, Boolean.TRUE);
                throw new ResponseStreamInterruptedException();
            }
            for (String header : List.of(HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_RANGE,
                    HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.ETAG, HttpHeaders.ACCEPT_RANGES, HttpHeaders.LOCATION)) {
                response.setHeader(header, null);
            }
            problems.writeServiceUnavailable(request, response);
        }
    }

    static boolean unavailable(Throwable failure) {
        // Typed infrastructure failures only. Bad SQL, integrity violations and
        // programming defects are not relabelled as temporary dependency loss.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof CannotCreateTransactionException
                    || failure instanceof DataAccessResourceFailureException) return true;
        }
        return false;
    }
}
