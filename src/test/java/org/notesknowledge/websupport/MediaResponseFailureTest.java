package org.notesknowledge.websupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpServletRequest;

@Tag("FAST")
class MediaResponseFailureTest {
    @Test void rangeFailureRequiresPositiveBoundedLongSizeAndCarriesOnlySafeFields() {
        assertThatThrownBy(() -> ApiFailureException.rangeNotSatisfiable(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiFailureException.rangeNotSatisfiable(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiFailureException.of(ApiFailureException.Kind.RANGE_NOT_SATISFIABLE)).isInstanceOf(IllegalArgumentException.class);
        var failure=ApiFailureException.rangeNotSatisfiable(Long.MAX_VALUE);
        assertThat(failure.status().value()).isEqualTo(416);
        assertThat(failure.representationSize()).isEqualTo(Long.MAX_VALUE);
        assertThat(failure.retryAfterSeconds()).isNull();
        assertThat(failure.code()).isEqualTo("range_not_satisfiable");
        assertThat(failure.getCause()).isNull();
    }
    @Test void interruptedCommittedResponseRetainsStatusAndCannotAppendJson() throws Exception {
        var response=new MockHttpServletResponse(); response.setStatus(206);response.setContentLengthLong(100);
        response.getOutputStream().write(new byte[]{1,2,3});response.flushBuffer();
        var request=new MockHttpServletRequest();
        assertThatThrownBy(()->new ApiProblemHandler(null).interruptedStream(new ResponseStreamInterruptedException(),request,response))
                .isInstanceOf(ResponseStreamInterruptedException.class);
        assertThat(request.getAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsByteArray()).containsExactly(1,2,3);
        assertThat(response.getHeader("Content-Length")).isEqualTo("100");
        assertThat(new ResponseStreamInterruptedException()).hasMessage("response_stream_interrupted").hasNoCause();
    }
    @Test void interruptedCompletionPreservesActualStatusButReportsFailureAndClearsMarker() throws Exception {
        var request=new MockHttpServletRequest("GET","/synthetic");var response=new MockHttpServletResponse();response.setStatus(206);
        new RequestCorrelationFilter(new RequestTraceContext()).doFilter(request,response,(req,res)->
                req.setAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE,Boolean.TRUE));
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(request.getAttribute(ResponseStreamInterruptedException.REQUEST_ATTRIBUTE)).isNull();
    }
}
