package org.notesknowledge;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.notesknowledge.websupport.DatabaseActivityFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("FAST") @Tag("SECURITY")
class DatabaseActivityFilterTest {
    final DatabaseIdleCoordinator coordinator=mock(DatabaseIdleCoordinator.class);
    final DatabaseIdleCoordinator.Reservation permit=mock(DatabaseIdleCoordinator.Reservation.class);
    final DatabaseActivityFilter filter=new DatabaseActivityFilter(coordinator,8081);
    MockHttpServletRequest request(String path,int port){
        var request=new MockHttpServletRequest("GET",path);request.setLocalPort(port);return request;
    }
    @Test void anonymousGoogleStartCallbackRecoveryPublicAndAuthenticatedTrafficWakeBeforeTheChain()throws Exception{
        for(String path:java.util.List.of("/api/auth/oidc/google/start","/api/auth/oidc/google/callback","/api/auth/password/reset","/api/public/explore","/api/notes")){
            reset(coordinator,permit);when(coordinator.request()).thenReturn(permit);
            filter.doFilter(request(path,8080),new MockHttpServletResponse(),(r,s)->{
                verify(coordinator).request();verify(permit,never()).close();
            });
            verify(permit).close();
        }
    }
    @Test void loopbackManagementDoesNotWakeDatabaseAndForwardedHeadersCannotChangeLocalListener()throws Exception{
        filter.doFilter(request("/actuator/health/liveness",8081),new MockHttpServletResponse(),(r,s)->{});
        verifyNoInteractions(coordinator);
        when(coordinator.request()).thenReturn(permit);
        var real=request("/actuator/health/liveness",8080);real.addHeader("X-Forwarded-Port","8081");
        filter.doFilter(real,new MockHttpServletResponse(),(r,s)->{});verify(coordinator).request();verify(permit).close();
    }
    @Test void asynchronousWorkHoldsActivityUntilActualCompletion()throws Exception{
        when(coordinator.request()).thenReturn(permit);
        var request=request("/api/notes",8080);request.setAsyncSupported(true);var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->r.startAsync(r,s));
        verify(permit,never()).close();request.getAsyncContext().complete();verify(permit).close();
    }
    @Test void failedRequestStillReleasesActivityAndTheOriginalFailureIsPreserved(){
        when(coordinator.request()).thenReturn(permit);
        assertThatThrownBy(()->filter.doFilter(request("/api/notes",8080),new MockHttpServletResponse(),(r,s)->{throw new ServletException("synthetic");}))
            .isInstanceOf(ServletException.class);
        verify(permit).close();
    }
    @Test void filterRunsBeforeJdbcSessionLookup(){
        int order=DatabaseActivityFilter.class.getAnnotation(org.springframework.core.annotation.Order.class).value();
        assertThat(order).isLessThan(org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER);
    }
}
