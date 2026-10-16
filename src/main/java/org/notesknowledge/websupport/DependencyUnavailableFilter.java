package org.notesknowledge.websupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/** JDBC session failures occur before MVC. Never turn unavailable authority into
 * an anonymous fallback or expose the infrastructure exception to the browser. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class DependencyUnavailableFilter extends OncePerRequestFilter {
    private static final Set<String> REPRESENTATION_HEADERS = Set.of("content-type", "content-length",
            "content-range", "content-encoding", "content-disposition", "etag", "accept-ranges", "location");
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
            var retained = new HttpHeaders();
            for (String header : response.getHeaderNames()) {
                if (!REPRESENTATION_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
                    retained.put(header, new ArrayList<>(response.getHeaders(header)));
                }
            }
            // resetBuffer/setHeader(null) can leave servlet content-length and
            // content-type state behind. Reset the representation completely,
            // then preserve existing security/correlation and cookie headers.
            response.reset();
            retained.forEach((header, values) -> values.forEach(value -> response.addHeader(header, value)));
            problems.writeServiceUnavailable(request, response);
        }
    }

    static boolean unavailable(Throwable failure) {
        // Typed infrastructure failures only. Bad SQL, integrity violations and
        // programming defects are not relabelled as temporary dependency loss.
        boolean unavailable = false;
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof InvalidDataAccessResourceUsageException
                    || failure instanceof DataIntegrityViolationException) return false;
            if (failure instanceof CannotCreateTransactionException
                    || failure instanceof DataAccessResourceFailureException) unavailable = true;
        }
        return failure == null && unavailable;
    }
}
