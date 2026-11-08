import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Synthetic container-only smoke probe, never included in the application JAR. */
class Probe {
    public static void main(String[] args) throws Exception {
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        if(args.length!=1)throw new IllegalArgumentException();
        boolean trusted=args[0].equals("trusted");
        String[] paths=trusted ? new String[]{"/login","/api/auth/session","/api/auth/csrf","/api/notes",
            "/api/auth/logout","/api/knowledge/query","/api/public/explore","/api/auth/login/password"}
            : new String[]{"/api/auth/session"};
        int[] expected=trusted ? new int[]{200,200,200,401,403,503,503,503} : new int[]{400};
        for(int i=0;i<paths.length;i++) {
            var request=HttpRequest.newBuilder(URI.create("http://app:8080"+paths[i])).timeout(Duration.ofSeconds(5))
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
