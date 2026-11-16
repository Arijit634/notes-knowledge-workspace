package org.notesknowledge.websupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.notesknowledge.DatabaseIdleCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wake before session lookup, including anonymous identity and public requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(name="operations.database-idle.enabled",havingValue="true")
public final class DatabaseActivityFilter extends OncePerRequestFilter {
    private final DatabaseIdleCoordinator coordinator;
    private final int managementPort;
    public DatabaseActivityFilter(DatabaseIdleCoordinator coordinator,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:8081}") int managementPort){
        this.coordinator=coordinator;this.managementPort=managementPort;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return request.getLocalPort()==managementPort;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var activity=coordinator.request();
        try{chain.doFilter(request,response);}
        finally{
            if(request.isAsyncStarted()){
                try{request.getAsyncContext().addListener(new jakarta.servlet.AsyncListener(){
                    public void onComplete(jakarta.servlet.AsyncEvent event){activity.close();}
                    public void onError(jakarta.servlet.AsyncEvent event){/* completion owns release */}
                    public void onTimeout(jakarta.servlet.AsyncEvent event){/* completion owns release */}
                    public void onStartAsync(jakarta.servlet.AsyncEvent event){event.getAsyncContext().addListener(this);}
                });}catch(IllegalStateException alreadyCompleted){activity.close();}
            }else activity.close();
        }
    }
}
