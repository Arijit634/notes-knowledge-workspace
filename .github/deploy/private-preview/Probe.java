import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Synthetic container-only smoke probe, never included in the application JAR. */
class Probe {
    public static void main(String[] args) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        if(args.length==1 && args[0].equals("await-private-readiness")) {
            // Infrastructure readiness polling, from an operator probe sharing
            // only the network namespace. Never spend the app's 0.1 CPU budget
            // launching an additional JVM for every health check.
            long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();
            while(System.nanoTime()<deadline) {
                try {
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8081/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(3)).GET().build();
                    var result=client.send(request,HttpResponse.BodyHandlers.ofString());
                    if(result.statusCode()==200 && result.body().replaceAll("\\s+","").equals("{\"status\":\"UP\"}")) {
                        System.out.println("private_preview_readiness_confirmed");return;
                    }
                    if(result.statusCode()!=503)throw new IllegalStateException("unexpected_private_readiness_status");
                }catch(java.io.IOException starting) {
                    // A listener can be absent while the isolated process starts.
                }
                Thread.sleep(200);
            }
            throw new IllegalStateException("private_preview_readiness_deadline_exceeded");
        }
        if(args.length==2 && args[0].startsWith("session-")) {
            var request=HttpRequest.newBuilder(URI.create("http://app:8080/api/auth/csrf"))
                .timeout(Duration.ofSeconds(5)).header("X-Forwarded-Proto","https")
                .header("X-Forwarded-Host","preview.example.test");
            var file=java.nio.file.Path.of(args[1]);
            String[] previous=!args[0].equals("session-create") ? java.nio.file.Files.readString(file).split("\n",2) : null;
            if(previous!=null)request.header("Cookie",previous[0]);
            var result=client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=200)throw new IllegalStateException("preview_session_probe_failed");
            if(previous==null) {
                String cookie=result.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
                java.nio.file.Files.writeString(file,cookie+"\n"+result.body());
                java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } else {
                // The default BREACH defence re-masks the CSRF token per response.
                // Check session reuse, then prove the OLD CSRF proof still works.
                if(result.headers().firstValue("Set-Cookie").isPresent())throw new IllegalStateException("preview_session_not_reused");
                if(args[0].equals("session-finalize")) {
                    var token=java.util.regex.Pattern.compile("\\\"csrfToken\\\"\\s*:\\s*\\\"([A-Za-z0-9_+=/-]+)\\\"").matcher(previous[1]);
                    if(!token.find())throw new IllegalStateException("preview_csrf_fixture_invalid");
                    var logout=HttpRequest.newBuilder(URI.create("http://app:8080/api/auth/logout"))
                        .timeout(Duration.ofSeconds(5)).header("X-Forwarded-Proto","https")
                        .header("X-Forwarded-Host","preview.example.test").header("Cookie",previous[0])
                        .header("X-CSRF-TOKEN",token.group(1)).POST(HttpRequest.BodyPublishers.noBody()).build();
                    if(client.send(logout,HttpResponse.BodyHandlers.discarding()).statusCode()!=204)
                        throw new IllegalStateException("preview_persisted_csrf_rejected");
                }
            }
            // No cookie, token, derivative or body is emitted.
            System.out.println(previous==null?"synthetic_session_created":"synthetic_session_persisted");
            return;
        }
        if(args.length!=1)throw new IllegalArgumentException();
        boolean edge=args[0].equals("edge");
        boolean trusted=args[0].equals("trusted")||edge;
        String[] paths=trusted ? new String[]{"/login","/api/auth/session","/api/auth/csrf","/api/notes",
            "/api/auth/logout","/api/knowledge/query","/api/public/explore","/api/auth/login/password"}
            : new String[]{"/api/auth/session"};
        int[] expected=trusted ? new int[]{200,200,200,401,403,503,503,503} : new int[]{400};
        for(int i=0;i<paths.length;i++) {
            var request=HttpRequest.newBuilder(URI.create((edge?"http://edge:8088":"http://app:8080")+paths[i])).timeout(Duration.ofSeconds(5))
                .header("X-Forwarded-Proto","https").header("X-Forwarded-Host","preview.example.test");
            if(paths[i].endsWith("logout"))request.POST(HttpRequest.BodyPublishers.noBody());else request.GET();
            var result=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(result.statusCode()!=expected[i])throw new IllegalStateException("preview_probe_status_"+i+"_"+result.statusCode());
            if(paths[i].equals("/login")&&!result.body().contains("google=enabled;email=disabled;preview=private"))throw new IllegalStateException("preview_presentation_missing");
            if(paths[i].equals("/api/auth/csrf")) {
                String cookie=result.headers().firstValue("Set-Cookie").orElseThrow();
                if(!cookie.startsWith("__Host-NKW_PREVIEW=")||!cookie.contains("Secure")||!cookie.contains("HttpOnly")||!cookie.contains("SameSite=Lax")||!cookie.contains("Path=/")||cookie.contains("Domain="))throw new IllegalStateException("unsafe_preview_cookie");
            }
        }
        System.out.println(trusted?"preview_core_denials_and_cookie_passed":"untrusted_forwarding_rejected");
    }
}
